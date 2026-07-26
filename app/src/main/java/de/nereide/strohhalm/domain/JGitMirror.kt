package de.nereide.strohhalm.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicReference

/**
 * JGit-backed [GitMirror]. The single file in `src/main` allowed to import JGit
 * or MINA SSHD, so swapping the engine is a one-file change.
 *
 * Mirroring uses an all-refs refspec (`+refs/…:refs/…`) so every ref — branches,
 * tags, notes — maps 1:1 into the local repository, and
 * `setRemoveDeletedRefs(true)` propagates upstream deletions. A plain clone
 * would track only the head refs, which is how backups end up quietly
 * incomplete.
 *
 * The refspec is written with an ellipsis rather than the literal glob because
 * Kotlin block comments nest: an unbalanced slash-star inside a comment opens a
 * nested comment that never closes, and the file stops compiling.
 */
class JGitMirror(
    private val keyPairProvider: suspend () -> KeyPair,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GitMirror {

    /** Built once and reused; see [installSessionFactory]. */
    @Volatile
    private var factory: SshdSessionFactory? = null

    @Volatile
    private var keyPairs: List<KeyPair> = emptyList()

    private val expectation = AtomicReference<HostKeyExpectation?>(null)

    /** Why the host key check refused, when it did. Never surfaces via an exception. */
    private val rejection = AtomicReference<SyncError?>(null)

    /** The last host key the server presented, for probing. */
    private val observedKey = AtomicReference<String?>(null)

    private companion object {
        const val DIAGNOSTIC_TIMEOUT_MS = 15_000
        /** [org.eclipse.jgit.transport.RemoteSession.exec] takes *seconds*. */
        const val DIAGNOSTIC_TIMEOUT_SECONDS = 15
        const val MAX_SERVER_MESSAGE = 2_000

        /**
         * Stream-level timeout for clone/fetch/ls-remote, in seconds (the unit
         * JGit's `setTimeout` uses). Without it JGit installs no
         * `TimeoutInputStream` at all (`BasePackConnection.init` skips the
         * wrapping when the timeout is 0), so a connection that dies silently —
         * mobile networks, server-side drops — blocks a read forever and the
         * sync never completes and never errors. The timer re-arms on every
         * read, so a slow transfer is fine as long as bytes keep flowing.
         */
        const val TRANSPORT_TIMEOUT_SECONDS = 300
    }

    override suspend fun sync(
        remoteUrl: String,
        destination: File,
        pinnedFingerprint: String?,
        progress: MirrorProgress?,
    ): MirrorOutcome = withContext(io) {
        AndroidSystemReader.install()
        runCatching {
            if (requiresSsh(remoteUrl)) {
                installSessionFactory(pinnedFingerprint, capture = false)
            }
            val monitor = progress?.let(::ThrottledMonitor)
            // runInterruptible, not a plain call: JGit blocks in socket reads
            // that no coroutine can unwind. Without the thread interrupt,
            // cancelling would clear the UI while the transfer kept running.
            runInterruptible {
                if (File(destination, "HEAD").isFile) {
                    fetchInto(destination, remoteUrl, monitor)
                } else {
                    cloneMirror(remoteUrl, destination, monitor)
                }
            }
            MirrorOutcome.Success(
                sizeBytes = sizeBytes(destination),
                refCount = refNames(destination).size,
            )
        }.getOrElse { t ->
            // runCatching catches everything, including the CancellationException
            // that stopping the sync depends on. Swallowing it would turn a
            // deliberate stop into a spurious failure and leave the scope alive.
            if (t is CancellationException) throw t

            // A refused host key closes the session, and JGit only ever sees the
            // socket end. The recorded reason is the truthful one.
            val refused = rejection.get()
            val mapped = SyncErrors.fromException(t)
            val base = refused?.copy(diagnostic = mapped.diagnostic) ?: mapped

            // JGit reads only stdout. When a git host refuses the *repository*
            // — no access, wrong path, does not exist — it explains itself on
            // stderr and closes stdout, which surfaces here as a bare EOF with
            // no reason. Ask the server directly what it said.
            val serverMessage = if (requiresSsh(remoteUrl) && isEmptyReadFailure(t)) {
                readServerMessage(remoteUrl)
            } else {
                null
            }

            MirrorOutcome.Failure(
                if (serverMessage.isNullOrBlank()) {
                    base
                } else {
                    base.copy(
                        code = SyncErrorCode.REMOTE_ERROR,
                        detail = "the server said: ${serverMessage.trim()}",
                    )
                }
            )
        }
    }

    /** An EOF with nothing read: the signature of a server that closed stdout. */
    private fun isEmptyReadFailure(t: Throwable): Boolean =
        generateSequence(t) { it.cause.takeIf { c -> c !== it } }
            .any { it is java.io.EOFException }

    /**
     * Opens one SSH session, runs `git-upload-pack` exactly as the transport
     * would, and returns whatever the server wrote to **stderr**.
     *
     * This is the only way to see a git host's own explanation: JGit's pack
     * transport consumes stdout and discards stderr, so a message like
     * "repository does not exist" is thrown away and the caller sees an
     * unexplained end of stream.
     *
     * Best-effort by design — any failure here returns null rather than
     * replacing the original error with a diagnostic one.
     */
    private fun readServerMessage(remoteUrl: String): String? = runCatching {
        val uri = URIish(remoteUrl)
        val sessionFactory = factory ?: return null
        val session = sessionFactory.getSession(uri, null, FS.DETECTED, DIAGNOSTIC_TIMEOUT_MS)
        try {
            val path = uri.path.orEmpty()
            val process = session.exec("git-upload-pack '$path'", DIAGNOSTIC_TIMEOUT_SECONDS)
            try {
                // On a *healthy* repository, git-upload-pack writes the ref
                // advertisement to stdout, writes nothing to stderr, and waits
                // indefinitely for a request that this probe never sends —
                // sshd keepalives keep the session alive, so reading stderr to
                // EOF would park the sync inside its own error handler forever.
                // The watchdog closes the session at the deadline, which ends
                // the stream and unblocks the read.
                val watchdog = Thread {
                    try {
                        Thread.sleep(DIAGNOSTIC_TIMEOUT_MS.toLong())
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    runCatching { session.disconnect() }
                }.apply {
                    isDaemon = true
                    start()
                }
                try {
                    process.errorStream.bufferedReader().use { it.readText() }
                        .take(MAX_SERVER_MESSAGE)
                } finally {
                    watchdog.interrupt()
                }
            } finally {
                runCatching { process.destroy() }
            }
        } finally {
            runCatching { session.disconnect() }
        }
    }.getOrNull()

    override suspend fun probeHostKey(remoteUrl: String): Result<String> = withContext(io) {
        AndroidSystemReader.install()
        runCatching {
            installSessionFactory(pinnedFingerprint = null, capture = true)
            // ls-remote is the cheapest operation that completes a handshake.
            Git.lsRemoteRepository()
                .setRemote(remoteUrl)
                .setHeads(true)
                .setTimeout(TRANSPORT_TIMEOUT_SECONDS)
                .call()
            observedKey.get() ?: error("the server presented no host key")
        }.recoverCatching { t ->
            // The host key is read before authentication, so a fingerprint can be
            // captured even when the repository itself is unreachable. Reporting
            // success on that basis alone is what let a broken remote be added as
            // if it worked — so the failure is surfaced, enriched with whatever
            // the server wrote to stderr.
            val fingerprint = observedKey.get() ?: throw t
            val serverMessage = readServerMessage(remoteUrl)
            if (serverMessage.isNullOrBlank()) throw t
            throw ProbeRejectedException(fingerprint, serverMessage.trim(), t)
        }
    }

    override fun refNames(destination: File): List<String> {
        if (!File(destination, "HEAD").isFile) return emptyList()
        return runCatching {
            openRepository(destination).use { repo ->
                repo.refDatabase.refs.map { it.name }.sorted()
            }
        }.getOrDefault(emptyList())
    }

    override fun sizeBytes(destination: File): Long {
        if (!destination.isDirectory) return 0L
        return destination.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun cloneMirror(remoteUrl: String, destination: File, monitor: ProgressMonitor?) {
        // A clone that failed part-way leaves a directory with no HEAD. JGit
        // refuses to clone into a non-empty directory, so without this a single
        // failed attempt would wedge the repository permanently. Safe to remove:
        // no HEAD means it was never a usable repository.
        if (destination.exists() && !File(destination, "HEAD").isFile) {
            destination.deleteRecursively()
        }
        destination.parentFile?.mkdirs()
        Git.cloneRepository()
            .setURI(remoteUrl)
            .setDirectory(destination)
            .setBare(true)
            .setMirror(true)
            .setTimeout(TRANSPORT_TIMEOUT_SECONDS)
            .apply { monitor?.let { setProgressMonitor(it) } }
            .call()
            .close()
    }

    private fun fetchInto(destination: File, remoteUrl: String, monitor: ProgressMonitor?) {
        openRepository(destination).use { repo ->
            Git(repo).use { git ->
                git.fetch()
                    .setRemote(remoteUrl)
                    .setRefSpecs(RefSpec("+refs/*:refs/*"))
                    .setRemoveDeletedRefs(true)
                    .setTagOpt(TagOpt.FETCH_TAGS)
                    .setTimeout(TRANSPORT_TIMEOUT_SECONDS)
                    .apply { monitor?.let { setProgressMonitor(it) } }
                    .call()
            }
        }
    }

    /**
     * Bridges JGit's [ProgressMonitor] to [MirrorProgress].
     *
     * JGit calls `update` per object, which for a large repository means tens of
     * thousands of calls. Emitting each one would swamp the UI's state flow for
     * no benefit, so updates are throttled to one every [MIN_INTERVAL_MS] —
     * except task boundaries, which always emit so the phase name never lags.
     */
    private class ThrottledMonitor(private val sink: MirrorProgress) : ProgressMonitor {

        private var task = ""
        private var total = 0
        private var done = 0
        private var lastEmit = 0L

        override fun start(totalTasks: Int) = Unit

        override fun beginTask(title: String?, totalWork: Int) {
            task = title.orEmpty()
            total = if (totalWork == ProgressMonitor.UNKNOWN) 0 else totalWork
            done = 0
            emit(force = true)
        }

        override fun update(completed: Int) {
            done += completed
            emit(force = false)
        }

        override fun endTask() = emit(force = true)

        // JGit polls this between units of work, so it stops the transfer
        // cleanly whenever progress is actually flowing. It is not sufficient on
        // its own — a stall before the first callback is never polled at all,
        // which is what the thread interrupt is for.
        override fun isCancelled(): Boolean = Thread.currentThread().isInterrupted

        override fun showDuration(enabled: Boolean) = Unit

        private fun emit(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmit < MIN_INTERVAL_MS) return
            lastEmit = now
            sink.update(task, done, total)
        }

        private companion object {
            const val MIN_INTERVAL_MS = 250L
        }
    }

    private fun openRepository(destination: File): Repository =
        FileRepositoryBuilder()
            .setGitDir(destination)
            .setMustExist(true)
            .build()

    /**
     * True for `ssh://…` and for scp-style `user@host:path`.
     *
     * The earlier version treated anything containing a colon but not `://` as
     * scp-style, which misclassified `file:/tmp/x` — the form `File.toURI()`
     * produces, with a single slash — as an SSH remote.
     */
    private fun requiresSsh(remoteUrl: String): Boolean {
        val scheme = remoteUrl.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isNotEmpty()) return scheme == "ssh"
        if (remoteUrl.startsWith("file:")) return false
        return remoteUrl.contains(":")
    }

    /**
     * What the host key check should do for the operation currently in flight.
     *
     * [capture] means "probing": accept and record whatever key is presented.
     * Otherwise the key must match [pinned].
     */
    private data class HostKeyExpectation(
        val pinned: String?,
        val capture: Boolean,
    )

    /**
     * Prepares the process-wide SSH session factory for one operation.
     *
     * The factory is built **once and reused**. An earlier version created one
     * per operation and never closed it; each holds an `SshClient` with its own
     * threads and connections, so repeated syncs leaked both — and a server that
     * limits concurrent SSH connections (Codeberg does) then refuses or drops the
     * next one, which surfaces as an unexplained EOF during ref advertisement.
     *
     * Per-operation state therefore lives in [expectation], which the key
     * database consults on each connection, rather than being baked into a new
     * factory each time.
     */
    private suspend fun installSessionFactory(
        pinnedFingerprint: String?,
        capture: Boolean,
    ) {
        expectation.set(HostKeyExpectation(pinnedFingerprint, capture))
        rejection.set(null)
        observedKey.set(null)

        synchronized(this) {
            if (factory == null) {
                val home = SshdEnvironment.homeDir()
                factory = SshdSessionFactoryBuilder()
                    .setPreferredAuthentications("publickey")
                    .setHomeDirectory(home)
                    .setSshDirectory(File(home, ".ssh"))
                    .setDefaultKeysProvider { keyPairs }
                    .setServerKeyDatabase { _, _ -> pinningDatabase }
                    .build(null)
            }
        }
        // The key pair is resolved outside the lock; suspending inside would be
        // wrong and the store caches after the first call anyway.
        keyPairs = listOf(keyPairProvider())
        SshSessionFactory.setInstance(factory)
    }

    private val pinningDatabase = object : ServerKeyDatabase {

        override fun lookup(
            connectAddress: String?,
            remoteAddress: InetSocketAddress?,
            config: ServerKeyDatabase.Configuration?,
        ): List<PublicKey> = emptyList()

        /**
         * Never throws. MINA SSHD calls this during key exchange, and an
         * exception here — or a `false` return — simply closes the session; the
         * cause never reaches JGit, which then reports a bare EOF ("Short read of
         * block") that looks like a network fault.
         *
         * So the reason is recorded in [rejection] instead, and [sync] prefers it
         * over whatever JGit ended up reporting.
         */
        override fun accept(
            connectAddress: String?,
            remoteAddress: InetSocketAddress?,
            serverKey: PublicKey?,
            config: ServerKeyDatabase.Configuration?,
            provider: CredentialsProvider?,
        ): Boolean {
            val algorithm = serverKey?.algorithm ?: "unknown"
            val presented = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey)
            if (presented == null) {
                rejection.set(
                    SyncError(
                        SyncErrorCode.UNKNOWN,
                        "the server presented no readable host key (algorithm $algorithm)"
                    )
                )
                return false
            }
            observedKey.set(presented)

            val current = expectation.get() ?: HostKeyExpectation(null, capture = false)
            return when (val decision = HostKeyVerifier.verify(current.pinned, presented)) {
                is HostKeyDecision.Trusted -> true

                is HostKeyDecision.FirstUse -> {
                    if (current.capture) {
                        true
                    } else {
                        rejection.set(
                            SyncError(
                                SyncErrorCode.HOST_KEY_MISMATCH,
                                "no host key is pinned for this repository; " +
                                    "the server offered $presented ($algorithm)"
                            )
                        )
                        false
                    }
                }

                is HostKeyDecision.Mismatch -> {
                    rejection.set(
                        SyncError(
                            SyncErrorCode.HOST_KEY_MISMATCH,
                            "expected ${decision.stored}, " +
                                "got ${decision.presented} (algorithm $algorithm)"
                        )
                    )
                    false
                }
            }
        }
    }
}
