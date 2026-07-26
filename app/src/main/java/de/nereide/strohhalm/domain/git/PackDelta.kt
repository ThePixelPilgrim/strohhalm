package de.nereide.strohhalm.domain.git

import java.io.IOException

/**
 * Git's delta encoding: a base size, a result size, then a stream of copy and
 * insert instructions.
 *
 * A copy instruction has its high bit set; the remaining bits say which of four
 * offset bytes and three size bytes follow, so an unchanged field costs nothing.
 * A copy size of zero means 0x10000 — a quirk of the format, not a bug here.
 *
 * This is the one hot loop in the engine that is not already native, so it
 * copies into a pre-sized array and never grows a buffer.
 */
object PackDelta {

    fun apply(base: ByteArray, delta: ByteArray): ByteArray {
        var position = 0

        fun varint(): Int {
            var value = 0
            var shift = 0
            while (true) {
                if (position >= delta.size) throw IOException("truncated delta header")
                val b = delta[position++].toInt() and 0xff
                value = value or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        val baseSize = varint()
        if (baseSize != base.size) {
            throw IOException("delta expects a base of $baseSize bytes, got ${base.size}")
        }
        val resultSize = varint()
        val result = ByteArray(resultSize)
        var written = 0

        while (position < delta.size) {
            val command = delta[position++].toInt() and 0xff
            if (command and 0x80 != 0) {
                var offset = 0
                var size = 0
                if (command and 0x01 != 0) offset = offset or (delta[position++].toInt() and 0xff)
                if (command and 0x02 != 0) offset = offset or ((delta[position++].toInt() and 0xff) shl 8)
                if (command and 0x04 != 0) offset = offset or ((delta[position++].toInt() and 0xff) shl 16)
                if (command and 0x08 != 0) offset = offset or ((delta[position++].toInt() and 0xff) shl 24)
                if (command and 0x10 != 0) size = size or (delta[position++].toInt() and 0xff)
                if (command and 0x20 != 0) size = size or ((delta[position++].toInt() and 0xff) shl 8)
                if (command and 0x40 != 0) size = size or ((delta[position++].toInt() and 0xff) shl 16)
                if (size == 0) size = 0x10000

                if (offset + size > base.size || written + size > resultSize) {
                    throw IOException("delta copy runs past the end of its base")
                }
                System.arraycopy(base, offset, result, written, size)
                written += size
            } else {
                if (command == 0) throw IOException("reserved delta instruction 0")
                if (written + command > resultSize) {
                    throw IOException("delta insert runs past the result size")
                }
                System.arraycopy(delta, position, result, written, command)
                position += command
                written += command
            }
        }

        if (written != resultSize) {
            throw IOException("delta produced $written bytes, expected $resultSize")
        }
        return result
    }
}
