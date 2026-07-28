package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.ProbeRejectedException
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.RejectAllPublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * The missing-public-key scenario: the server completes the key exchange —
 * presenting its host key — and then refuses authentication. The probe must
 * hand that fingerprint out anyway, because pinning it is exactly what lets
 * the user add the repository now and install the key later.
 */
class ProbeAuthFailureTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var clientKey: KeyPair

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
            publickeyAuthenticator = RejectAllPublickeyAuthenticator.INSTANCE
            start()
        }
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(true)
    }

    @Test
    fun `a refused authentication still yields the observed host key`() = runBlocking {
        val url = "ssh://test@127.0.0.1:${server.port}/srv/repo.git"

        val result = ProtocolMirror(keyPairProvider = { clientKey }).probeHostKey(url)

        val failure = result.exceptionOrNull()
        assertTrue("expected ProbeRejectedException, got $failure",
            failure is ProbeRejectedException)
        failure as ProbeRejectedException
        assertTrue("fingerprint captured: ${failure.fingerprint}",
            failure.fingerprint.startsWith("SHA256:"))
        assertEquals(
            SyncErrorCode.AUTH_FAILED,
            SyncErrors.fromException(failure).code,
        )
    }
}
