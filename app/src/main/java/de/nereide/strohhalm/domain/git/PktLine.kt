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

/**
 * The server refused, and said why on band 3. Carries the message verbatim so
 * the UI can show a git host's own words rather than a generic failure.
 */
class SidebandException(val serverMessage: String) :
    IOException("the server said: $serverMessage")

/**
 * Presents band 1 of a sideband-encoded section as a plain [InputStream].
 *
 * Band 2 is progress text and is handed to [onProgress]; band 3 is fatal and
 * becomes a [SidebandException]. The stream ends at the section's flush packet,
 * which is why the pack data can be handed straight to the indexer without the
 * indexer knowing anything about pkt-line.
 */
class SidebandInputStream(
    private val input: InputStream,
    private val onProgress: (String) -> Unit,
) : InputStream() {

    private var buffer: ByteArray = ByteArray(0)
    private var position = 0
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (!fill()) return -1
        val n = minOf(length, buffer.size - position)
        System.arraycopy(buffer, position, destination, offset, n)
        position += n
        return n
    }

    /** True when [buffer] holds unread band-1 bytes. */
    private fun fill(): Boolean {
        while (position >= buffer.size) {
            if (finished) return false
            when (val pkt = PktLine.read(input)) {
                is Pkt.Flush, is Pkt.ResponseEnd -> {
                    finished = true
                    return false
                }

                is Pkt.Delim -> Unit // section separator; nothing to emit

                is Pkt.Data -> {
                    if (pkt.bytes.isEmpty()) continue
                    val payload = pkt.bytes.copyOfRange(1, pkt.bytes.size)
                    when (pkt.bytes[0].toInt()) {
                        BAND_DATA -> {
                            buffer = payload
                            position = 0
                        }

                        // Split on the carriage returns git uses to update a
                        // line in place: one payload routinely carries several
                        // updates glued together, and each is a whole label.
                        BAND_PROGRESS ->
                            String(payload, Charsets.UTF_8).split('\r', '\n')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .forEach(onProgress)

                        BAND_ERROR ->
                            throw SidebandException(String(payload, Charsets.UTF_8).trim())

                        else -> throw IOException("unknown sideband ${pkt.bytes[0].toInt()}")
                    }
                }
            }
        }
        return true
    }

    private companion object {
        const val BAND_DATA = 1
        const val BAND_PROGRESS = 2
        const val BAND_ERROR = 3
    }
}
