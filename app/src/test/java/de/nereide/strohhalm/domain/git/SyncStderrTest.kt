package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.MirrorOutcome
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * The device failure this reproduces: a repository URL pointing at a path the
 * forge does not know. Forgejo explains itself on stderr ("Cannot find
 * repository: …") and closes, and the sync saw only the resulting
 * `EOFException: stream ended after 1 of 4 bytes` — the one message the user
 * could have acted on was the one the app discarded. The probe path already
 * harvested stderr; the sync path must too.
 */
class SyncStderrTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var clientKey: KeyPair

    private val forgejoMessage = "Forgejo: Cannot find repository: PixelPilgrim/yamiro"

    @Before
    fun startServer() {
        // RSA, not Ed25519: sshd 2.14 supports Ed25519 only through the
        // net.i2p EdDSA provider, not the JDK's own EdEC keys.
        clientKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }.generateKeyPair()
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                File(temp.newFolder("hostkey"), "host.ser").toPath()
            )
            publickeyAuthenticator = AcceptAllPublickeyAuthenticator.INSTANCE
            commandFactory = StderrOnlyCommandFactory(forgejoMessage)
            start()
        }
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(true)
    }

    @Test
    fun `a refused repository surfaces the server's own words, not a bare EOF`() = runBlocking {
        val url = "ssh://git@127.0.0.1:${server.port}/PixelPilgrim/yamiro.git"
        val destination = File(temp.newFolder("mirrors"), "yamiro.git")

        val mirror = ProtocolMirror(keyPairProvider = { clientKey })
        // A sync only runs against a verified server, so pin the key the way
        // the app would have: from a probe.
        val pinned = (mirror.probeHostKey(url).exceptionOrNull()
            as? de.nereide.strohhalm.domain.ProbeRejectedException)?.fingerprint
            ?: mirror.probeHostKey(url).getOrThrow()
        val outcome = mirror.sync(url, destination, pinnedFingerprint = pinned, progress = null)

        val failure = outcome as MirrorOutcome.Failure
        assertEquals(SyncErrorCode.REMOTE_ERROR, failure.error.code)
        assertEquals("the server said: $forgejoMessage", failure.error.detail)
    }

    @Test
    fun `a server message about a denied key still classifies as an auth failure`() {
        val error = SyncErrors.fromServerMessage(
            "ERROR: Permission denied (publickey).",
            EOFException("stream ended after 1 of 4 bytes"),
        )
        assertEquals(SyncErrorCode.AUTH_FAILED, error.code)
        assertTrue(
            "expected the server's words in the detail, got: ${error.detail}",
            error.detail!!.contains("Permission denied"),
        )
    }

    @Test
    fun `an unclassifiable server message is a remote error carrying the words`() {
        val error = SyncErrors.fromServerMessage(
            forgejoMessage,
            EOFException("stream ended after 1 of 4 bytes"),
        )
        assertEquals(SyncErrorCode.REMOTE_ERROR, error.code)
        assertEquals("the server said: $forgejoMessage", error.detail)
        assertTrue(
            "expected the original exception chain in the diagnostic, got: ${error.diagnostic}",
            error.diagnostic!!.contains("EOFException"),
        )
    }

    /**
     * What a forge's serv command does for a bad path: a line on stderr, a
     * nonzero exit, and not a single valid pkt-line on stdout.
     */
    private class StderrOnlyCommandFactory(private val message: String) : CommandFactory {
        override fun createCommand(channel: ChannelSession, command: String): Command =
            object : Command {
                private lateinit var out: OutputStream
                private lateinit var err: OutputStream
                private var callback: ExitCallback? = null

                override fun setInputStream(value: InputStream) {}
                override fun setOutputStream(value: OutputStream) { out = value }
                override fun setErrorStream(value: OutputStream) { err = value }
                override fun setExitCallback(value: ExitCallback) { callback = value }

                override fun start(session: ChannelSession, env: Environment) {
                    err.write((message + "\n").toByteArray(Charsets.UTF_8))
                    err.flush()
                    callback?.onExit(1)
                }

                override fun destroy(session: ChannelSession) {}
            }
    }
}
