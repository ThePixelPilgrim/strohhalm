package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.MirrorOutcome
import de.nereide.strohhalm.domain.SyncErrorCode
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * A full mirror over a real SSH connection to a local `git-upload-pack`.
 *
 * The remote repository is created with the system `git` in SHA-256 mode, and
 * the produced mirror is validated by `git fsck` and `git verify-pack` — real
 * git judging the one artifact this engine authors. Skipped where git is absent
 * or too old to support `--object-format=sha256`.
 */
class MirrorEndToEndTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var clientKey: KeyPair

    private fun git(vararg args: String, cwd: File): String {
        val process = ProcessBuilder("git", *args)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("git ${args.joinToString(" ")} failed:\n$output", 0, process.waitFor())
        return output
    }

    private fun gitSupportsSha256(): Boolean = runCatching {
        val probe = temp.newFolder("probe")
        git("init", "--object-format=sha256", "--bare", "probe.git", cwd = probe)
        true
    }.getOrDefault(false)

    @Before
    fun startServer() {
        assumeTrue("needs git with SHA-256 support", gitSupportsSha256())

        // RSA, not Ed25519: sshd 2.14 supports Ed25519 only through the
        // net.i2p EdDSA provider (the app's real keys), not the JDK's own
        // EdEC keys — those arrive in sshd 2.15. The existing
        // JGitMirrorDiagnosticHangTest harness uses RSA for the same reason.
        clientKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }.generateKeyPair()

        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                File(temp.newFolder("hostkey"), "host.ser").toPath()
            )
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator.INSTANCE
            // git-upload-pack is executed for real; nothing about the transfer is faked.
            setCommandFactory(UploadPackCommandFactory())
            start()
        }
    }

    /**
     * Runs the real `git upload-pack` for whatever path the client asked for.
     *
     * Written out rather than using a stock shell factory because the command
     * arrives as `git-upload-pack '<path>'` — single-quoted, exactly as git
     * sends it — and a factory that splits on spaces would hand `'<path>'` to
     * git with the quotes still attached.
     */
    private class UploadPackCommandFactory : CommandFactory {
        override fun createCommand(channel: ChannelSession, command: String): Command =
            object : Command {
                private lateinit var out: OutputStream
                private lateinit var err: OutputStream
                private lateinit var input: InputStream
                private var callback: ExitCallback? = null
                private var process: Process? = null

                override fun setInputStream(value: InputStream) { input = value }
                override fun setOutputStream(value: OutputStream) { out = value }
                override fun setErrorStream(value: OutputStream) { err = value }
                override fun setExitCallback(value: ExitCallback) { callback = value }

                override fun start(session: ChannelSession, env: Environment) {
                    val path = command.substringAfter(' ').trim().trim('\'')
                    val started = ProcessBuilder("git", "upload-pack", path)
                        .apply {
                            // Forward the channel's env exactly as a real sshd
                            // with `AcceptEnv GIT_PROTOCOL` would. Without it,
                            // upload-pack speaks v0 and the engine (correctly)
                            // refuses — proving here that the client actually
                            // sends the variable.
                            env.env["GIT_PROTOCOL"]?.let {
                                environment()["GIT_PROTOCOL"] = it
                            }
                        }
                        .start()
                    process = started
                    pump(input, started.outputStream, closeTarget = true)
                    pump(started.inputStream, out, closeTarget = false)
                    pump(started.errorStream, err, closeTarget = false)
                    Thread {
                        val code = started.waitFor()
                        runCatching { out.flush() }
                        callback?.onExit(code)
                    }.apply { isDaemon = true }.start()
                }

                override fun destroy(session: ChannelSession) {
                    process?.destroy()
                }

                private fun pump(from: InputStream, to: OutputStream, closeTarget: Boolean) {
                    Thread {
                        runCatching {
                            // Flushed per chunk rather than `copyTo`: sshd's
                            // channel stream and the process's stdin both
                            // buffer, and copyTo only flushes at EOF. The
                            // advertisement would then sit in a buffer while
                            // the client waited for it and the client's request
                            // sat in another — a stall broken only by the idle
                            // timeout. A real sshd flushes as it writes.
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = from.read(buffer)
                                if (n < 0) break
                                to.write(buffer, 0, n)
                                to.flush()
                            }
                            to.flush()
                            if (closeTarget) to.close()
                        }
                    }.apply { isDaemon = true }.start()
                }
            }
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(true)
    }

    /** Creates a SHA-256 remote with a few commits and returns its bare path. */
    private fun remoteRepository(): File {
        val work = temp.newFolder("remote-work")
        git("init", "--object-format=sha256", cwd = work)
        git("config", "user.email", "test@example.invalid", cwd = work)
        git("config", "user.name", "Test", cwd = work)
        repeat(5) { i ->
            File(work, "file$i.txt").writeText("line $i\n".repeat(i + 1))
            git("add", ".", cwd = work)
            git("commit", "-m", "commit $i", cwd = work)
        }
        git("tag", "v1", cwd = work)

        val bare = temp.newFolder("remote.git")
        // No --object-format here: git clone does not accept the flag (only
        // init does), and a local bare clone inherits sha256 from its source —
        // verified against git 2.53. The assertion below guards the inheritance.
        git("clone", "--bare", work.absolutePath, bare.absolutePath, cwd = temp.root)
        assertEquals("sha256", git("rev-parse", "--show-object-format", cwd = bare).trim())
        return bare
    }

    @Test
    // runBlocking<Unit>, not a bare runBlocking: the body's last expression is
    // the `git verify-pack` output, so an inferred String return makes JUnit
    // reject the whole class with "should be void".
    fun `a sha256 repository mirrors and passes git fsck`() = runBlocking<Unit> {
        val remote = remoteRepository()
        val destination = File(temp.root, "mirror.git")
        val url = "ssh://test@127.0.0.1:${server.port}${remote.absolutePath}"

        val outcome = ProtocolMirror(keyPairProvider = { clientKey })
            .sync(url, destination, pinnedFingerprint = null)

        // No pin, so the first attempt must be refused rather than trusted —
        // and refused *for the right reason*. SSHD reports a rejected host key
        // as a bare transport failure, so this assertion is what proves the
        // recorded refusal is preferred over the library's own exception.
        assertTrue("unpinned host must be refused", outcome is MirrorOutcome.Failure)
        assertEquals(
            SyncErrorCode.HOST_KEY_MISMATCH,
            (outcome as MirrorOutcome.Failure).error.code,
        )

        // Pin what the server actually presented, then mirror for real.
        val fingerprint = ProtocolMirror(keyPairProvider = { clientKey })
            .probeHostKey(url)
            .getOrThrow()
        val second = ProtocolMirror(keyPairProvider = { clientKey })
            .sync(url, destination, pinnedFingerprint = fingerprint)

        assertTrue("mirror succeeded: $second", second is MirrorOutcome.Success)

        val fsck = git("fsck", "--strict", cwd = destination)
        assertTrue("git fsck reported problems:\n$fsck", fsck.isBlank())

        val refs = git("show-ref", cwd = destination)
        assertTrue("branch mirrored", refs.contains("refs/heads/"))
        assertTrue("tag mirrored", refs.contains("refs/tags/v1"))

        val idx = File(destination, "objects/pack").listFiles { f -> f.name.endsWith(".idx") }!!.single()
        git("verify-pack", "-v", idx.absolutePath, cwd = destination)
    }

    @Test
    fun `a second sync is incremental and still valid`() = runBlocking {
        val remote = remoteRepository()
        val destination = File(temp.root, "mirror2.git")
        val url = "ssh://test@127.0.0.1:${server.port}${remote.absolutePath}"
        val fingerprint = ProtocolMirror(keyPairProvider = { clientKey }).probeHostKey(url).getOrThrow()
        val mirror = ProtocolMirror(keyPairProvider = { clientKey })

        assertTrue(mirror.sync(url, destination, fingerprint) is MirrorOutcome.Success)
        assertTrue(mirror.sync(url, destination, fingerprint) is MirrorOutcome.Success)

        assertTrue(git("fsck", "--strict", cwd = destination).isBlank())
    }
}
