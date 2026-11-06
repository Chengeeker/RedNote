package com.red.note

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.*

// Same duration/bounce conversion and anchored stretch as the retained glass renderer.
internal fun glassSpring(milliseconds: Int, bounce: Float): SpringSpec<Float> =
    spring(
        dampingRatio = if (bounce > 0) 1 - bounce else 1 / (1 + bounce),
        stiffness = (4 * PI * PI / (milliseconds / 1000.0).pow(2)).toFloat(),
    )

internal fun anchoredStretch(x: Float, y: Float): FloatArray {
    val resistance = 1 + hypot(x, y) * .01f
    val dx = x / resistance * .8f
    val dy = y / resistance * .8f
    val rx = abs(dx) / 48
    val ry = abs(dy) / 48
    fun pivot(v: Float): Float {
        val t = (abs(v) / 20).coerceIn(0f, 1f)
        return .5f - sign(v) * (t * t * (3 - 2 * t)) * .5f
    }
    return floatArrayOf(
        (1 + (rx * .8f).coerceAtMost(.3f)) * (1 - (ry * .3f).coerceAtMost(.15f)),
        (1 + (ry * .8f).coerceAtMost(.3f)) * (1 - (rx * .3f).coerceAtMost(.15f)),
        pivot(dx),
        pivot(dy),
        dx * .15f,
        dy * .15f,
    )
}

internal fun jellyScale(velocity: Float, pressure: Float = 1f): FloatArray {
    val distortion =
        if (velocity.isFinite()) (abs(velocity) / 10).coerceIn(0f, 1f) * .8f * .7f * pressure
        else 0f
    return floatArrayOf(1 - distortion * .5f, 1 + distortion * .3f)
}
