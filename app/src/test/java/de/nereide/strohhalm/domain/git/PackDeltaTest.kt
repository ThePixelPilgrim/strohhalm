package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class PackDeltaTest {

    /** Sizes are little-endian 7-bit varints, high bit meaning "more follows". */
    private fun varint(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = value
        while (true) {
            val b = remaining and 0x7f
            remaining = remaining ushr 7
            out.write(if (remaining == 0) b else b or 0x80)
            if (remaining == 0) break
        }
        return out.toByteArray()
    }

    @Test
    fun `an insert instruction copies literal bytes into the result`() {
        val base = "unused".toByteArray()
        val delta = ByteArrayOutputStream().apply {
            write(varint(base.size))
            write(varint(3))
            write(3)               // insert 3 literal bytes
            write("abc".toByteArray())
        }.toByteArray()

        assertArrayEquals("abc".toByteArray(), PackDelta.apply(base, delta))
    }

    @Test
    fun `a copy instruction takes a run from the base`() {
        val base = "HELLO WORLD".toByteArray()
        val delta = ByteArrayOutputStream().apply {
            write(varint(base.size))
            write(varint(5))
            write(0x90)            // copy, offset absent (0), size byte 1 present
            write(5)               // 5 bytes
        }.toByteArray()

        assertArrayEquals("HELLO".toByteArray(), PackDelta.apply(base, delta))
    }

    @Test
    fun `copy and insert combine`() {
        val base = "HELLO WORLD".toByteArray()
        val delta = ByteArrayOutputStream().apply {
            write(varint(base.size))
            write(varint(8))
            write(0x91); write(6); write(5)   // copy 5 bytes from offset 6 -> "WORLD"
            write(3); write("!!!".toByteArray())
        }.toByteArray()

        assertArrayEquals("WORLD!!!".toByteArray(), PackDelta.apply(base, delta))
    }

    @Test
    fun `a size mismatch is refused rather than returned`() {
        val base = "abc".toByteArray()
        val delta = ByteArrayOutputStream().apply {
            write(varint(99))      // wrong base size
            write(varint(1))
            write(1); write('x'.code)
        }.toByteArray()

        assertThrows(IOException::class.java) { PackDelta.apply(base, delta) }
    }
}
