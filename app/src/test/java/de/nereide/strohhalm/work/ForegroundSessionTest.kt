package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.SyncProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundSessionTest {

    private val calls = mutableListOf<String>()
    private val session = ForegroundSession(object : ForegroundSession.Display {
        override fun show(progress: SyncProgress) {
            calls += "show:${progress.task}"
        }

        override fun finish() {
            calls += "finish"
        }
    })

    private fun progress(task: String) =
        SyncProgress(repoId = 1, repoName = "repo", task = task, completed = 0, total = 0)

    @Test
    fun `shows the stage of the repository in flight`() {
        session.onProgress(progress("Receiving"))
        assertEquals(listOf("show:Receiving"), calls)
    }

    @Test
    fun `finishes when progress clears instead of showing a placeholder`() {
        session.onProgress(progress("Receiving"))
        session.onProgress(null)
        assertEquals(listOf("show:Receiving", "finish"), calls)
    }

    @Test
    fun `finishes at once when the sync already ended before the service came up`() {
        session.onProgress(null)
        assertEquals(listOf("finish"), calls)
    }

    @Test
    fun `never posts again after finishing`() {
        session.onProgress(null)
        session.onProgress(progress("Receiving"))
        session.onProgress(null)
        assertEquals(listOf("finish"), calls)
    }

    @Test
    fun `a stop request with nothing running finishes rather than lingering`() {
        session.onStopRequested(running = false)
        assertEquals(listOf("finish"), calls)
    }

    @Test
    fun `a stop request during a sync leaves the notification to the sync's own end`() {
        session.onProgress(progress("Receiving"))
        session.onStopRequested(running = true)
        assertEquals(listOf("show:Receiving"), calls)
    }
}
