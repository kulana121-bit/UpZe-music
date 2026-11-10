@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.ravi.musicspot.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * v2.0.2 motion kit. Everything here is plain Compose (no API 31+ platform calls), so it is Android 8–10 safe, and
 * every effect collapses to "no animation" when reducedMotion() is true (app setting, system "Remove animations",
 * or a low-RAM device).
 */

// ---- shared element (cover art list → album/playlist/artist page) -------------------------------------------------

/** Provided by AppRoot around the page AnimatedContent; null while not inside a page transition (e.g. sheets). */
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalPageScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks cover art with [key] ("cover:<collection id>") so it flies between the card and the page header: a
 * container-transform-like bounds morph on MotionSpec.containerSpring, while the rest of the page does its shared-axis
 * or fade-through transition around it. Off with Reduce motion.
 */
@Composable
fun Modifier.sharedCover(key: String?): Modifier {
    if (key == null || reducedMotion()) return this
    val shared = LocalSharedScope.current ?: return this
    val page = LocalPageScope.current ?: return this
    return with(shared) {
        this@sharedCover.sharedElement(
            rememberSharedContentState(key),
            animatedVisibilityScope = page,
            boundsTransform = { _, _ -> MotionSpec.containerSpring() },
        )
    }
}

// ---- play/pause morph --------------------------------------------------------------------------------------------

/**
 * Play ⇄ pause as a real shape morph (two bars fold into the triangle's halves), drawn on a 24-unit grid.
 * Respects Reduce motion (snaps). Announces "Play"/"Pause" to TalkBack.
 */
// 1.0.5: vertex tables hoisted out of the draw lambda (were rebuilt as Lists of Pairs on every draw). Per vertex:
// play x, play y, pause x, pause y on the 24-unit grid; same values and order as before.
private val PP_LEFT = floatArrayOf(8f, 5f, 6f, 5f, 13.5f, 8.14f, 10f, 5f, 13.5f, 15.86f, 10f, 19f, 8f, 19f, 6f, 19f)
private val PP_RIGHT = floatArrayOf(13.5f, 8.14f, 14f, 5f, 19f, 12f, 18f, 5f, 19f, 12f, 18f, 19f, 13.5f, 15.86f, 14f, 19f)

/** 1.0.5: rebuilds [path] in place (rewind, no allocation) as the quad between the play and pause shapes at [p]. */
private fun ppQuad(path: Path, pts: FloatArray, p: Float, ox: Float, oy: Float, s: Float) {
    path.rewind()
    var i = 0
    while (i < pts.size) {
        val x = ox + (pts[i] + (pts[i + 2] - pts[i]) * p) * s
        val y = oy + (pts[i + 1] + (pts[i + 3] - pts[i + 1]) * p) * s
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        i += 4
    }
    path.close()
}

/**
 * Play ⇄ pause as a real shape morph (two bars fold into the triangle's halves), drawn on a 24-unit grid.
 * Respects Reduce motion (snaps). Announces "Play"/"Pause" to TalkBack.
 */
@Composable
fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier.size(24.dp), tint: Color = LocalContentColor.current) {
    val still = reducedMotion()
    val p by animateFloatAsState(if (playing) 1f else 0f, if (still) tween(0) else spring(dampingRatio = 0.8f, stiffness = 357f), label = "pp")
    // 1.0.5: Canvas(...) is Spacer(drawBehind); drawWithCache keeps two Paths per icon and rewinds them each draw instead
    // of allocating two Paths + two vertex lists per frame of the morph. p is still read only while drawing.
    Spacer(modifier.semantics { contentDescription = if (playing) "Pause" else "Play" }.drawWithCache {
        val left = Path(); val right = Path()
        onDrawBehind {
            val s = size.minDimension / 24f
            val ox = (size.width - 24f * s) / 2f; val oy = (size.height - 24f * s) / 2f
            val pv = p
            ppQuad(left, PP_LEFT, pv, ox, oy, s); ppQuad(right, PP_RIGHT, pv, ox, oy, s)
            drawPath(left, tint); drawPath(right, tint)
        }
    })
}

// ---- shimmer skeletons -------------------------------------------------------------------------------------------

@Composable
fun Modifier.shimmer(shape: Shape = MaterialTheme.shapes.medium): Modifier {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    if (reducedMotion()) return this.clip(shape).background(base)
    val t = rememberInfiniteTransition(label = "shimmer")
    // 1.0.5: x was read here (composition), recomposing every skeleton's parent each frame and allocating a new
    // gradient per frame. Now x is read only while drawing; the gradient is built once (start at the origin) and the
    // canvas is translated by x * 600, which puts it at exactly start = (x*600, 0), end = (x*600 + 400, 240) as before.
    val x = t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1820, easing = LinearEasing), RepeatMode.Restart), label = "sx")
    val brush = remember(base, hi) { Brush.linearGradient(listOf(base, hi, base), start = Offset.Zero, end = Offset(400f, 240f)) }
    return this.clip(shape).drawBehind {
        val dx = x.value * 600f
        translate(left = dx) { drawRect(brush, topLeft = Offset(-dx, 0f), size = size) }
    }
}

@Composable
fun SkeletonBox(width: Dp, height: Dp, shape: Shape = MaterialTheme.shapes.medium) = Box(Modifier.size(width, height).shimmer(shape))

// ---- staggered enter ---------------------------------------------------------------------------------------------

/**
 * One screen load's stagger window (v2.0.4). Its clock starts when the first item asks; items that first appear more
 * than [WINDOW_MS] later (scrolled into view, paged in) show at once, so only the initial load cascades.
 */
class StaggerGroup {
    private var startMs = 0L
    fun admit(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (startMs == 0L) startMs = now
        return now - startMs < WINDOW_MS
    }
    companion object { const val WINDOW_MS = 700L }
}

/** Provided per page by AppRoot (and per query by Search). */
val LocalStaggerGroup = staticCompositionLocalOf<StaggerGroup?> { null }

/** Items fade/slide up in sequence the first time a screen loads (index-capped so long lists don't wait). */
fun Modifier.staggerIn(index: Int, enabled: Boolean = true): Modifier = composed {
    val still = reducedMotion()
    val group = LocalStaggerGroup.current
    val play = remember { !still && enabled && (group?.admit() ?: true) }
    if (!play) return@composed this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceIn(0, 8) * 25L)) // 1.0.8: was 40 ms apart
        a.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) // 1.0.8: was 476 ms
    }
    this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 16.dp.toPx() }
}

/**
 * Loading → content → error as a short crossfade (v2.0.4). Keyed by the state's class, so a refreshed Ok does not
 * fade. Instant with Reduce motion.
 */
@Composable
fun <S> StateCrossfade(state: S, modifier: Modifier = Modifier, key: (S) -> Any? = { it?.let { v -> v::class } }, content: @Composable (S) -> Unit) {
    val still = reducedMotion()
    androidx.compose.animation.AnimatedContent(
        targetState = state, modifier = modifier, label = "state", contentKey = key,
        transitionSpec = {
            if (still) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            else androidx.compose.animation.fadeIn(tween(200, delayMillis = 50)) togetherWith androidx.compose.animation.fadeOut(tween(110))
        },
    ) { content(it) }
}
