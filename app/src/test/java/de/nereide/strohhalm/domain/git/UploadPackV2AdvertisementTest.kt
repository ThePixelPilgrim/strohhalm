package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class UploadPackV2AdvertisementTest {

    private fun advertisement(vararg lines: String): UploadPackV2 {
        val out = ByteArrayOutputStream()
        lines.forEach { PktLine.writeString(out, "$it\n") }
        PktLine.writeFlush(out)
        return UploadPackV2(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
    }

    @Test
    fun `a sha256 server selects the sha256 hash`() {
        val caps = advertisement(
            "version 2",
            "agent=git/2.55.0",
            "ls-refs=unborn",
            "fetch=shallow wait-for-done",
            "object-format=sha256",
        ).readAdvertisement()

        assertEquals(ObjectHash.SHA256, caps.objectHash)
        assertTrue(caps.supports("fetch"))
        assertTrue(caps.supports("ls-refs"))
    }

    @Test
    fun `no object-format means sha1, which is what older servers omit`() {
        val caps = advertisement("version 2", "agent=git/2.30.0", "fetch", "ls-refs")
            .readAdvertisement()
        assertEquals(ObjectHash.SHA1, caps.objectHash)
    }

    @Test
    fun `protocol v0 is refused with a message naming the version`() {
        val thrown = assertThrows(IOException::class.java) {
            advertisement("version 1", "fetch").readAdvertisement()
        }
        assertTrue(thrown.message!!.contains("version 1"))
    }

    @Test
    fun `a server without fetch is refused`() {
        assertThrows(IOException::class.java) {
            advertisement("version 2", "ls-refs").readAdvertisement()
        }
    }

    /** A refusing server may answer with a v0-style ERR packet instead of an advertisement. */
    @Test
    fun `an ERR packet surfaces the server's own words`() {
        val thrown = assertThrows(IOException::class.java) {
            advertisement("ERR access denied").readAdvertisement()
        }
        assertTrue(thrown.message!!.contains("access denied"))
    }
}
