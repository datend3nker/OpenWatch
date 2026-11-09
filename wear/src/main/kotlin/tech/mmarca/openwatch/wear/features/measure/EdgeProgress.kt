package tech.mmarca.openwatch.wear.features.measure

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A thin arc along the edge of the round screen that fills clockwise from the top as [progress]
 * goes from 0 to 1, on a dim track. Drawn here rather than with the stock indicator: that one
 * showed the track and no filled part on the watch.
 */
@Composable
internal fun EdgeProgress(progress: Float, color: Color, trackColor: Color, modifier: Modifier = Modifier) {
    // The measurement reports its progress every 200 ms; easing over the same time keeps the arc moving.
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(durationMillis = 200, easing = LinearEasing), label = "edgeProgress")
    Canvas(modifier = modifier) {
        val stroke = STROKE_DP.dp.toPx()
        val inset = stroke / 2 + INSET_DP.dp.toPx()
        val arc = Size(size.width - 2 * inset, size.height - 2 * inset)
        val topLeft = Offset(inset, inset)
        drawArc(trackColor, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke))
        if (shown > 0f) {
            drawArc(
                color,
                startAngle = START_ANGLE,
                sweepAngle = 360f * shown,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

private const val STROKE_DP = 6
private const val INSET_DP = 3

/** Straight up. */
private const val START_ANGLE = -90f
