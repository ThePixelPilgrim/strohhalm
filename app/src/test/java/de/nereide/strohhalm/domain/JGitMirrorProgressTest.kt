package de.nereide.strohhalm.domain

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Proves the progress callback is actually wired through JGit's ProgressMonitor.
 *
 * Written because a sync sat for minutes showing only the placeholder label, and
 * "it has not started receiving" and "it is receiving but never reports" look
 * identical from the outside. A local file:// clone exercises the same
 * CloneCommand -> FetchCommand -> ProgressMonitor path as a real one.
 */
class JGitMirrorProgressTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var origin: File
    private lateinit var mirror: JGitMirror

    @Before
    fun setUp() {
        AndroidSystemReader.install()
        mirror = JGitMirror(keyPairProvider = { error("file:// needs no key") })
        origin = tmp.newFolder("origin")

        Git.init().setDirectory(origin).call().use { git ->
            repeat(20) { i ->
                File(origin, "file$i.txt").writeText("content $i".repeat(200))
                git.add().addFilepattern(".").call()
                git.commit().setMessage("commit $i").setSign(false).call()
            }
            git.tag().setName("v1").setAnnotated(false).call()
        }
    }

    @Test
    fun `sync reports progress tasks to the callback`() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()

        val outcome = mirror.sync(
            remoteUrl = origin.toURI().toString(),
            destination = File(tmp.root, "mirror.git"),
            pinnedFingerprint = null,
            progress = { task, _, _ -> seen.add(task) }
        )

        assertTrue("sync failed: $outcome", outcome is MirrorOutcome.Success)
        assertTrue(
            "no progress was reported at all — the monitor is not wired through",
            seen.isNotEmpty()
        )
        println("PROGRESS TASKS: ${seen.distinct()}")
    }
}
