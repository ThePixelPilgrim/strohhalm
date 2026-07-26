package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.HostKeyDecision
import de.nereide.strohhalm.domain.HostKeyVerifier
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.core.CoreModuleProperties
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** An `ssh://` or scp-style remote, split into the parts SSHD needs. */
data class GitRemote(
    val user: String,
    val host: String,
    val port: Int,
    val path: String,
) {
    companion object {
        private const val DEFAULT_PORT = 22
        private const val DEFAULT_USER = "git"

        fun parse(url: String): GitRemote {
            if (url.startsWith("ssh://")) {
                val rest = url.removePrefix("ssh://")
                val slash = rest.indexOf('/')
                require(slash > 0) { "no path in $url" }
                val authority = rest.substring(0, slash)
                val path = rest.substring(slash)
                val user = authority.substringBefore('@', DEFAULT_USER)
                val hostPort = authority.substringAfter('@', authority)
                return GitRemote(
                    user = user,
                    host = hostPort.substringBefore(':'),
                    port = hostPort.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT,
                    path = path,
                )
            }
            // scp-style: user@host:path. Rejects everything else rather than
            // guessing — a half-understood URL is how a backup silently targets
            // the wrong place.
            require("://" !in url) { "not an ssh remote: $url" }
            val colon = url.indexOf(':')
            require(colon > 0) { "not an ssh remote: $url" }
            val authority = url.substring(0, colon)
            return GitRemote(
                user = authority.substringBefore('@', DEFAULT_USER),
                host = authority.substringAfter('@', authority),
                port = DEFAULT_PORT,
                path = url.substring(colon + 1),
            )
        }
    }
}

/**
 * One SSH session running `git-upload-pack` on the remote.
 *
 * Unlike JGit's transport this exposes **stderr** alongside stdout. That single
 * difference removes the diagnostic probe the JGit engine needed: a git host
 * explains a refusal on stderr, and JGit discarded it, so the reason had to be
 * harvested over a second connection while a watchdog raced a read that would
 * otherwise never return. Here it is just another stream on the same channel.
 */
class UploadPackChannel(
    private val remote: GitRemote,
    private val keyPair: KeyPair,
    private val pinnedFingerprint: String?,
    private val capture: Boolean,
    private val timeout: Duration,
) : Closeable {

    private var client: SshClient? = null
    private var session: ClientSession? = null
    private var channel: ChannelExec? = null

    private val observed = AtomicReference<String?>(null)
    private val refusal = AtomicReference<SyncError?>(null)

    /** The host key the server presented, once the handshake has run. */
    val observedHostKey: String? get() = observed.get()

    /**
     * Why the host key check refused, when it did.
     *
     * SSHD closes the session on a `false` return and the cause never propagates,
     * so a rejection surfaces to the caller as an unexplained end of stream. The
     * reason is recorded here instead and preferred over whatever the transport
     * ends up reporting — the same mechanism the JGit engine used.
     */
    val rejection: SyncError? get() = refusal.get()

    lateinit var input: InputStream
        private set

    lateinit var output: OutputStream
        private set

    fun open() {
        val ssh = SshClient.setUpDefaultClient().apply {
            serverKeyVerifier = org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, _, key ->
                val algorithm = key?.algorithm ?: "unknown"
                val presented = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)
                if (presented == null) {
                    refusal.set(
                        SyncError(
                            SyncErrorCode.UNKNOWN,
                            "the server presented no readable host key (algorithm $algorithm)",
                        )
                    )
                    return@ServerKeyVerifier false
                }
                observed.set(presented)
                when (val decision = HostKeyVerifier.verify(pinnedFingerprint, presented)) {
                    is HostKeyDecision.Trusted -> true

                    is HostKeyDecision.FirstUse -> capture.also {
                        if (!it) refusal.set(
                            SyncError(
                                SyncErrorCode.HOST_KEY_MISMATCH,
                                "no host key is pinned for this repository; " +
                                    "the server offered $presented ($algorithm)",
                            )
                        )
                    }

                    is HostKeyDecision.Mismatch -> {
                        refusal.set(
                            SyncError(
                                SyncErrorCode.HOST_KEY_MISMATCH,
                                "expected ${decision.stored}, got ${decision.presented} " +
                                    "(algorithm $algorithm)",
                            )
                        )
                        false
                    }
                }
            }
            // A silently dropped connection must never park a read forever —
            // the recurring failure mode this project has already been burned
            // by twice. Both timers re-arm on traffic, so a slow-but-alive
            // transfer is unaffected; only true silence trips them.
            CoreModuleProperties.IDLE_TIMEOUT.set(this, timeout)
            CoreModuleProperties.NIO2_READ_TIMEOUT.set(this, timeout)
            start()
        }
        client = ssh

        val opened = ssh.connect(remote.user, remote.host, remote.port)
            .verify(timeout)
            .clientSession
        session = opened
        opened.addPublicKeyIdentity(keyPair)
        opened.auth().verify(timeout)

        // Single-quoted, as git itself does: the remote runs this through a shell.
        val exec = opened.createExecChannel("git-upload-pack '${remote.path}'")
        // Protocol v2 is opt-in, and the switch is out-of-band: upload-pack
        // speaks v0 unless GIT_PROTOCOL=version=2 reaches its environment.
        // git sends it as an SSH channel env var (SendEnv), and every major
        // host accepts it; without this line the engine can never see a v2
        // advertisement from any real server.
        exec.setEnv("GIT_PROTOCOL", "version=2")
        channel = exec
        // Leaving out/err unset is what makes the inverted streams available.
        exec.open().verify(timeout)

        input = exec.invertedOut
        output = exec.invertedIn
    }

    /** Whatever the server wrote to stderr, without blocking on more. */
    fun stderrText(): String = runCatching {
        val err = channel?.invertedErr ?: return ""
        val available = err.available()
        if (available <= 0) return ""
        // Not readNBytes: Android only gained it at API 33, and minSdk is 26.
        val buffer = ByteArray(minOf(available, MAX_SERVER_MESSAGE))
        val read = err.read(buffer)
        if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8).trim()
    }.getOrDefault("")

    override fun close() {
        runCatching { channel?.close(false) }
        runCatching { session?.close(false) }
        runCatching { client?.stop() }
    }

    private companion object {
        const val MAX_SERVER_MESSAGE = 2_000
    }
}
