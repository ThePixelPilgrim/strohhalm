package de.nereide.strohhalm.domain

/**
 * Keeps the process alive and unthrottled while a sync runs.
 *
 * Android suspends cached processes and restricts their network access. A
 * mirror of a large repository can run for many minutes, so an ordinary
 * background coroutine gets frozen the moment the user switches away — the
 * socket stalls, the server drops it, and the sync dies with an unexplained end
 * of stream.
 *
 * Implemented on the Android side by a foreground service; kept as an interface
 * so the domain layer stays free of framework types and testable.
 */
interface ForegroundHold {
    fun acquire()
    fun release()
}

/** Used in tests and wherever no process priority is needed. */
object NoForegroundHold : ForegroundHold {
    override fun acquire() = Unit
    override fun release() = Unit
}
