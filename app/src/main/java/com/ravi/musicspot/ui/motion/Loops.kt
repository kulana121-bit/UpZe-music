// v2.0.14: unconditional looping phase so callers never call rememberInfiniteTransition inside an `if`.
package com.ravi.musicspot.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember

/** v2.0.14: 0..1 loop while [running]; holds [rest] when stopped. [reverse] ping-pongs, otherwise restarts at 0. */
@Composable
fun rememberLoopPhase(
    running: Boolean,
    periodMs: Int,
    reverse: Boolean = true,
    rest: Float = 0f,
    easing: Easing = LinearEasing,
): State<Float> {
    val anim = remember { Animatable(rest) }
    LaunchedEffect(running, periodMs) {
        if (!running) { anim.snapTo(rest); return@LaunchedEffect }
        while (true) {
            anim.animateTo(1f, tween(periodMs, easing = easing))
            if (reverse) anim.animateTo(0f, tween(periodMs, easing = easing)) else anim.snapTo(0f)
        }
    }
    return anim.asState()
}
