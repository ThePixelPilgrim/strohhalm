package de.nereide.strohhalm.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.TagOpt
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
            if (File(destination, "HEAD").isFile) {
                fetchInto(destination, remoteUrl, monitor)
            } else {
                cloneMirror(remoteUrl, destination, monitor)
            }
            MirrorOutcome.Success(
                sizeBytes = sizeBytes(destination),
                refCount = refNames(destination).size,
            )
        }.getOrElse { t ->
            // A refused host key closes the session, and JGit only ever sees the
            // socket end. The recorded reason is the truthful one.
            val refused = rejection.get()
            MirrorOutcome.Failure(
                refused?.copy(diagnostic = SyncErrors.fromException(t).diagnostic)
                    ?: SyncErrors.fromException(t)
            )
        }
    }

    override suspend fun probeHostKey(remoteUrl: String): Result<String> = withContext(io) {
        AndroidSystemReader.install()
        runCatching {
            installSessionFactory(pinnedFingerprint = null, capture = true)
            // ls-remote is the cheapest operation that completes a handshake.
            Git.lsRemoteRepository().setRemote(remoteUrl).setHeads(true).call()
            observedKey.get() ?: error("the server presented no host key")
        }.recoverCatching { t ->
            // Authentication may fail after the host key has been read. A captured
            // fingerprint is still a successful probe — the point is to show the
            // user the key, not to prove the key pair is authorised yet.
            observedKey.get() ?: throw t
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

        override fun isCancelled(): Boolean = false

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

    private fun requiresSsh(remoteUrl: String): Boolean =
        remoteUrl.startsWith("ssh://") ||
            (!remoteUrl.contains("://") && remoteUrl.contains(":"))

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
