package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefFingerprintTest {

    private val sha1Refs = mapOf(
        "refs/heads/main" to "a".repeat(40),
        "refs/tags/v1" to "b".repeat(40),
    )

    private val sha256Refs = mapOf(
        "refs/heads/main" to "a".repeat(64),
        "refs/tags/v1" to "b".repeat(64),
    )

    @Test
    fun `the fingerprint is 64 hex characters whatever the object format`() {
        assertEquals(64, RefFingerprint.of(sha1Refs).length)
        assertEquals(64, RefFingerprint.of(sha256Refs).length)
        assertTrue(RefFingerprint.of(sha256Refs).all { it in "0123456789abcdef" })
    }

    @Test
    fun `map order does not change the fingerprint`() {
        val reversed = linkedMapOf(
            "refs/tags/v1" to "b".repeat(64),
            "refs/heads/main" to "a".repeat(64),
        )
        assertEquals(RefFingerprint.of(sha256Refs), RefFingerprint.of(reversed))
    }

    /** The case the whole cache key exists for: a tag arrives, HEAD does not move. */
    @Test
    fun `adding a tag changes the fingerprint`() {
        val withTag = sha256Refs + ("refs/tags/v2" to "c".repeat(64))
        assertNotEquals(RefFingerprint.of(sha256Refs), RefFingerprint.of(withTag))
    }

    @Test
    fun `moving a ref changes the fingerprint`() {
        val moved = sha256Refs + ("refs/heads/main" to "d".repeat(64))
        assertNotEquals(RefFingerprint.of(sha256Refs), RefFingerprint.of(moved))
    }

    /** A name and an id must not be interchangeable; without a separator they would be. */
    @Test
    fun `a ref name and an id cannot be confused for one another`() {
        assertNotEquals(
            RefFingerprint.of(mapOf("refs/heads/ab" to "c".repeat(64))),
            RefFingerprint.of(mapOf("refs/heads/a" to "bc".padEnd(64, 'c'))),
        )
    }

    @Test
    fun `an empty ref list has a stable fingerprint`() {
        assertEquals(RefFingerprint.of(emptyMap()), RefFingerprint.of(emptyMap()))
    }
}
