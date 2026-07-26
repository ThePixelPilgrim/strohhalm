package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.MirrorProgress
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Pack ingestion in two passes.
 *
 * Pass one streams the pack to disk while hashing it, so the server's trailer
 * checksum is verified without holding anything in memory — a 72k-object pack
 * must never be buffered on a phone. Pass two reads back through the file,
 * inflating each object and resolving deltas, to compute the ids the index
 * needs.
 *
 * The engine never interprets an object body; the only reason it inflates at all
 * is that an id is the hash of the *inflated* content.
 */
class KotlinPackIndexer : PackIndexer {

    private data class Entry(
        val offset: Long,
        val type: Int,
        val dataOffset: Long,
        val baseOffset: Long,
        val baseId: ByteArray?,
        val crc32: Int,
    )

    override fun consume(
        pack: InputStream,
        hash: ObjectHash,
        objectsDir: File,
        progress: MirrorProgress?,
    ): PackResult {
        val packDir = File(objectsDir, "pack").apply { mkdirs() }
        val temporary = File.createTempFile("incoming-", ".pack", packDir)

        try {
            val checksum = writeToDisk(pack, temporary, hash)
            val entries = RandomAccessFile(temporary, "r").use { file ->
                scan(file, hash, progress)
            }
            val indexed = RandomAccessFile(temporary, "r").use { file ->
                resolve(file, entries, hash, progress)
            }

            val name = "pack-${hash.toHex(checksum)}"
            val finalPack = File(packDir, "$name.pack")
            if (!temporary.renameTo(finalPack)) {
                throw IOException("could not move the pack into place: $finalPack")
            }
            PackIndexWriter.write(File(packDir, "$name.idx"), indexed, checksum, hash)

            return PackResult(name, indexed.size, finalPack.length())
        } finally {
            temporary.delete()
        }
    }

    /**
     * Streams to disk, verifying the trailer the server computed.
     *
     * The digest covers everything *except* the final `rawLength` bytes, which
     * *are* that digest — so that many bytes are always held back in [carry]
     * until more input proves they were content. The trailer still belongs in
     * the file: git's own tools expect every `.pack` to end with its checksum.
     */
    private fun writeToDisk(pack: InputStream, target: File, hash: ObjectHash): ByteArray {
        val digest = hash.newDigest()
        val carry = ByteArray(hash.rawLength)
        var carried = 0

        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("sync cancelled")
                }
                val read = pack.read(buffer)
                if (read < 0) break

