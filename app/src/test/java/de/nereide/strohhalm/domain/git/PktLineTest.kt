package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class PktLineTest {

    private fun read(wire: String): Pkt =
        PktLine.read(ByteArrayInputStream(wire.toByteArray(Charsets.UTF_8)))

    @Test
    fun `the length prefix counts itself`() {
        // "0006" = 6 bytes total, so 2 bytes of payload.
        assertEquals("hi", (read("0006hi") as Pkt.Data).text())
    }

    @Test
    fun `special packets are distinguished`() {
        assertTrue(read("0000") is Pkt.Flush)
        assertTrue(read("0001") is Pkt.Delim)
        assertTrue(read("0002") is Pkt.ResponseEnd)
    }

    @Test
    fun `an empty payload is data, not a flush`() {
        assertEquals("", (read("0004") as Pkt.Data).text())
    }

    @Test
    fun `a truncated packet is an error, not a silent short read`() {
        assertThrows(EOFException::class.java) { read("0010short") }
    }

    @Test
    fun `writing prefixes the length including the prefix`() {
        val out = ByteArrayOutputStream()
        PktLine.writeString(out, "want abc\n")
        assertEquals("000dwant abc\n", out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `flush and delim are written verbatim`() {
        val out = ByteArrayOutputStream()
        PktLine.writeFlush(out)
        PktLine.writeDelim(out)
        assertEquals("00000001", out.toString(Charsets.UTF_8.name()))
    }
}
