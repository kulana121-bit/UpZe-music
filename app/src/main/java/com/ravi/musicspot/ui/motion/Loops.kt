// v2.0.14: unconditional looping phase so callers never call rememberInfiniteTransition inside an `if`.
// 1.0.5: smart frame pacing for ambient loops on lite devices (see [rememberLoopPhase] and DeviceTier.ambientFrameIntervalNanos).
package com.ravi.musicspot.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.ravi.musicspot.logic.DeviceTier

/**
 * v2.0.14: 0..1 loop while [running]; holds [rest] when stopped. [reverse] ping-pongs, otherwise restarts at 0.
 *
 * 1.0.5: callers should read the returned State's `.value` only inside draw / layer lambdas (graphicsLayer {},
 * drawBehind {}, Canvas) so a running loop never recomposes them.
 *
 * 1.0.5: [ambient] loops (decorative motion nobody touches: drifts, breathing, reels, buffering pulses) are paced on
 * lite-effects devices (Graph.liteFx): the phase is computed from the elapsed frame time, so speed, easing and shape
 * are exactly the same, but it is published at most ~30 times a second (frames closer than ~33 ms are skipped). On
 * every other device, and for non-ambient loops, it is the original Animatable/tween loop, unchanged.
 */
@Composable
fun rememberLoopPhase(
    running: Boolean,
    periodMs: Int,
    reverse: Boolean = true,
    rest: Float = 0f,
    easing: Easing = LinearEasing,
    ambient: Boolean = true, // 1.0.5: new, defaulted, so every existing call compiles unchanged
): State<Float> {
    val anim = remember { Animatable(rest) }
    // 1.0.5: Graph.liteFx is fixed at startup, so this never flips while composed.
    val frameInterval = if (ambient) DeviceTier.ambientFrameIntervalNanos(com.ravi.musicspot.Graph.liteFx) else 0L
    LaunchedEffect(running, periodMs) {
        if (!running) { anim.snapTo(rest); return@LaunchedEffect }
        if (frameInterval <= 0L || periodMs <= 0) {
            // Original full-rate loop (bit-for-bit the v2.0.14 behaviour).
            while (true) {
                anim.animateTo(1f, tween(periodMs, easing = easing))
                if (reverse) anim.animateTo(0f, tween(periodMs, easing = easing)) else anim.snapTo(0f)
            }
        }
        // 1.0.5: paced loop. Phase is a pure function of elapsed time (continuous motion, same speed); only the
        // publishing is throttled, so skipped frames cost no state write, no draw invalidation and no re-blur above.
        val from = anim.value
        val startNanos = withFrameNanos { it }
        var lastPublished = startNanos
        anim.snapTo(loopPhaseAt(0L, periodMs, reverse, from, easing))
        while (true) {
            val now = withFrameNanos { it }
            if (!DeviceTier.shouldPublishFrame(lastPublished, now, frameInterval)) continue
            lastPublished = now
            anim.snapTo(loopPhaseAt((now - startNanos) / 1_000_000L, periodMs, reverse, from, easing))
        }
    }
    return anim.asState()
}

/**
 * 1.0.5: the value the original Animatable loop has after [elapsedMs]: the first leg runs from [from] to 1, then
 * legs alternate 1→0 / 0→1 when [reverse], or restart 0→1. Same [easing] on every leg as tween(periodMs, easing).
 * Pure, for the paced loop and unit tests.
 */
internal fun loopPhaseAt(elapsedMs: Long, periodMs: Int, reverse: Boolean, from: Float, easing: Easing): Float {
    if (periodMs <= 0) return 1f
    val e = elapsedMs.coerceAtLeast(0L)
    val leg = e / periodMs
    val t = ((e - leg * periodMs).toFloat() / periodMs).coerceIn(0f, 1f)
    val k = easing.transform(t)
    return when {
        leg == 0L -> from + (1f - from) * k
        reverse && leg % 2L == 1L -> 1f - k
        else -> k
    }
}
