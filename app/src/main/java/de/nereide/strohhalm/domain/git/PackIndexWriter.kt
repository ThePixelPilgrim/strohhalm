package de.nereide.strohhalm.domain.git

import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream

/** One entry in a pack index: where an object is, and how to check it. */
class IndexedObject(
    val id: ByteArray,
    val offset: Long,
    val crc32: Int,
)

/**
 * Writes a pack index, version 2.
 *
 * Layout, in order: the magic `\377tOc`, version 2, a 256-entry cumulative
 * fanout over the first byte of each id, the sorted ids, one CRC32 per object,
 * one 32-bit offset per object, a table of 64-bit offsets for anything past
 * 2 GiB, the pack checksum, and finally a checksum of the index itself.
 *
 * Every width that depends on the hash comes from [ObjectHash.rawLength]. This
 * file is the reason SHA-256 works: the format was always hash-parameterised,
 * and only the implementations hardcoded 20.
 *
 * The index is derivable — `git index-pack -f` regenerates it from the pack — so
 * a defect here costs one repair command rather than data.
 */
object PackIndexWriter {

    private val MAGIC = byteArrayOf(0xff.toByte(), 't'.code.toByte(), 'O'.code.toByte(), 'c'.code.toByte())
    private const val VERSION = 2

    /**
     * `0x80000000` — the MSB that marks an indirection into the 64-bit table.
     * Written as [Int.MIN_VALUE] because in Kotlin, unlike Java, the hex
     * literal is typed `Long` and cannot be used where an `Int` is needed.
     */
    private const val LARGE_OFFSET_FLAG = Int.MIN_VALUE
    private const val MAX_SMALL_OFFSET = 0x7fffffffL

    fun write(
        target: File,
        objects: List<IndexedObject>,
        packChecksum: ByteArray,
        hash: ObjectHash,
    ) {
        val sorted = objects.sortedWith { a, b -> compareIds(a.id, b.id) }
        val digest = hash.newDigest()

        target.outputStream().buffered().use { raw ->
            val out = DataOutputStream(DigestOutputStream(raw, digest))

            out.write(MAGIC)
            out.writeInt(VERSION)

            // Fanout: bucket i holds the number of objects whose first byte is <= i.
            var seen = 0
            var bucket = 0
            val counts = IntArray(256)
            sorted.forEach { counts[it.id[0].toInt() and 0xff]++ }
            while (bucket < 256) {
                seen += counts[bucket]
                out.writeInt(seen)
                bucket++
            }

            sorted.forEach { out.write(it.id) }
            sorted.forEach { out.writeInt(it.crc32) }

            val largeOffsets = mutableListOf<Long>()
            sorted.forEach { entry ->
                if (entry.offset <= MAX_SMALL_OFFSET) {
                    out.writeInt(entry.offset.toInt())
                } else {
                    out.writeInt(LARGE_OFFSET_FLAG or largeOffsets.size)
                    largeOffsets += entry.offset
                }
            }
            largeOffsets.forEach { out.writeLong(it) }

            out.write(packChecksum)
            out.flush()

            // The trailing checksum covers everything above it, so it is written
            // outside the digest stream.
            raw.write(digest.digest())
            raw.flush()
        }
    }

    /** Unsigned byte order, which is how git sorts object ids. */
    private fun compareIds(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val difference = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return 0
    }
}