                // Emit in bulk, not byte-at-a-time: this loop sees every byte of
                // a transfer, and 44 MiB of single-byte digest updates is the
                // difference between seconds and minutes on a phone.
                val total = carried + read
                val emit = total - hash.rawLength
                if (emit <= 0) {
                    System.arraycopy(buffer, 0, carry, carried, read)
                    carried = total
                    continue
                }
                val fromCarry = minOf(carried, emit)
                if (fromCarry > 0) {
                    digest.update(carry, 0, fromCarry)
                    out.write(carry, 0, fromCarry)
                    System.arraycopy(carry, fromCarry, carry, 0, carried - fromCarry)
                    carried -= fromCarry
                }
                val fromBuffer = emit - fromCarry
                if (fromBuffer > 0) {
                    digest.update(buffer, 0, fromBuffer)
                    out.write(buffer, 0, fromBuffer)
                }
                System.arraycopy(buffer, fromBuffer, carry, carried, read - fromBuffer)
                carried = hash.rawLength
            }

            if (carried != hash.rawLength) throw IOException("pack ended before its checksum")

            // The held-back checksum is written last, undigested — the file must
            // be byte-identical to what the server sent.
            out.write(carry, 0, carried)
        }

        val computed = digest.digest()
        if (!computed.contentEquals(carry)) {
            throw IOException("pack checksum mismatch: the transfer was corrupted")
        }
        return computed
    }

    /** Records where every object starts, without inflating any of them. */
    private fun scan(
        file: RandomAccessFile,
        hash: ObjectHash,
        progress: MirrorProgress?,
    ): List<Entry> {
        val magic = ByteArray(4).also(file::readFully)
        if (!magic.contentEquals("PACK".toByteArray(Charsets.US_ASCII))) {
            throw IOException("not a packfile")
        }
        val version = file.readInt()
        if (version != 2 && version != 3) throw IOException("unsupported pack version $version")
        val count = file.readInt()

        val entries = ArrayList<Entry>(count)
        repeat(count) { index ->
            val offset = file.filePointer
            val crc = CRC32()

            var b = file.readUnsignedByte().also { crc.update(it) }
            val type = (b shr 4) and 7
            var size = (b and 0x0f).toLong()
            var shift = 4
            while (b and 0x80 != 0) {
                b = file.readUnsignedByte().also { crc.update(it) }
                size = size or ((b and 0x7f).toLong() shl shift)
                shift += 7
            }

            var baseOffset = -1L
            var baseId: ByteArray? = null
            when (type) {
                OBJ_OFS_DELTA -> {
                    b = file.readUnsignedByte().also { crc.update(it) }
                    var delta = (b and 0x7f).toLong()
                    while (b and 0x80 != 0) {
                        b = file.readUnsignedByte().also { crc.update(it) }
                        delta = ((delta + 1) shl 7) or (b and 0x7f).toLong()
                    }
                    baseOffset = offset - delta
                }

                OBJ_REF_DELTA -> {
                    baseId = ByteArray(hash.rawLength).also(file::readFully)
                    crc.update(baseId)
                }
            }

            val dataOffset = file.filePointer
            val compressed = skipDeflated(file, size)
            crc.update(compressed)

            entries += Entry(offset, type, dataOffset, baseOffset, baseId, crc.value.toInt())
            if (index % PROGRESS_EVERY == 0) {
                // RandomAccessFile work is not interruptible on its own; the
                // flag must be polled or cancellation cannot reach this phase.
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("sync cancelled")
                }
                progress?.update("Indexing objects", index, count)
            }
        }
        progress?.update("Indexing objects", count, count)
        return entries
    }

    /**
     * Advances past one deflate stream and returns its raw bytes.
     *
     * The pack does not record the compressed length, so the only way to find the
     * next object is to inflate until this one ends and ask the [Inflater] how
     * many bytes it actually consumed.
     */
    private fun skipDeflated(file: RandomAccessFile, expectedSize: Long): ByteArray {
        val start = file.filePointer
        val inflater = Inflater()
        val input = ByteArray(BUFFER)
        val scratch = ByteArray(BUFFER)
        var consumed = 0L
        var produced = 0L
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    val read = file.read(input)
                    if (read < 0) throw IOException("pack ended inside a compressed object")
                    inflater.setInput(input, 0, read)
                    consumed += read
                }
                produced += inflater.inflate(scratch)
            }
            if (produced != expectedSize) {
                throw IOException("object declared $expectedSize bytes, inflated to $produced")
            }
            val used = consumed - inflater.remaining
            file.seek(start + used)
            val raw = ByteArray(used.toInt())
            val here = file.filePointer
            file.seek(start)
            file.readFully(raw)
            file.seek(here)
            return raw
        } finally {
            inflater.end()
        }
    }

    /** Inflates and resolves, producing the ids the index is built from. */
    private fun resolve(
        file: RandomAccessFile,
        entries: List<Entry>,
        hash: ObjectHash,
        progress: MirrorProgress?,
    ): List<IndexedObject> {
        val byOffset = entries.associateBy { it.offset }
        val contentCache = HashMap<Long, Pair<Int, ByteArray>>()
        val idToOffset = HashMap<String, Long>()
        val indexed = ArrayList<IndexedObject>(entries.size)

        fun contentOf(entry: Entry): Pair<Int, ByteArray> {
            contentCache[entry.offset]?.let { return it }

            val body = inflate(file, entry.dataOffset)
            val resolved = when (entry.type) {
                OBJ_OFS_DELTA, OBJ_REF_DELTA -> {
                    val base = when (entry.type) {
                        OBJ_OFS_DELTA -> byOffset[entry.baseOffset]
                            ?: throw IOException("delta base at ${entry.baseOffset} is missing")

                        else -> {
                            val key = hash.toHex(entry.baseId!!)
                            val offset = idToOffset[key]
                                ?: throw IOException("delta base $key is not in this pack")
                            byOffset[offset]!!
                        }
                    }
                    val (baseType, baseBytes) = contentOf(base)
                    baseType to PackDelta.apply(baseBytes, body)
                }

                else -> entry.type to body
            }
            contentCache[entry.offset] = resolved
            if (contentCache.size > CACHE_LIMIT) {
                contentCache.keys.take(contentCache.size - CACHE_LIMIT).forEach(contentCache::remove)
            }
            return resolved
        }

        entries.forEachIndexed { index, entry ->
            val (type, body) = contentOf(entry)
            val id = hash.objectId(typeName(type), body)
            idToOffset[hash.toHex(id)] = entry.offset
            indexed += IndexedObject(id, entry.offset, entry.crc32)
            if (index % PROGRESS_EVERY == 0) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("sync cancelled")
                }
                progress?.update("Resolving deltas", index, entries.size)
            }
        }
        progress?.update("Resolving deltas", entries.size, entries.size)
        return indexed
    }

    private fun inflate(file: RandomAccessFile, dataOffset: Long): ByteArray {
        file.seek(dataOffset)
        val stream = InflaterInputStream(
            object : InputStream() {
                override fun read(): Int = file.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = file.read(b, off, len)
            }
        )
        return stream.readBytes()
    }

    private fun typeName(type: Int): String = when (type) {
        OBJ_COMMIT -> "commit"
        OBJ_TREE -> "tree"
        OBJ_BLOB -> "blob"
        OBJ_TAG -> "tag"
        else -> throw IOException("unresolved object type $type")
    }

    private companion object {
        const val OBJ_COMMIT = 1
        const val OBJ_TREE = 2
        const val OBJ_BLOB = 3
        const val OBJ_TAG = 4
        const val OBJ_OFS_DELTA = 6
        const val OBJ_REF_DELTA = 7

        const val BUFFER = 64 * 1024
        const val CACHE_LIMIT = 256
        const val PROGRESS_EVERY = 256
    }
}
