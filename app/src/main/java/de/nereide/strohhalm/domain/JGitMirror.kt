package de.nereide.strohhalm.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.eclipse.jgit.api.Git
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

    override suspend fun sync(
        remoteUrl: String,
        destination: File,
        pinnedFingerprint: String?,
    ): MirrorOutcome = withContext(io) {
        AndroidSystemReader.install()
        runCatching {
            if (requiresSsh(remoteUrl)) {
                installSessionFactory(pinnedFingerprint, observed = null)
            }
            if (File(destination, "HEAD").isFile) {
                fetchInto(destination, remoteUrl)
            } else {
                cloneMirror(remoteUrl, destination)
            }
            MirrorOutcome.Success(
                sizeBytes = sizeBytes(destination),
                refCount = refNames(destination).size,
            )
        }.getOrElse { t ->
            MirrorOutcome.Failure(SyncErrors.fromException(t))
        }
    }

    override suspend fun probeHostKey(remoteUrl: String): Result<String> = withContext(io) {
        AndroidSystemReader.install()
        val observed = AtomicReference<String?>(null)
        runCatching {
            installSessionFactory(pinnedFingerprint = null, observed = observed)
            // ls-remote is the cheapest operation that completes a handshake.
            Git.lsRemoteRepository().setRemote(remoteUrl).setHeads(true).call()
            observed.get() ?: error("the server presented no host key")
        }.recoverCatching { t ->
            // Authentication may fail after the host key has been read. A captured
            // fingerprint is still a successful probe — the point is to show the
            // user the key, not to prove the key pair is authorised yet.
            observed.get() ?: throw t
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

    private fun cloneMirror(remoteUrl: String, destination: File) {
        destination.parentFile?.mkdirs()
        Git.cloneRepository()
            .setURI(remoteUrl)
            .setDirectory(destination)
            .setBare(true)
            .setMirror(true)
            .call()
            .close()
    }

    private fun fetchInto(destination: File, remoteUrl: String) {
        openRepository(destination).use { repo ->
            Git(repo).use { git ->
                git.fetch()
                    .setRemote(remoteUrl)
                    .setRefSpecs(RefSpec("+refs/*:refs/*"))
                    .setRemoveDeletedRefs(true)
                    .setTagOpt(TagOpt.FETCH_TAGS)
                    .call()
            }
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
     * Installs a process-wide session factory offering only Strohhalm's key and
     * enforcing the pinned host key. JGit's SSH transport is configured globally,
     * so this is set immediately before each operation.
     */
    private suspend fun installSessionFactory(
        pinnedFingerprint: String?,
        observed: AtomicReference<String?>?,
    ) {
        val keyPair = keyPairProvider()
        val factory: SshdSessionFactory = SshdSessionFactoryBuilder()
            .setPreferredAuthentications("publickey")
            .setHomeDirectory(NO_HOME)
            .setSshDirectory(NO_HOME)
            .setDefaultKeysProvider { listOf(keyPair) }
            .setServerKeyDatabase { _, _ -> pinningDatabase(pinnedFingerprint, observed) }
            .build(null)
        SshSessionFactory.setInstance(factory)
    }

    private fun pinningDatabase(
        pinnedFingerprint: String?,
        observed: AtomicReference<String?>?,
    ) = object : ServerKeyDatabase {

        override fun lookup(
            connectAddress: String?,
            remoteAddress: InetSocketAddress?,
            config: ServerKeyDatabase.Configuration?,
        ): List<PublicKey> = emptyList()

        override fun accept(
            connectAddress: String?,
            remoteAddress: InetSocketAddress?,
            serverKey: PublicKey?,
            config: ServerKeyDatabase.Configuration?,
            provider: CredentialsProvider?,
        ): Boolean {
            val presented = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey)
                ?: return false
            observed?.set(presented)

            return when (val decision = HostKeyVerifier.verify(pinnedFingerprint, presented)) {
                is HostKeyDecision.Trusted -> true
                // Probing captures the key; syncing must never trust an unpinned host.
                is HostKeyDecision.FirstUse -> observed != null
                is HostKeyDecision.Mismatch ->
                    throw HostKeyMismatchException(decision.stored, decision.presented)
            }
        }
    }

    private companion object {
        /**
         * JGit insists on a home and .ssh directory. Android has neither, and the
         * key is supplied programmatically, so an unused path keeps it from
         * reading any on-disk config.
         */
        val NO_HOME: File = File("/data/local/tmp/strohhalm-nonexistent")
    }
}
