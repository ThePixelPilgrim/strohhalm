package de.nereide.strohhalm.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard that keeps a stopped pack from redrawing its own progress.
 *
 * All of this is pure counting, which is the point: the real race is between a
 * blocking file read on one thread and a button on another, and neither is
 * needed to pin what the gate must decide.
 */
class ShareProgressGateTest {

    @Test
    fun `a token from the current generation is accepted`() {
        val gate = ShareProgressGate()
        val token = gate.begin()
        assertTrue(gate.accepts(token))
    }

    /** The Stop case: the packing thread reports one more entry afterwards. */
    @Test
    fun `a cancelled generation refuses its own token`() {
        val gate = ShareProgressGate()
        val token = gate.begin()
        gate.cancel()
        assertFalse("a stopped pack must not redraw itself", gate.accepts(token))
    }

    /** Share tapped again: the old pack is still unwinding and must stay quiet. */
    @Test
    fun `a superseded generation is refused while the new one is accepted`() {
        val gate = ShareProgressGate()
        val old = gate.begin()
        val new = gate.begin()
        assertFalse("the superseded pack must not redraw itself", gate.accepts(old))
        assertTrue(gate.accepts(new))
    }

    @Test
    fun `a token that was never issued is refused`() {
        val gate = ShareProgressGate()
        assertFalse(gate.accepts(0L))
        gate.begin()
        assertFalse(gate.accepts(0L))
    }

    /** Stopping twice, or stopping when nothing is packing, is not special. */
    @Test
    fun `a generation begun after a cancel is accepted`() {
        val gate = ShareProgressGate()
        gate.cancel()
        val token = gate.begin()
        assertTrue(gate.accepts(token))
    }
}
