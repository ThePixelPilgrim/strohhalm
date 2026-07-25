package de.nereide.strohhalm.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class OpenSshPublicKeyTest {

    private val raw = ByteArray(32) { it.toByte() }

    @Test
    fun `starts with the algorithm name and ends with the comment`() {
        val line = OpenSshPublicKey.encode(raw, "strohhalm@pixel")

        assertEquals(3, line.split(" ").size)
        assertEquals("ssh-ed25519", line.split(" ")[0])
        assertEquals("strohhalm@pixel", line.split(" ")[2])
    }

    @Test
    fun `body decodes to the ssh wire format of the key`() {
        val body = OpenSshPublicKey.encode(raw, "c").split(" ")[1]
        val blob = Base64.getDecoder().decode(body)

        // uint32 len | "ssh-ed25519" | uint32 32 | 32 raw bytes
        assertEquals(4 + 11 + 4 + 32, blob.size)
        assertEquals(11, readUint32(blob, 0))
        assertEquals("ssh-ed25519", String(blob, 4, 11))
        assertEquals(32, readUint32(blob, 15))
        assertArrayEquals(raw, blob.copyOfRange(19, 51))
    }

    @Test
    fun `rejects a key that is not 32 bytes`() {
        val e = runCatching { OpenSshPublicKey.encode(ByteArray(31), "c") }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, e!!::class.java)
    }

    private fun readUint32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)
}
