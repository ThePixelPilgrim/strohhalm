package de.nereide.strohhalm.domain.archive

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The one place that knows what an archive is called.
 *
 * A name carries three things: which repository, when the copy was taken, and
 * which ref state it holds. Only the third takes part in lookup — the date is
 * there for whoever receives the file.
 */
object ArchiveNames {

    /** How much of the fingerprint appears in the filename. */
    const val SHORT = 12

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun archive(
        slug: String,
        lastSyncAt: Long,
        fingerprint: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = DATE.format(Instant.ofEpochMilli(lastSyncAt).atZone(zone).toLocalDate())
        return "$slug-$date-${fingerprint.take(SHORT)}.zip"
    }

    fun sidecar(archiveName: String): String = "$archiveName.sha256"

    fun part(archiveName: String): String = "$archiveName.part"

    /**
     * Whether [fileName] is an archive of [slug] holding [fingerprint]'s ref
     * state — a *hint*, narrowing a directory to one candidate. The sidecar
     * decides; a twelve-character prefix is an index, not a proof.
     */
    fun matches(fileName: String, slug: String, fingerprint: String): Boolean {
        if (!fileName.endsWith(".zip")) return false
        val stem = fileName.removeSuffix(".zip")
        return stem.startsWith("$slug-") &&
            stem.endsWith("-${fingerprint.take(SHORT)}") &&
            // Guards a slug that is a prefix of another: "my-notes" must not
            // match "my-notes-2-…". What is left between the two anchors has to
            // be exactly the date.
            stem.removePrefix("$slug-").removeSuffix("-${fingerprint.take(SHORT)}").length == DATE_LENGTH
    }

    /** The name the recipient sees: no hash, because it means nothing to them. */
    fun displayName(archiveName: String): String {
        if (!archiveName.endsWith(".zip")) return archiveName
        val stem = archiveName.removeSuffix(".zip")
        val short = stem.takeLast(SHORT)
        if (short.length < SHORT || !short.all { it in "0123456789abcdef" }) return archiveName
        if (stem.length < SHORT + 1 || stem[stem.length - SHORT - 1] != '-') return archiveName
        return stem.dropLast(SHORT + 1) + ".zip"
    }

    private const val DATE_LENGTH = 10
}
