package de.nereide.strohhalm.domain.archive

import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * What is recorded beside an archive.
 *
 * Two checksums, because two independent questions get asked of a cached
 * archive: [archiveSha256] answers *is this file intact?*, and
 * [refFingerprint] answers *is this file current?*. Neither implies the other —
 * a stale archive verifies perfectly, and a current one can be truncated.
 *
 * The rendered form is valid input to `sha256sum -c`, which ignores `#` lines,
 * so a recipient can check the archive with ordinary tools.
 */
data class Sidecar(
    val refFingerprint: String,
    val archiveSha256: String,
    val archiveName: String,
) {
    fun render(): String = "# refs $refFingerprint\n$archiveSha256  $archiveName\n"

    companion object {
        fun parse(text: String): Sidecar? {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            val refs = lines.firstOrNull { it.startsWith("# refs ") }
                ?.removePrefix("# refs ")?.trim() ?: return null
            val checksum = lines.firstOrNull { !it.startsWith("#") } ?: return null
            val hash = checksum.substringBefore(' ').trim()
            val name = checksum.substringAfter(' ').trim()
            if (hash.isEmpty() || name.isEmpty()) return null
            return Sidecar(refFingerprint = refs, archiveSha256 = hash, archiveName = name)
        }
    }
}

/**
 * The archive cache.
 *
 * Everything here rests on one rule: an archive is current exactly when its
 * sidecar's ref fingerprint matches the mirror's. There is no timer, no grace
 * period and no protection for a recently shared file — deleting a file another
 * process holds open is harmless on Android, since the reader's descriptor
 * keeps the data alive until it closes.
 */
class ArchiveStore(
    private val root: File,
    private val archiver: MirrorArchiver = ZipMirrorArchiver(),
) {

    /**
     * The `.part` names a build is writing right now.
     *
     * An *orphaned* part file is worthless and [prune] is right to clear it; a
     * *live* one is minutes of packing, and unlinking it does not stop the
     * writer — the bytes go on filling an anonymous inode, the finalising
     * rename finds no source, and the user gets a generic failure after the
     * whole wait. Concurrent because the two genuinely run on different
     * threads: maintenance prunes on `Dispatchers.IO` while the pack runs
     * inside `runInterruptible` elsewhere.
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** A finished, intact, current archive for [slug], or null. */
    fun existing(slug: String, fingerprint: String): File? {
        val candidate = root.listFiles()
            ?.firstOrNull { ArchiveNames.matches(it.name, slug, fingerprint) }
            ?: return null

        val sidecar = File(root, ArchiveNames.sidecar(candidate.name))
        if (!sidecar.isFile) return null
        val recorded = Sidecar.parse(sidecar.readText()) ?: return null

        // The full fingerprint, not the twelve characters in the filename. That
        // prefix narrows the search; it does not decide.
        if (recorded.refFingerprint != fingerprint) return null
        if (recorded.archiveName != candidate.name) return null
        if (checksumOf(candidate) != recorded.archiveSha256) return null
        return candidate
    }

    /**
     * Builds an archive for [slug], replacing any superseded one.
     *
     * The build goes to a `.part` file and is renamed only once complete, so an
     * interrupted build can never be found by [existing]. The rename is the
     * mechanism; the checksum is the second line of defence.
     */
    fun build(
        slug: String,
        gitDir: File,
        fingerprint: String,
        lastSyncAt: Long,
        progress: ArchiveProgress?,
    ): File {
        root.mkdirs()
        val name = ArchiveNames.archive(slug, lastSyncAt, fingerprint)
        val part = File(root, ArchiveNames.part(name))
        val archive = File(root, name)

        inFlight.add(part.name)
        val result = try {
            archiver.archive(gitDir, part, progress)
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            // Both ways out: on the throw the part is already gone, and on
            // success the rename below is what removes it.
            inFlight.remove(part.name)
        }

        if (!part.renameTo(archive)) {
            part.delete()
            throw java.io.IOException("could not finalise $archive")
        }
        File(root, ArchiveNames.sidecar(name)).writeText(
            Sidecar(fingerprint, result.sha256, name).render()
        )

        // A rebuild happens because the refs moved, so clearing the superseded
        // archive is the ordinary prune on the ordinary condition.
        prune(slug, fingerprint)
        return archive
    }

    /**
     * Deletes archives of [slug] that no longer describe the mirror.
     *
     * A null [currentFingerprint] means *all of them*, which is what a severe
     * memory trim asks for: an archive is fully regenerable, so it is the first
     * thing worth giving back.
     *
     * @return how many archives were removed.
     */
    fun prune(slug: String, currentFingerprint: String?): Int {
        val files = root.listFiles() ?: return 0
        var removed = 0
        // Ownership, not a prefix: "notes" must not delete "notes-2"'s files.
        files.filter { it.name.endsWith(".zip") && ArchiveNames.belongsTo(it.name, slug) }
            .forEach { archive ->
                val keep = currentFingerprint != null &&
                    ArchiveNames.matches(archive.name, slug, currentFingerprint)
                if (!keep) {
                    File(root, ArchiveNames.sidecar(archive.name)).delete()
                    if (archive.delete()) removed++
                }
            }
        // Orphaned part files are never valid; a build that left one is over.
        // One a build is still writing is not orphaned, and survives.
        files.filter {
            it.name.endsWith(".part") &&
                ArchiveNames.belongsTo(it.name, slug) &&
                it.name !in inFlight
        }.forEach { it.delete() }
        return removed
    }

    private fun checksumOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream().buffered(), digest).use { input ->
            val buffer = ByteArray(64 * 1024)
            // Not readNBytes: Android only gained it at API 33, and minSdk is 26.
            while (input.read(buffer) >= 0) Unit
        }
        return digest.digest().toHex()
    }
}
