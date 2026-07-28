package de.nereide.strohhalm.domain

import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SyncErrorsTest {

    @Test
    fun `a rejected key maps to AUTH_FAILED`() {
        val e = TransportException("git@host:repo.git: Auth fail")
        assertEquals(SyncErrorCode.AUTH_FAILED, SyncErrors.fromException(e).code)
    }

    @Test
    fun `permission denied also maps to AUTH_FAILED`() {
        val e = TransportException("ssh://host/repo: Permission denied (publickey)")
        assertEquals(SyncErrorCode.AUTH_FAILED, SyncErrors.fromException(e).code)
    }

    @Test
    fun `a host key mismatch keeps both fingerprints in the detail`() {
        val e = HostKeyMismatchException(stored = "SHA256:aaa", presented = "SHA256:bbb")
        val mapped = SyncErrors.fromException(e)

        assertEquals(SyncErrorCode.HOST_KEY_MISMATCH, mapped.code)
        assertEquals("expected SHA256:aaa, got SHA256:bbb", mapped.detail)
    }

    @Test
    fun `an unresolvable host maps to HOST_UNREACHABLE`() {
        assertEquals(
            SyncErrorCode.HOST_UNREACHABLE,
            SyncErrors.fromException(UnknownHostException("nope.invalid")).code
        )
    }

    @Test
    fun `a timeout maps to HOST_UNREACHABLE`() {
        assertEquals(
            SyncErrorCode.HOST_UNREACHABLE,
            SyncErrors.fromException(SocketTimeoutException("timed out")).code
        )
    }

    @Test
    fun `a missing remote repository maps to REMOTE_ERROR`() {
        val e = NoRemoteRepositoryException(URIish("ssh://host/gone.git"), "not found")
        assertEquals(SyncErrorCode.REMOTE_ERROR, SyncErrors.fromException(e).code)
    }

    @Test
    fun `a wrapped cause is unwrapped before matching`() {
        val e = RuntimeException("outer", TransportException("Auth fail"))
        assertEquals(SyncErrorCode.AUTH_FAILED, SyncErrors.fromException(e).code)
    }

    @Test
    fun `an unrecognised failure maps to UNKNOWN but keeps the message`() {
        val mapped = SyncErrors.fromException(IOException("disk went sideways"))
        assertEquals(SyncErrorCode.UNKNOWN, mapped.code)
        assertEquals("disk went sideways", mapped.detail)
    }

    @Test
    fun `the diagnostic names every class in the chain`() {
        val e = RuntimeException("outer", IllegalStateException("inner"))

        val diagnostic = SyncErrors.fromException(e).diagnostic!!

        assertTrue(diagnostic.contains("java.lang.RuntimeException"))
        assertTrue(diagnostic.contains("java.lang.IllegalStateException"))
    }

    @Test
    fun `the diagnostic keeps each message, not just the top one`() {
        // The failure that motivated this: a NoClassDefFoundError's message is
        // the ONLY place the un-loadable class name appears.
        val root = NoClassDefFoundError("Could not initialize class org.example.Boom")
        val e = RuntimeException("remote hung up unexpectedly", root)

        val diagnostic = SyncErrors.fromException(e).diagnostic!!

        assertTrue(diagnostic.contains("org.example.Boom"))
        assertTrue(diagnostic.contains("remote hung up unexpectedly"))
    }

    @Test
    fun `the diagnostic includes the root cause stack frames`() {
        val e = RuntimeException("outer", IllegalArgumentException("bad"))

        val diagnostic = SyncErrors.fromException(e).diagnostic!!

        assertTrue(diagnostic.contains("root cause stack:"))
        assertTrue(diagnostic.contains("   at "))
    }

    /**
     * A ProbeRejectedException with a blank server message is a transport
     * carrying a fingerprint, not a server verdict. Classification must fall
     * through to its cause — here an auth failure — instead of reporting a
     * REMOTE_ERROR with an empty message.
     */
    @Test
    fun `a blank probe message defers classification to the cause`() {
        val error = SyncErrors.fromException(
            ProbeRejectedException(
                fingerprint = "SHA256:abc",
                serverMessage = "",
                cause = Exception("Permission denied (publickey)"),
            )
        )
        assertEquals(SyncErrorCode.AUTH_FAILED, error.code)
    }

    @Test
    fun `a probe message from the server still wins over the cause`() {
        val error = SyncErrors.fromException(
            ProbeRejectedException(
                fingerprint = "SHA256:abc",
                serverMessage = "repository not found",
                cause = Exception("stream ended"),
            )
        )
        assertEquals(SyncErrorCode.REMOTE_ERROR, error.code)
    }
}
