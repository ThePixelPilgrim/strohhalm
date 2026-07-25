package de.nereide.strohhalm.domain

import java.io.File

/**
 * Turns the document id of a Storage Access Framework tree URI into a real
 * filesystem path.
 *
 * The picker is used purely as a chooser: access comes from
 * MANAGE_EXTERNAL_STORAGE and ordinary `java.io.File`, not from the URI, because
 * JGit needs a real path and SAF only hands out opaque document URIs.
 *
 * A document id looks like `primary:Backups/git` — a volume id, a colon, then a
 * path relative to that volume. Deriving the path is therefore a guess, which is
 * why [isWritable] must confirm it before it is persisted.
 */
object StorageRootResolver {

    /**
     * @param volumeLookup resolves a non-primary volume id to its mount point.
     * @return the directory, or null when the volume is unknown or the id is
     *   malformed — in which case the caller falls back to manual entry.
     */
    fun resolve(
        documentId: String,
        primaryRoot: File,
        volumeLookup: (String) -> File?,
    ): File? {
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2) return null
        val (volumeId, relativePath) = parts

        val base = if (volumeId == PRIMARY_VOLUME) primaryRoot else volumeLookup(volumeId)
            ?: return null

        return if (relativePath.isEmpty()) base else File(base, relativePath)
    }

    /**
     * Confirms the derived path is real and writable by creating and deleting a
     * probe file. Checking `File.canWrite()` alone is not enough — it reports
     * stale results under scoped storage.
     */
    fun isWritable(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val probe = File(dir, ".strohhalm-write-probe")
        return try {
            probe.writeBytes(ByteArray(0))
            probe.isFile
        } catch (e: Exception) {
            false
        } finally {
            probe.delete()
        }
    }

    private const val PRIMARY_VOLUME = "primary"
}
