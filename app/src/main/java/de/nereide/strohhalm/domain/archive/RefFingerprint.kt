package de.nereide.strohhalm.domain.archive

import java.security.MessageDigest

/**
 * A digest of a mirror's entire ref list — the answer to "has this repository
 * changed since the archive was built?".
 *
 * It covers every ref rather than HEAD alone, because a sync that adds only a
 * tag or a side branch leaves HEAD where it was. Keyed on HEAD, such a sync
 * would leave a stale archive that still verifies clean, and sharing a backup
 * silently missing refs is the worst thing this feature could do.
 *
 * The digest is always SHA-256 regardless of the repository's own object
 * format: it identifies a state of the ref list, not a git object.
 */
object RefFingerprint {

    fun of(refs: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Sorted, so an unordered map cannot change the answer. The space and
        // newline are load-bearing: without a separator, "ab" + "c" and
        // "a" + "bc" would hash identically.
        refs.entries
            .map { (name, id) -> "$id $name" }
            .sorted()
            .forEach {
                digest.update(it.toByteArray(Charsets.UTF_8))
                digest.update('\n'.code.toByte())
            }
        return digest.digest().toHex()
    }
}

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    this@toHex.forEach { append(HEX[(it.toInt() shr 4) and 0xf]).append(HEX[it.toInt() and 0xf]) }
}

private const val HEX = "0123456789abcdef"
