package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Real `git` and real `sha256sum` validating our output. Everything else in
 * this package tests our reader against our writer; only this proves that what
 * a recipient gets is usable with ordinary tools.
 */
class ArchiveEndToEndTest {

    @get:Rule val temp = TemporaryFolder()

    private fun run(vararg command: String, cwd: File): Pair<Int, String> {
        val process = ProcessBuilder(*command)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private fun toolsPresent(): Boolean = runCatching {
        run("git", "--version", cwd = temp.root).first == 0 &&
            run("sha256sum", "--version", cwd = temp.root).first == 0
    }.getOrDefault(false)

    /** Builds a real bare mirror with the system git. */
    private fun bareMirror(objectFormat: String): File {
        val work = temp.newFolder("work-$objectFormat")
        assertEquals(0, run("git", "init", "--object-format=$objectFormat", "-q", ".", cwd = work).first)
        File(work, "a.txt").writeText("hello\n")
        run("git", "add", "a.txt", cwd = work)
        run("git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "first", cwd = work)
        run("git", "tag", "v1", cwd = work)

        val bare = File(temp.root, "mirror-$objectFormat.git")
        assertEquals(0, run("git", "clone", "-q", "--mirror", work.absolutePath, bare.absolutePath, cwd = temp.root).first)
        return bare
    }

    @Test
    fun `a sha256 mirror survives packing, unpacking, fsck and clone`() {
        assumeTrue("needs git and sha256sum", toolsPresent())
        assumeTrue(
            "needs a git with sha256 support",
            run("git", "init", "--object-format=sha256", "-q", "probe", cwd = temp.root).first == 0,
        )

        val bare = bareMirror("sha256")
        val cache = temp.newFolder("archives")
        val store = ArchiveStore(cache)
        val fingerprint = RefFingerprint.of(
            de.nereide.strohhalm.domain.git.MirrorRepository(bare).localRefs()
        )

        val archive = store.build("mirror-sha256", bare, fingerprint, 1_785_060_000_000L, null)

        // The sidecar must be checkable by the tool it imitates.
        val (checkCode, checkOut) = run("sha256sum", "-c", ArchiveNames.sidecar(archive.name), cwd = cache)
        assertEquals("sha256sum -c said:\n$checkOut", 0, checkCode)

        val out = temp.newFolder("unpacked")
        assertEquals(0, run("unzip", "-q", archive.absolutePath, cwd = out).first)
        val restored = File(out, bare.name)
        assertTrue("expected a single top-level ${bare.name}", restored.isDirectory)

        val (fsckCode, fsckOut) = run("git", "fsck", "--strict", "--full", cwd = restored)
        assertEquals("git fsck said:\n$fsckOut", 0, fsckCode)

        val clone = File(temp.root, "clone")
        assertEquals(0, run("git", "clone", "-q", restored.absolutePath, clone.absolutePath, cwd = temp.root).first)
        assertEquals("hello\n", File(clone, "a.txt").readText())
    }

    /** The Share anyway claim: a mirror left by a failed fetch is still valid. */
    @Test
    fun `a sha1 mirror packs and restores just as well`() {
        assumeTrue("needs git and sha256sum", toolsPresent())

        val bare = bareMirror("sha1")
        val store = ArchiveStore(temp.newFolder("archives-sha1"))
        val fingerprint = RefFingerprint.of(
            de.nereide.strohhalm.domain.git.MirrorRepository(bare).localRefs()
        )
        val archive = store.build("mirror-sha1", bare, fingerprint, 1_785_060_000_000L, null)

        val out = temp.newFolder("unpacked-sha1")
        assertEquals(0, run("unzip", "-q", archive.absolutePath, cwd = out).first)
        val restored = File(out, bare.name)
        assertEquals(0, run("git", "fsck", "--strict", "--full", cwd = restored).first)
    }
}
