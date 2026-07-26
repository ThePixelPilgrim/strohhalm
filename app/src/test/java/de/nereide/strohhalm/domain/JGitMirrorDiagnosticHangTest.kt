package de.nereide.strohhalm.domain

import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.keyprovider.MappedKeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Reproduces the on-device symptom: a sync that never completes, with the
 * progress monitor never firing, after the transport dies mid `ls-refs`.
 *
 * The server here plays the exact sequence the phone saw against Codeberg:
 *
 *  1. On the *transport* connection it advertises protocol v2, accepts the
 *     `ls-refs` request, then truncates the response mid pkt-line — which JGit
 *     surfaces as `EOFException: Short read of block.` inside `lsRefs`, the
 *     precise failure recorded on the device.
 *  2. On the follow-up *diagnostic* connection (`readServerMessage`), it
 *     behaves like a healthy `git-upload-pack` on a real repo: writes the ref
 *     advertisement to stdout, writes **nothing** to stderr, and waits for a
 *     request that never comes. A real server holds this state indefinitely —
 *     sshd keepalives keep the TCP session alive, so no idle timeout fires.
 *
 * `JGitMirror.readServerMessage` reads that stderr stream **to EOF** with no
 * deadline, so the whole sync hangs forever inside its own error handler and
 * the original failure is never reported.
 */
class JGitMirrorDiagnosticHangTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var sshd: SshServer
    private lateinit var mirror: JGitMirror
    private lateinit var pinned: String

    /** Commands the server was asked to run, in order. */
    private val execs = CopyOnWriteArrayList<String>()
    private val attempts = AtomicInteger()
    private val transportAttemptDone = CountDownLatch(1)
    private val diagnosticStarted = CountDownLatch(1)
    private val releaseDiagnostic = CountDownLatch(1)

    /** When true the diagnostic exec explains itself on stderr and exits. */
    @Volatile
    private var diagnosticSpeaksAndExits = false

    /**
     * [SshdEnvironment] is a process-wide latch and [SshdEnvironmentTest]
     * asserts on the *first* install of the process, so this test saves the
     * current state and restores it afterwards instead of claiming the slot.
     */
    private var previousHome: Any? = null
    private val homeField = SshdEnvironment::class.java.getDeclaredField("home")
        .apply { isAccessible = true }

    @Before
    fun setUp() {
        AndroidSystemReader.install()
        previousHome = homeField.get(SshdEnvironment)
        homeField.set(SshdEnvironment, null)
        SshdEnvironment.install(tmp.newFolder("sshd-home"))

        val hostKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }.generateKeyPair()
        val clientKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }.generateKeyPair()
        pinned = KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey.public)

        sshd = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = MappedKeyPairProvider(hostKey)
            publickeyAuthenticator = AcceptAllPublickeyAuthenticator.INSTANCE
            commandFactory = CommandFactory { _, command -> FakeUploadPack(command) }
            start()
        }

        mirror = JGitMirror(keyPairProvider = { clientKey })
    }

    @After
    fun tearDown() {
        releaseDiagnostic.countDown()
        runCatching { sshd.stop(true) }
        homeField.set(SshdEnvironment, previousHome)
    }

    private fun remoteUrl() = "ssh://git@127.0.0.1:${sshd.port}/repo.git"

    @Test
    fun `sync surfaces a transport failure instead of hanging in the diagnostic session`() {
        var outcome: MirrorOutcome? = null
        val syncDone = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            outcome = runBlocking {
                mirror.sync(
                    remoteUrl = remoteUrl(),
                    destination = tmp.newFolder("mirror.git"),
                    pinnedFingerprint = pinned,
                    progress = { _, _, _ -> },
                )
            }
            syncDone.countDown()
        }

        // The transport connection must have failed exactly the way the phone's
        // did, and the app must have opened its follow-up diagnostic session.
        assertTrue(
            "the transport attempt never reached the server",
            transportAttemptDone.await(30, TimeUnit.SECONDS),
        )
        assertTrue(
            "the diagnostic git-upload-pack exec never started",
            diagnosticStarted.await(30, TimeUnit.SECONDS),
        )

        // With the transport dead, sync() must report the failure within the
        // diagnostic deadline (15s) plus slack. A healthy-but-silent
        // upload-pack on the diagnostic session must not park the sync forever.
        val finished = syncDone.await(30, TimeUnit.SECONDS)
        if (!finished) {
            // Free the worker before failing so the JVM can exit cleanly.
            releaseDiagnostic.countDown()
            sshd.stop(true)
            worker.join(10_000)
        }
        assertTrue(
            "sync() is hung inside readServerMessage: the transport failed " +
                "(execs=$execs) but no outcome was produced within 15s because " +
                "the diagnostic session's stderr never reaches EOF",
            finished,
        )
        assertTrue("expected a failure outcome, got $outcome", outcome is MirrorOutcome.Failure)
    }

    @Test
    fun `a server that explains itself on stderr is reported, proving the fixture`() {
        diagnosticSpeaksAndExits = true

        val outcome = runBlocking {
            mirror.sync(
                remoteUrl = remoteUrl(),
                destination = tmp.newFolder("mirror.git"),
                pinnedFingerprint = pinned,
                progress = { _, _, _ -> },
            )
        }

        assertTrue("expected failure, got $outcome", outcome is MirrorOutcome.Failure)
        val error = (outcome as MirrorOutcome.Failure).error
        assertEquals(SyncErrorCode.REMOTE_ERROR, error.code)
        assertTrue(
            "server stderr should be quoted in: ${error.detail}",
            error.detail.orEmpty().contains("fatal: mock refusal"),
        )
    }

    // ------------------------------------------------------------------
    // A minimal git-upload-pack impostor speaking just enough protocol v2.
    // ------------------------------------------------------------------

    private inner class FakeUploadPack(private val command: String) : Command {
        private lateinit var stdin: InputStream
        private lateinit var stdout: OutputStream
        private lateinit var stderr: OutputStream
        private lateinit var exit: ExitCallback
        private var runner: Thread? = null

        override fun setInputStream(inputStream: InputStream) { stdin = inputStream }
        override fun setOutputStream(outputStream: OutputStream) { stdout = outputStream }
        override fun setErrorStream(errorStream: OutputStream) { stderr = errorStream }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            execs.add(command)
            val attempt = attempts.incrementAndGet()
            runner = thread(isDaemon = true, name = "fake-upload-pack-$attempt") {
                runCatching {
                    if (attempt == 1) transportAttempt() else diagnosticAttempt()
                }
            }
        }

        /** Advertises v2, reads ls-refs, truncates the response mid pkt-line. */
        private fun transportAttempt() {
            try {
                stdout.pkt("version 2")
                stdout.pkt("agent=fake/1.0")
                stdout.pkt("ls-refs")
                stdout.pkt("fetch")
                stdout.flushPkt()
                stdout.flush()
                stdin.readUntilFlushPkt()
                // Claim a 46-byte payload, deliver 10 bytes, drop the connection:
                // JGit's IO.readFully -> EOFException("Short read of block.")
                stdout.write("0032truncated!".toByteArray(Charsets.US_ASCII))
                stdout.flush()
            } finally {
                transportAttemptDone.countDown()
                exit.onExit(0)
            }
        }

        /**
         * A healthy upload-pack on a valid repository: full advertisement on
         * stdout, silence on stderr, then waiting for a request forever.
         */
        private fun diagnosticAttempt() {
            if (diagnosticSpeaksAndExits) {
                stderr.write("fatal: mock refusal\n".toByteArray(Charsets.US_ASCII))
                stderr.flush()
                exit.onExit(128)
                return
            }
            stdout.pkt("version 2")
            stdout.pkt("ls-refs")
            stdout.pkt("fetch")
            stdout.flushPkt()
            stdout.flush()
            diagnosticStarted.countDown()
            releaseDiagnostic.await()
            exit.onExit(0)
        }

        override fun destroy(channel: ChannelSession) {
            runner?.interrupt()
        }
    }

    // pkt-line helpers -------------------------------------------------

    private fun OutputStream.pkt(payload: String) {
        val body = payload + "\n"
        write("%04x".format(body.length + 4).toByteArray(Charsets.US_ASCII))
        write(body.toByteArray(Charsets.US_ASCII))
    }

    private fun OutputStream.flushPkt() = write("0000".toByteArray(Charsets.US_ASCII))

    /** Consumes pkt-lines (including delim 0001) until a flush pkt. */
    private fun InputStream.readUntilFlushPkt() {
        while (true) {
            val len = String(readExactly(4), Charsets.US_ASCII).toInt(16)
            when {
                len == 0 -> return       // 0000 flush
                len <= 4 -> continue     // 0001 delim etc., no payload
                else -> readExactly(len - 4)
            }
        }
    }

    private fun InputStream.readExactly(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) throw EOFException("stream ended after $off of $n bytes")
            off += r
        }
        return buf
    }
}
