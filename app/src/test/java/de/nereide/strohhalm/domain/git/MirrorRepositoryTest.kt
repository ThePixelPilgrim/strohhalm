package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MirrorRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    private fun repo(): MirrorRepository = MirrorRepository(File(temp.root, "repo.git"))

    private fun ref(name: String, id: String) = RemoteRef(name = name, objectId = id)

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun `initialising a sha256 mirror records the object format`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA256)

        val config = File(mirror.gitDir, "config").readText()
        assertTrue(config.contains("repositoryformatversion = 1"))
        assertTrue(config.contains("objectFormat = sha256"))
        assertTrue(config.contains("bare = true"))
        assertTrue(File(mirror.gitDir, "HEAD").isFile)
        assertTrue(File(mirror.gitDir, "objects/pack").isDirectory)
        assertEquals(ObjectHash.SHA256, mirror.objectHash())
    }

    @Test
    fun `a sha1 mirror stays at format version 0, as git writes it`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA1)

        val config = File(mirror.gitDir, "config").readText()
        assertTrue(config.contains("repositoryformatversion = 0"))
        assertFalse(config.contains("objectFormat"))
        assertEquals(ObjectHash.SHA1, mirror.objectHash())
    }

    @Test
    fun `refs round-trip through packed-refs`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA256)
        mirror.writeRefs(listOf(ref("refs/heads/main", a), ref("refs/tags/v1", b)))

        assertEquals(mapOf("refs/heads/main" to a, "refs/tags/v1" to b), mirror.localRefs())
        assertEquals(listOf("refs/heads/main", "refs/tags/v1"), mirror.refNames())
    }

    @Test
    fun `a ref deleted upstream is removed locally`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA256)
        mirror.writeRefs(listOf(ref("refs/heads/main", a), ref("refs/heads/old", b)))
        mirror.writeRefs(listOf(ref("refs/heads/main", a)))

        assertEquals(listOf("refs/heads/main"), mirror.refNames())
    }

    @Test
    fun `HEAD is not written as a ref but as a symbolic pointer`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA256)
        mirror.writeRefs(
            listOf(
                RemoteRef("HEAD", a, symrefTarget = "refs/heads/trunk"),
                ref("refs/heads/trunk", a),
            )
        )

        assertEquals("ref: refs/heads/trunk", File(mirror.gitDir, "HEAD").readText().trim())
        assertFalse("HEAD is not a ref entry", mirror.refNames().contains("HEAD"))
    }

    @Test
    fun `loose refs written by another engine are read too`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA1)
        // JGit-created mirrors may hold refs loose rather than packed.
        File(mirror.gitDir, "refs/heads").mkdirs()
        File(mirror.gitDir, "refs/heads/legacy").writeText("${"c".repeat(40)}\n")

        assertTrue(mirror.localRefs().containsKey("refs/heads/legacy"))
    }

    /**
     * Git resolves loose over packed. A JGit clone writes `packed-refs` once and
     * later fetches update refs loose, so the packed entry is the *stale* one —
     * preferring it would offer outdated haves on every incremental sync.
     */
    @Test
    fun `a loose ref shadows its packed entry, as git itself resolves them`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA1)
        File(mirror.gitDir, "packed-refs")
            .writeText("${"a".repeat(40)} refs/heads/main\n")
        File(mirror.gitDir, "refs/heads").mkdirs()
        File(mirror.gitDir, "refs/heads/main").writeText("${"b".repeat(40)}\n")

        assertEquals("b".repeat(40), mirror.localRefs()["refs/heads/main"])
    }
}
