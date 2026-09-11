package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.SyncProgress

/**
 * The lifecycle rules of the sync notification, kept free of framework types so
 * they can be pinned on the JVM.
 *
 * The one invariant that matters: once the session has finished, nothing is
 * ever posted again. A notification posted after the service has begun to stop
 * outlives it as an orphan the user cannot dismiss — the end of a sync clears
 * progress and stops the service back to back, and a placeholder posted for
 * the cleared progress used to race the system's own removal and, when it
 * landed last, stayed on screen for good.
 */
class ForegroundSession(private val display: Display) {

    interface Display {
        fun show(progress: SyncProgress)

        /** Remove the notification and stop; called at most once. */
        fun finish()
    }

    private var finished = false

    fun onProgress(progress: SyncProgress?) {
        if (finished) return
        if (progress == null) finish() else display.show(progress)
    }

    /**
     * The notification's Stop action. With a sync in flight the runner ends
     * it and the cleared progress finishes the session; without one the
     * action has merely revived the service, which must not stay up.
     */
    fun onStopRequested(running: Boolean) {
        if (!running) finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        display.finish()
    }
}
