package de.nereide.strohhalm.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maths behind the calm indeterminate bar. The composable itself is a
 * device concern; what is testable is the geometry — where the segment sits at
 * each point of the cycle — which is also where a mistake would show as a bar
 * jumping or leaking outside its track.
 */
class IndeterminateSweepTest {

    @Test
    fun `the cycle is half the speed of the stock indicator`() {
        // Material 3 animates its indeterminate linear indicator on an 1800 ms
        // cycle; the whole point of this class is to halve that.
        assertEquals(3_600, IndeterminateSweep.CYCLE_MILLIS)
    }

    @Test
    fun `the segment starts empty at the left edge`() {
        val (start, end) = IndeterminateSweep.segment(0f)
        assertEquals(0f, start, 1e-6f)
        assertEquals(0f, end, 1e-6f)
    }

    @Test
    fun `the segment ends empty at the right edge`() {
        val (start, end) = IndeterminateSweep.segment(1f)
        assertEquals(1f, start, 1e-6f)
        assertEquals(1f, end, 1e-6f)
    }

    @Test
    fun `mid-cycle the segment is fully visible at its travel width`() {
        val (start, end) = IndeterminateSweep.segment(0.5f)
        assertEquals(IndeterminateSweep.WIDTH, end - start, 1e-6f)
        assertTrue("segment should sit mid-track", start > 0f && end < 1f)
    }

    @Test
    fun `the segment never leaves the track and never inverts`() {
        var t = 0f
        while (t <= 1f) {
            val (start, end) = IndeterminateSweep.segment(t)
            assertTrue("start in range at t=$t", start in 0f..1f)
            assertTrue("end in range at t=$t", end in 0f..1f)
            assertTrue("start <= end at t=$t", start <= end)
            t += 0.01f
        }
    }

    @Test
    fun `the sweep only ever moves right`() {
        var previousStart = -1f
        var previousEnd = -1f
        var t = 0f
        while (t <= 1f) {
            val (start, end) = IndeterminateSweep.segment(t)
            assertTrue("start monotonic at t=$t", start >= previousStart)
            assertTrue("end monotonic at t=$t", end >= previousEnd)
            previousStart = start
            previousEnd = end
            t += 0.01f
        }
    }
}
