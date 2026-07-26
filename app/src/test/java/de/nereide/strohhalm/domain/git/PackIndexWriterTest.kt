package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.File

class PackIndexWriterTest {

    @get:Rule val temp = TemporaryFolder()

    private val hash = ObjectHash.SHA256

    private fun id(firstByte: Int): ByteArray =
        ByteArray(hash.rawLength).also { it[0] = firstByte.toByte() }

    @Test
    fun `the header, fanout and trailers match the idx v2 layout`() {
        val objects = listOf(
            IndexedObject(id(0x00), 12L, 0x11111111),
            IndexedObject(id(0x40), 400L, 0x22222222),
            IndexedObject(id(0xff), 900L, 0x33333333),
        )
        val packChecksum = ByteArray(hash.rawLength) { 0x7 }
        val target = File(temp.root, "pack-test.idx")

        PackIndexWriter.write(target, objects, packChecksum, hash)

        DataInputStream(target.inputStream().buffered()).use { input ->
            assertArrayEquals(
                byteArrayOf(0xff.toByte(), 't'.code.toByte(), 'O'.code.toByte(), 'c'.code.toByte()),
                ByteArray(4).also(input::readFully),
            )
            assertEquals(2, input.readInt())

            // Fanout is cumulative: every bucket holds the count of objects whose
            // first byte is <= that bucket's index.
            val fanout = IntArray(256) { input.readInt() }
            assertEquals(1, fanout[0x00])
            assertEquals(1, fanout[0x3f])
            assertEquals(2, fanout[0x40])
            assertEquals(3, fanout[0xff])

            objects.forEach { assertArrayEquals(it.id, ByteArray(hash.rawLength).also(input::readFully)) }
            objects.forEach { assertEquals(it.crc32, input.readInt()) }
            objects.forEach { assertEquals(it.offset.toInt(), input.readInt()) }

            assertArrayEquals(packChecksum, ByteArray(hash.rawLength).also(input::readFully))
        }
    }

    @Test
    fun `objects are sorted by id regardless of input order`() {
        val objects = listOf(
            IndexedObject(id(0xff), 900L, 3),
            IndexedObject(id(0x00), 12L, 1),
        )
        val target = File(temp.root, "pack-sorted.idx")

        PackIndexWriter.write(target, objects, ByteArray(hash.rawLength), hash)

        DataInputStream(target.inputStream().buffered()).use { input ->
            input.skipNBytes((8 + 256 * 4).toLong())
            assertEquals(0x00.toByte(), ByteArray(hash.rawLength).also(input::readFully)[0])
            assertEquals(0xff.toByte(), ByteArray(hash.rawLength).also(input::readFully)[0])
        }
    }

    @Test
    fun `an offset beyond 2GB moves to the large offset table`() {
        val big = 3L shl 30 // 3 GiB, past the 31-bit limit
        val target = File(temp.root, "pack-large.idx")

        PackIndexWriter.write(
            target,
            listOf(IndexedObject(id(1), big, 0)),
            ByteArray(hash.rawLength),
            hash,
        )

        DataInputStream(target.inputStream().buffered()).use { input ->
            input.skipNBytes((8 + 256 * 4 + hash.rawLength + 4).toLong())
            val encoded = input.readInt()
            // Int.MIN_VALUE is 0x80000000 — written this way because in Kotlin,
            // unlike Java, the hex literal itself is typed Long and won't compile
            // against an Int.
            assertEquals("MSB marks an indirection", Int.MIN_VALUE, encoded and Int.MIN_VALUE)
            assertEquals("index 0 into the large table", 0, encoded and 0x7fffffff)
            assertEquals(big, input.readLong())
        }
    }
}
