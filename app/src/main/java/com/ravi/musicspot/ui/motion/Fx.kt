@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.ravi.musicspot.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
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
@Composable
fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier.size(24.dp), tint: Color = LocalContentColor.current) {
    val still = reducedMotion()
    val p by animateFloatAsState(if (playing) 1f else 0f, if (still) tween(0) else spring(dampingRatio = 0.8f, stiffness = 357f), label = "pp")
    Canvas(modifier.semantics { contentDescription = if (playing) "Pause" else "Play" }) {
        val s = size.minDimension / 24f
        val ox = (size.width - 24f * s) / 2f; val oy = (size.height - 24f * s) / 2f
        fun l(a: Float, b: Float) = a + (b - a) * p
        fun quad(pts: List<Pair<Offset, Offset>>) = Path().apply {
            pts.forEachIndexed { i, (play, pause) ->
                val x = ox + l(play.x, pause.x) * s; val y = oy + l(play.y, pause.y) * s
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        // (play shape vertex, pause shape vertex)
        val left = listOf(Offset(8f, 5f) to Offset(6f, 5f), Offset(13.5f, 8.14f) to Offset(10f, 5f), Offset(13.5f, 15.86f) to Offset(10f, 19f), Offset(8f, 19f) to Offset(6f, 19f))
        val right = listOf(Offset(13.5f, 8.14f) to Offset(14f, 5f), Offset(19f, 12f) to Offset(18f, 5f), Offset(19f, 12f) to Offset(18f, 19f), Offset(13.5f, 15.86f) to Offset(14f, 19f))
        drawPath(quad(left), tint); drawPath(quad(right), tint)
    }
}

// ---- shimmer skeletons -------------------------------------------------------------------------------------------

@Composable
fun Modifier.shimmer(shape: Shape = MaterialTheme.shapes.medium): Modifier {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    if (reducedMotion()) return this.clip(shape).background(base)
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1820, easing = LinearEasing), RepeatMode.Restart), label = "sx")
    return this.clip(shape).background(Brush.linearGradient(listOf(base, hi, base), start = Offset(x * 600f, 0f), end = Offset(x * 600f + 400f, 240f)))
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
        delay((index.coerceIn(0, 8) * 40L))
        a.animateTo(1f, tween(476, easing = FastOutSlowInEasing))
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
            else androidx.compose.animation.fadeIn(tween(308, delayMillis = 84)) togetherWith androidx.compose.animation.fadeOut(tween(168))
        },
    ) { content(it) }
}

// ---- chips -------------------------------------------------------------------------------------------------------

/** Animated chip colours (selected fill glides in; content colour always readable on it). */
@Composable
fun chipColors(selected: Boolean): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    val still = reducedMotion()
    val bg by animateColorAsState(if (selected) c.primary else c.surfaceContainerHigh, if (still) tween(0) else tween(308), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.onPrimary else c.onSurface, if (still) tween(0) else tween(308), label = "chipFg")
    return bg to fg
}
