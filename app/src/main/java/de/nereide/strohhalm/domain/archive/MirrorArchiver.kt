package de.nereide.strohhalm.domain.archive

import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Progress while packing. Mirrors `MirrorProgress` so one bar renders both. */
fun interface ArchiveProgress {
    fun update(task: String, completed: Int, total: Int)
}

data class ArchiveResult(
    val sha256: String,
    val bytes: Long,
    val entries: Int,
)

interface MirrorArchiver {
    /**
     * Packs [gitDir] into [target], returning the archive's own checksum.
     *
     * Throws [InterruptedException] if the calling thread is interrupted, and
     * deletes [target] before doing so — a half-written archive that survived
     * would be indistinguishable from a finished one.
     */
    fun archive(gitDir: File, target: File, progress: ArchiveProgress?): ArchiveResult
}

class ZipMirrorArchiver : MirrorArchiver {

    override fun archive(gitDir: File, target: File, progress: ArchiveProgress?): ArchiveResult {
        // Sorted, because directory iteration order is not guaranteed and the
        // archive has to be reproducible for its checksum to mean anything.
        //
        // Empty directories are carried too, and they are not cosmetic: a bare
        // mirror keeps every ref in `packed-refs`, which leaves `refs/heads`
        // and `refs/tags` empty on disk. Git decides whether a directory is a
        // repository by looking for `objects` and `refs`, so an archive that
        // dropped them unpacks into something git rejects outright with
        // "not a git repository" — the mirror would be intact and unusable.
        // Non-empty directories need no entry: unzip creates the parents of a
        // file it is writing.
        val entries = gitDir.walkTopDown()
            .filter { it.isFile || it.isEmptyDirectory() }
            .map { it to it.relativeTo(gitDir).path.replace(File.separatorChar, '/') }
            .filter { (_, relative) -> relative.isNotEmpty() }
            .sortedBy { (_, relative) -> relative }
            .toList()

        val root = gitDir.name
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            DigestOutputStream(FileOutputStream(target).buffered(), digest).use { sink ->
                ZipOutputStream(sink).use { zip ->
                    entries.forEachIndexed { index, (file, relative) ->
                        // Polled per entry: the work is blocking file I/O that no
                        // coroutine can unwind on its own.
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("archive cancelled")
                        }
                        if (file.isDirectory) {
                            zip.putNextEntry(directoryEntry("$root/$relative/"))
                            zip.closeEntry()
                            progress?.update("Packing", index + 1, entries.size)
                            return@forEachIndexed
                        }
                        // Pack files arrive already deflated. Compressing them
                        // again costs real seconds on a phone and saves nothing.
                        val name = "$root/$relative"
                        val stored = relative.endsWith(".pack") || relative.endsWith(".idx")
                        zip.setLevel(
                            if (stored) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION
                        )
                        zip.putNextEntry(
                            if (stored) storedEntry(name, file) else ZipEntry(name).apply { time = FIXED_TIME }
                        )
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        progress?.update("Packing", index + 1, entries.size)
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }

        return ArchiveResult(
            sha256 = digest.digest().toHex(),
            bytes = target.length(),
            entries = entries.size,
        )
    }

    /** True for a directory holding nothing at all — no files, no subdirectories. */
    private fun File.isEmptyDirectory(): Boolean =
        isDirectory && (listFiles()?.isEmpty() ?: false)

    /**
     * A zero-length entry whose name ends in `/`, which is how a zip records a
     * directory. `STORED` with an explicit size and CRC of zero, because that
     * is what every other packer writes and the most conservative thing to
     * hand an unzip implementation we do not control.
     */
    private fun directoryEntry(name: String): ZipEntry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = 0
        compressedSize = 0
        crc = 0
        time = FIXED_TIME
    }

    /**
     * A `STORED` entry, copied in verbatim with no DEFLATE framing at all.
     *
     * `Deflater.NO_COMPRESSION` is not enough: it still emits deflate blocks,
     * each with a five-byte header, so the entry ends up slightly *larger*
     * than its input. `STORED` is the only method that writes the bytes as
     * they are, and it makes the writer state the size and CRC up front —
     * hence the extra streaming pass. The file is read in chunks so a
     * multi-megabyte pack never lands on the heap.
     */
    private fun storedEntry(name: String, file: File): ZipEntry {
        val crc = CRC32()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = file.length()
            compressedSize = file.length()
            this.crc = crc.value
            time = FIXED_TIME
        }
    }

    private companion object {
        /**
         * A fixed entry timestamp, so the same mirror always yields the same
         * bytes. Only stability on one device matters — the checksum is never
         * compared against one computed elsewhere.
         *
         * 2000-01-01T00:00:00Z, comfortably inside the DOS timestamp range a
         * zip entry can represent, whose epoch is 1980.
         */
        const val FIXED_TIME = 946_684_800_000L
    }
}
