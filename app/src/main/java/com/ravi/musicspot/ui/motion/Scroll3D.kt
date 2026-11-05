package com.ravi.musicspot.ui.motion

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import kotlin.math.sign

/**
 * v2.0.7 3D scroll: rows tilt back like the face of a drum as they move away from the list's focus line, and
 * shrink / fade a little. Only on lists where it reads well (lyrics, queue, track lists, search results); never on
 * settings, carousels or grids. Everything is read in the draw phase, so scrolling never recomposes. Off with
 * Reduce motion.
 *
 * @param focus where in the viewport rows sit flat (0 = top, 0.5 = centre, used by lyrics).
 * @param maxTilt rotationX in degrees at the edge of the viewport.
 */
@Composable
fun Modifier.scroll3d(
    state: LazyListState,
    index: Int,
    focus: Float = 0.5f,
    maxTilt: Float = 28f,
    minScale: Float = 0.86f,
    minAlpha: Float = 0.45f,
    /** Share of each half of the viewport that stays flat (track lists tilt only near the edges). */
    flat: Float = 0f,
): Modifier {
    if (reducedMotion()) return this
    return this.graphicsLayer {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
        val vpStart = info.viewportStartOffset
        val vpEnd = info.viewportEndOffset
        val vpH = (vpEnd - vpStart).toFloat().coerceAtLeast(1f)
        val center = item.offset + item.size / 2f
        val focusY = vpStart + vpH * focus
        // -1 at the top edge … 0 at the focus line … +1 at the bottom edge (clamped a little past).
        val half = if (center < focusY) (focusY - vpStart) else (vpEnd - focusY)
        val d = ((center - focusY) / half.coerceAtLeast(1f)).coerceIn(-1.2f, 1.2f)
        val k = ((abs(d) - flat) / (1f - flat)).coerceAtLeast(0f)
        val eased = k * k * (3f - 2f * k.coerceAtMost(1f))
        rotationX = -sign(d) * maxTilt * eased.coerceAtMost(1f)
        val s = 1f - (1f - minScale) * eased.coerceAtMost(1f)
        scaleX = s; scaleY = s
        alpha = 1f - (1f - minAlpha) * eased.coerceAtMost(1f)
        cameraDistance = 14f * density
        // Pivot on the edge nearer the focus line so rows hinge toward the centre, like a wheel.
        transformOrigin = TransformOrigin(0.5f, if (d < 0) 1f else 0f)
    }
}
