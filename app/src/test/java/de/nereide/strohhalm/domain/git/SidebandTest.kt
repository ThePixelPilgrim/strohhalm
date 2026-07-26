package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SidebandTest {

    /** Builds a sideband stream: each entry is band number to payload. */
    private fun wire(vararg parts: Pair<Int, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        parts.forEach { (band, payload) ->
            PktLine.writeBytes(out, byteArrayOf(band.toByte()) + payload.toByteArray())
        }
        PktLine.writeFlush(out)
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `band 1 is the pack data and flush ends it`() {
        val stream = SidebandInputStream(wire(1 to "PACK", 1 to "DATA")) {}
        assertArrayEquals("PACKDATA".toByteArray(), stream.readBytes())
    }

    @Test
    fun `band 2 is progress and never reaches the pack data`() {
        val seen = mutableListOf<String>()
        val stream = SidebandInputStream(wire(2 to "Counting objects: 5", 1 to "PACK")) {
            seen += it
        }
        assertArrayEquals("PACK".toByteArray(), stream.readBytes())
        assertEquals(listOf("Counting objects: 5"), seen)
    }

    @Test
    fun `band 3 raises the server's own message`() {
        val stream = SidebandInputStream(wire(3 to "repository not found")) {}
        val thrown = assertThrows(SidebandException::class.java) { stream.readBytes() }
        assertEquals("repository not found", thrown.serverMessage)
    }
}
