package de.nereide.strohhalm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyVerifierTest {

    private val a = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val b = "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"

    @Test
    fun `no pinned fingerprint means first use`() {
        assertEquals(HostKeyDecision.FirstUse, HostKeyVerifier.verify(stored = null, presented = a))
    }

    @Test
    fun `a blank pinned fingerprint is also first use`() {
        assertEquals(HostKeyDecision.FirstUse, HostKeyVerifier.verify(stored = "  ", presented = a))
    }

    @Test
    fun `matching fingerprints are trusted`() {
        assertEquals(HostKeyDecision.Trusted, HostKeyVerifier.verify(stored = a, presented = a))
    }

    @Test
    fun `surrounding whitespace does not defeat a match`() {
        assertEquals(HostKeyDecision.Trusted, HostKeyVerifier.verify(stored = " $a ", presented = a))
    }

    @Test
    fun `a different fingerprint is a mismatch carrying both values`() {
        val decision = HostKeyVerifier.verify(stored = a, presented = b)

        assertTrue(decision is HostKeyDecision.Mismatch)
        assertEquals(a, (decision as HostKeyDecision.Mismatch).stored)
        assertEquals(b, decision.presented)
    }

    @Test
    fun `comparison is case sensitive because base64 is`() {
        val lower = a.lowercase()
        assertTrue(HostKeyVerifier.verify(stored = a, presented = lower) is HostKeyDecision.Mismatch)
    }
}
