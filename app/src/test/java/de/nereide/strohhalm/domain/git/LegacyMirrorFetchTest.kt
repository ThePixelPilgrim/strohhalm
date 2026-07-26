package de.nereide.strohhalm.domain.git

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A mirror created by the old JGit engine must be readable by the new one.
 *
 * The risk is not the objects — those are ordinary — but the refs: JGit may
 * leave them loose rather than packed, and an engine that read only `packed-refs`
 * would offer no haves and silently re-download everything on every sync.
 */
class LegacyMirrorFetchTest {

    @get:Rule val temp = TemporaryFolder()

    private fun jgitCreatedMirror(): File {
        val source = temp.newFolder("source")
        Git.init().setDirectory(source).call().use { git ->
            File(source, "a.txt").writeText("hello\n")
            git.add().addFilepattern("a.txt").call()
            git.commit().setMessage("first").setSign(false).call()
        }
        val mirror = File(temp.root, "legacy.git")
        Git.cloneRepository()
            .setURI(source.toURI().toString())
            .setDirectory(mirror)
            .setBare(true)
            .setMirror(true)
            .call()
            .close()
        return mirror
    }

    @Test
    fun `refs written by JGit are visible to MirrorRepository`() {
        val mirror = MirrorRepository(jgitCreatedMirror())

        assertTrue("recognised as an existing mirror", mirror.exists())
        assertEquals(ObjectHash.SHA1, mirror.objectHash())
        assertTrue(
            "a JGit-created mirror's refs must be readable: ${mirror.refNames()}",
            mirror.refNames().any { it.startsWith("refs/heads/") },
        )
        assertTrue("every ref has an id", mirror.localRefs().values.all { it.length == 40 })
    }
}
