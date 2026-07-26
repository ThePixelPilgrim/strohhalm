package de.nereide.strohhalm.ui.detail

import java.util.concurrent.atomic.AtomicLong

/**
 * Decides whether a progress callback still speaks for the current share.
 *
 * Stop cancels the packing coroutine, but the thread doing the work is inside
 * a blocking read of a regular file, and those ignore the interrupt flag: the
 * archiver finishes the entry it is on and reports progress before its next
 * cancellation check ever runs. That late report used to overwrite the state
 * the Stop had just cleared, so the card vanished and came straight back as
 * "Packing… n of m" — frozen, because the job behind it was already dead.
 *
 * A generation counter is enough to make that harmless. Every pack takes a
 * token; a report is written only while its token is still the current one;
 * stopping, or starting another pack, moves the counter on and every straggler
 * from the old generation is dropped. Nothing has to wait for a thread to
 * notice it was cancelled.
 *
 * [AtomicLong] because the reports arrive on the packing thread while
 * [cancel] runs on the main thread.
 */
class ShareProgressGate {

    private val generation = AtomicLong(NONE)

    /** Starts a new generation, superseding any before it. */
    fun begin(): Long = generation.incrementAndGet()

    /**
     * [NONE] is checked explicitly rather than left to the comparison: the
     * counter also sits at [NONE] before the first pack, and a default-valued
     * token must not be mistaken for the live generation.
     */
    fun accepts(token: Long): Boolean = token != NONE && generation.get() == token

    /** Invalidates the current generation without starting one. */
    fun cancel() {
        generation.incrementAndGet()
    }

    private companion object {
        /** Not a generation. [begin] only ever issues values above it. */
        const val NONE = 0L
    }
}
