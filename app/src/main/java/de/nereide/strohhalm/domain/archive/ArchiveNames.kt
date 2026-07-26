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
        return shortIn(fileName.removeSuffix(".zip"), slug) == fingerprint.take(SHORT)
    }

    /**
     * Whether [fileName] is an archive, a sidecar or a part file of exactly
     * [slug] — the question deletion asks, where a wrong yes destroys another
     * repository's archive.
     *
     * Ownership is decided by the same anchoring [matches] uses, not by a
     * prefix: "notes" must not claim "notes-2-…".
     */
    fun belongsTo(fileName: String, slug: String): Boolean {
        val base = fileName.removeSuffix(".part").removeSuffix(".sha256")
        if (!base.endsWith(".zip")) return false
        val short = shortIn(base.removeSuffix(".zip"), slug) ?: return false
        return short.all { it in HEX }
    }

    /**
     * The archive slug of the mirror kept in [gitDir].
     *
     * It has to come from the directory name, because that is the
     * collision-resolved slug: two repositories can share a remote basename,
     * and the second one's mirror is `notes-2.git`. Deriving the slug from the
     * remote URL instead would give both of them "notes", so they would share
     * one archive namespace and prune each other's files on every sync.
     */
    fun slugForMirror(gitDir: java.io.File): String = gitDir.name.removeSuffix(".git")

    /**
     * The short fingerprint ending [stem], if [stem] is exactly
     * `<slug>-<date>-<short>`; null otherwise.
     *
     * Anchoring both ends is what separates a slug that is a prefix of another:
     * "my-notes" must not match "my-notes-2-…". Everything that has to make
     * that distinction goes through here, so the two callers cannot drift.
     */
    private fun shortIn(stem: String, slug: String): String? {
        val prefix = "$slug-"
        if (!stem.startsWith(prefix)) return null
        val rest = stem.removePrefix(prefix)
        if (rest.length != DATE_LENGTH + 1 + SHORT) return null
        if (rest[DATE_LENGTH] != '-') return null
        return rest.substring(DATE_LENGTH + 1)
    }

    /** The name the recipient sees: no hash, because it means nothing to them. */
    fun displayName(archiveName: String): String {
        if (!archiveName.endsWith(".zip")) return archiveName
        val stem = archiveName.removeSuffix(".zip")
        val short = stem.takeLast(SHORT)
        if (short.length < SHORT || !short.all { it in HEX }) return archiveName
        if (stem.length < SHORT + 1 || stem[stem.length - SHORT - 1] != '-') return archiveName
        return stem.dropLast(SHORT + 1) + ".zip"
    }

    private const val DATE_LENGTH = 10

    private const val HEX = "0123456789abcdef"
}
