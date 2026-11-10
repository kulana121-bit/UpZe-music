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
/** 1.0.1: shared flat-band sizes for [scroll3d]: rows that stay full size around the centre. */
object Depth {
    /** Carousels, Home rows and general lists: the centre ~3-4 items are flat. */
    const val FLAT_LIST = 3.5f
    /** Playlists, albums, track lists and the queue: the centre ~6-7 rows are flat. */
    const val FLAT_TRACKS = 6.5f
}

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
    /**
     * 1.0.1: number of rows around the focus line that always stay flat (full size, no tilt, no fade), measured in
     * this row's own height so the band is the same on a phone and an 8" tablet. 0 = use [flat] only (the lyrics drum).
     * See [Depth] for the shared values.
     */
    flatRows: Float = 0f,
    /** 1.0.1: lists turn the effect off on lite-effects devices ([com.ravi.musicspot.Graph.liteFx]); lyrics keep it. */
    allowOnLite: Boolean = false,
    /**
     * 1.0.8: how much of the effect rows BELOW the focus line get (1 = same as above, 0 = flat). The queue uses a
     * small value so the upcoming songs stay readable instead of folding away into the drum.
     */
    below: Float = 1f,
): Modifier {
    if (reducedMotion()) return this
    if (!allowOnLite && com.ravi.musicspot.Graph.liteFx) return this
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
        // 1.0.1: widen the flat band to at least `flatRows` rows (half on each side of the focus line).
        val eased = depthEase(d, flat, flatBandHalf(flatRows, item.size.toFloat(), half)) * (if (d > 0f) below.coerceIn(0f, 1f) else 1f)
        if (eased <= 0.0005f) { rotationX = 0f; scaleX = 1f; scaleY = 1f; alpha = 1f; return@graphicsLayer } // 1.0.8: flat rows skip the 3D setup
        rotationX = -sign(d) * maxTilt * eased.coerceAtMost(1f)
        val s = 1f - (1f - minScale) * eased.coerceAtMost(1f)
        scaleX = s; scaleY = s
        alpha = 1f - (1f - minAlpha) * eased.coerceAtMost(1f)
        cameraDistance = 14f * density
        // Pivot on the edge nearer the focus line so rows hinge toward the centre, like a wheel.
        transformOrigin = TransformOrigin(0.5f, if (d < 0) 1f else 0f)
    }
}

/** 1.0.1: half of the flat band as a share of the half-viewport, for [flatRows] rows of [rowPx]; 0 when unused. */
internal fun flatBandHalf(flatRows: Float, rowPx: Float, halfViewportPx: Float): Float =
    if (flatRows > 0f) ((flatRows * rowPx / 2f) / halfViewportPx.coerceAtLeast(1f)).coerceIn(0f, 0.92f) else 0f

/**
 * Depth amount for a row at normalised distance [d] from the focus line: 0 inside the flat band (max of [flat] and
 * [bandHalf]), easing (smoothstep) to 1 at the viewport edge. With bandHalf = 0 this is the v2.0.7 curve exactly
 * (the lyrics drum is unchanged).
 */
internal fun depthEase(d: Float, flat: Float, bandHalf: Float): Float {
    val f = maxOf(flat, bandHalf)
    val k = ((abs(d) - f) / (1f - f)).coerceAtLeast(0f)
    return k * k * (3f - 2f * k.coerceAtMost(1f))
}
