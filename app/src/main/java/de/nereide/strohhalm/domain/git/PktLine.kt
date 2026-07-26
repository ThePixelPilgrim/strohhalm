package de.nereide.strohhalm.domain.git

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One packet from a pkt-line stream. */
sealed interface Pkt {
    /** A payload packet. Empty payloads are legal and are *not* a flush. */
    class Data(val bytes: ByteArray) : Pkt {
        fun text(): String = String(bytes, Charsets.UTF_8)
    }

    /** `0000` — end of a section or of the whole response. */
    data object Flush : Pkt

    /** `0001` — separates command capabilities from arguments in protocol v2. */
    data object Delim : Pkt

    /** `0002` — end of response. */
    data object ResponseEnd : Pkt
}

/**
 * Git's wire framing: four hex digits giving the total packet length, *including
 * the four digits themselves*, followed by that many bytes minus four.
 *
 * The off-by-four is the classic mistake here; `0004` is a legal empty packet
 * and `0000` is a flush, so the two cannot be conflated.
 */
object PktLine {

    /** Git's maximum, including the length prefix. */
    const val MAX_PACKET = 65520

    fun read(input: InputStream): Pkt {
        val length = Integer.parseInt(String(readFully(input, 4), Charsets.US_ASCII), 16)
        return when (length) {
            0 -> Pkt.Flush
            1 -> Pkt.Delim
            2 -> Pkt.ResponseEnd
            3 -> throw IOException("invalid pkt-line length 3")
            else -> {
                if (length > MAX_PACKET) throw IOException("pkt-line too long: $length")
                Pkt.Data(readFully(input, length - 4))
            }
        }
    }

    fun writeString(out: OutputStream, text: String) =
        writeBytes(out, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(out: OutputStream, payload: ByteArray) {
        val length = payload.size + 4
        require(length <= MAX_PACKET) { "pkt-line too long: $length" }
        out.write(String.format("%04x", length).toByteArray(Charsets.US_ASCII))
        out.write(payload)
    }

    fun writeFlush(out: OutputStream) = out.write("0000".toByteArray(Charsets.US_ASCII))

    fun writeDelim(out: OutputStream) = out.write("0001".toByteArray(Charsets.US_ASCII))

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw EOFException("stream ended after $read of $count bytes")
            read += n
        }
        return buffer
    }
}
