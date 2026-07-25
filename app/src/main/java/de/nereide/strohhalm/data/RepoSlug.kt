package de.nereide.strohhalm.data

/**
 * Derives the on-disk directory name for a mirror from its remote URL.
 *
 * The order of the two `substringAfterLast` calls matters: splitting on `/`
 * first and `:` second handles both `ssh://host/a/b/repo.git` and the scp-style
 * `git@host:repo.git`, where the whole string survives the first split.
 */
object RepoSlug {

    fun fromRemoteUrl(url: String): String {
        val lastSegment = url.trim().trimEnd('/')
            .substringAfterLast('/')
            .substringAfterLast(':')
        val slug = lastSegment.removeSuffix(".git")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifEmpty { FALLBACK }
    }

    /**
     * Appends a numeric suffix until the name is free. Counting starts at 2 so
     * the first collision reads `notes-2`, matching how a person would name it.
     */
    fun unique(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private const val FALLBACK = "repo"
}
