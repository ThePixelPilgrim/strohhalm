package de.nereide.strohhalm.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * Where the sweeping segment sits at each point of the cycle.
 *
 * Kept free of Compose so the geometry is a plain JVM concern: the segment
 * enters from the left, crosses at constant speed, and leaves on the right —
 * one movement per cycle, nothing chasing anything.
 */
object IndeterminateSweep {

    /**
     * Material 3 runs its indeterminate linear indicator on an 1800 ms cycle of
     * two chasing segments, which reads as urgency. This bar exists to say
     * "working, be patient", so it takes twice as long and moves once.
     */
    const val CYCLE_MILLIS = 3_600

    /** The segment's length as a fraction of the track. */
    const val WIDTH = 0.3f

    /**
     * Start and end of the visible segment, both in 0..1 of the track width,
     * for [t] in 0..1 of the cycle. The head travels from 0 to 1 + [WIDTH] so
     * the tail has fully left the track when the cycle wraps.
     */
    fun segment(t: Float): Pair<Float, Float> {
        val head = t * (1f + WIDTH)
        val start = (head - WIDTH).coerceIn(0f, 1f)
        val end = head.coerceIn(0f, 1f)
        return start to end
    }
}

/**
 * The indeterminate bar for phases with no measurable progress.
 *
 * A drop-in for `LinearProgressIndicator()` in its indeterminate form, drawn at
 * the same height and in the same colours, but calmer: half the pace, and a
 * single segment instead of two chasing each other.
 */
@Composable
fun CalmIndeterminateBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "indeterminate-sweep")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(IndeterminateSweep.CYCLE_MILLIS, easing = LinearEasing)
        ),
        label = "sweep-position",
    )
    val color = ProgressIndicatorDefaults.linearColor
    val track = ProgressIndicatorDefaults.linearTrackColor
    Canvas(modifier.height(4.dp)) {
        val y = size.height / 2f
        drawLine(track, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
        val (start, end) = IndeterminateSweep.segment(t)
        if (end > start) {
            drawLine(
                color,
                Offset(start * size.width, y),
                Offset(end * size.width, y),
                size.height,
                StrokeCap.Round,
            )
        }
    }
}
