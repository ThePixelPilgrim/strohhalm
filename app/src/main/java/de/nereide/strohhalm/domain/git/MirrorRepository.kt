package de.nereide.strohhalm.domain.git

import java.io.File
import java.io.IOException

/**
 * A bare mirror on disk.
 *
 * The layout is deliberately the plainest git accepts — loose `packed-refs`, a
 * `HEAD` file, packs under `objects/pack` — because the whole recovery promise is
 * that a user's own `git` can read the folder with no Strohhalm-specific tooling.
 *
 * Fetches append packs and never repack. For an append-only backup that is the
 * right behaviour: nothing already written is ever rewritten.
 */
class MirrorRepository(val gitDir: File) {

    fun exists(): Boolean = File(gitDir, "HEAD").isFile

    fun objectsDir(): File = File(gitDir, "objects")

    fun initialise(hash: ObjectHash) {
        File(gitDir, "objects/pack").mkdirs()
        File(gitDir, "refs/heads").mkdirs()
        File(gitDir, "refs/tags").mkdirs()
        File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")

        // Format version 1 plus an extension is how git marks a non-SHA-1
        // repository; a SHA-1 one stays at version 0 so older git can read it.
        val config = buildString {
            appendLine("[core]")
            appendLine("\trepositoryformatversion = ${if (hash == ObjectHash.SHA1) 0 else 1}")
            appendLine("\tfilemode = false")
            appendLine("\tbare = true")
            if (hash != ObjectHash.SHA1) {
                appendLine("[extensions]")
                appendLine("\tobjectFormat = ${hash.configName}")
            }
        }
        File(gitDir, "config").writeText(config)
    }

    fun objectHash(): ObjectHash {
        val config = File(gitDir, "config")
        if (!config.isFile) throw IOException("not a repository: $gitDir")
        val declared = config.readLines()
            .firstOrNull { it.trim().startsWith("objectFormat") }
            ?.substringAfter('=')
            ?.trim()
        return declared?.let(ObjectHash::fromConfigName) ?: ObjectHash.SHA1
    }

    /** Every ref this mirror already holds, packed or loose. */
    fun localRefs(): Map<String, String> {
        val refs = linkedMapOf<String, String>()

        File(gitDir, "packed-refs").takeIf { it.isFile }?.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("^")) return@forEachLine
            val id = trimmed.substringBefore(' ')
            val name = trimmed.substringAfter(' ', "")
            if (name.isNotEmpty()) refs[name] = id
        }

        // A mirror created by the JGit engine may hold refs loose. Both must be
        // read, or an incremental fetch would offer no haves and re-download.
        // Loose wins over packed — git's own precedence, and the packed entry
        // is the stale one after a JGit fetch updated a ref loose.
        val refsRoot = File(gitDir, "refs")
        if (refsRoot.isDirectory) {
            refsRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = file.relativeTo(gitDir).path.replace(File.separatorChar, '/')
                refs[name] = file.readText().trim()
            }
        }
        return refs
    }

    fun refNames(): List<String> = localRefs().keys.sorted()

    /**
     * Replaces the ref set wholesale, which is what makes this a mirror: a ref
     * deleted upstream disappears here too. A merging write would let a deleted
     * branch linger forever and quietly diverge from the remote.
     */
    fun writeRefs(refs: List<RemoteRef>) {
        val head = refs.firstOrNull { it.name == "HEAD" }
        val entries = refs.filter { it.name != "HEAD" }.sortedBy { it.name }

        val packed = buildString {
            appendLine("# pack-refs with: peeled fully-peeled sorted ")
            entries.forEach { ref ->
                appendLine("${ref.objectId} ${ref.name}")
                ref.peeled?.let { appendLine("^$it") }
            }
        }
        File(gitDir, "packed-refs").writeText(packed)

        // Loose refs would shadow packed-refs, so a previous engine's leftovers
        // are cleared rather than left to win.
        File(gitDir, "refs").walkBottomUp().forEach { file ->
            if (file.isFile) file.delete()
        }
        File(gitDir, "refs/heads").mkdirs()
        File(gitDir, "refs/tags").mkdirs()

        head?.symrefTarget?.let(::setHead)
    }

    fun setHead(target: String) {
        File(gitDir, "HEAD").writeText("ref: $target\n")
    }
}
