/*
 * Motion helpers. pressable() and the page transitions follow OpenTune 1.0.4 components/Motion.kt and
 * PageMotion.kt (GPL-3.0-or-later); reduced-motion handling is UpZe's. GPL-3.0-or-later.
 */
package com.ravi.musicspot.ui.motion

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph

/** True when the system animator scale is 0 ("Remove animations"), the app's Reduce motion is on, or the device is low-RAM. */
@Composable
fun reducedMotion(): Boolean {
    val s by Graph.settings.state.collectAsState()
    val ctx = LocalContext.current
    val sys = remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) == 0f }
    return s.reduceMotion || sys || Graph.lowEnd
}

class Haptics(private val view: android.view.View, private val on: () -> Boolean) {
    fun tick() { if (on()) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun click() { if (on()) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    fun confirm() { if (on()) view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS) }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) { Graph.settings.value.haptics } }
}

/**
 * Sinks under the finger and springs back (iOS-like), with a light haptic tick.
 * 1.0.1: [onLongClick] (with a confirm haptic) is how song cards open the shared song-options sheet on long-press.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(onClickLabel: String? = null, pressedScale: Float = 0.96f, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val still = reducedMotion()
    val scale by animateFloatAsState(if (pressed && !still) pressedScale else 1f, spring(dampingRatio = 0.55f, stiffness = 306f), label = "press")
    val h = rememberHaptics()
    val layer = this.graphicsLayer { scaleX = scale; scaleY = scale }
    return if (onLongClick == null) layer.clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClickLabel = onClickLabel) { h.tick(); onClick() }
    else layer.combinedClickable(
        interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClickLabel = onClickLabel,
        onLongClickLabel = "Song options", onLongClick = { h.confirm(); onLongClick() },
    ) { h.tick(); onClick() }
}

/**
 * v2.0.4 press feedback for anything that already has its own click handling (Material buttons, IconButtons, rows):
 * pass the same [interaction] to the clickable/button. Springs to [scale] (~0.96) while pressed; none with Reduce motion.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, scale: Float = 0.96f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val still = reducedMotion()
    val s by animateFloatAsState(if (pressed && !still) scale else 1f, spring(dampingRatio = 0.55f, stiffness = 306f), label = "pressScale")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

/**
 * v2.0.6: the one place UpZe's motion is tuned. Every sheet, dialog, menu, pane and page transition reads its
 * springs, easing and durations from here so the whole app moves the same way. Each builder takes `still`
 * (reducedMotion()) and returns an instant transition when it is true.
 */
object MotionSpec {
    /** Material 3 emphasized / emphasized-decelerate / emphasized-accelerate easing. */
    val emphasized = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasizedDecelerate = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT = 210
    const val MEDIUM = 420
    const val EXIT = 280

    /** Surfaces that arrive (sheets, panes): soft, no visible bounce. */
    const val SURFACE_DAMPING = 0.86f
    const val SURFACE_STIFFNESS = 214f
    /** Small things that pop (menus, dialogs): a touch snappier. */
    const val POP_DAMPING = 0.8f
    const val POP_STIFFNESS = 306f

    fun <T> surfaceSpring(visibility: T? = null) = spring(dampingRatio = SURFACE_DAMPING, stiffness = SURFACE_STIFFNESS, visibilityThreshold = visibility)
    fun <T> popSpring(visibility: T? = null) = spring(dampingRatio = POP_DAMPING, stiffness = POP_STIFFNESS, visibilityThreshold = visibility)
    fun <T> enterTween(delay: Int = 0) = tween<T>(MEDIUM, delayMillis = delay, easing = emphasizedDecelerate)
    fun <T> exitTween() = tween<T>(EXIT, easing = emphasizedAccelerate)

    // ---- scrim ----
    fun scrimIn(still: Boolean): EnterTransition = if (still) EnterTransition.None else fadeIn(tween(MEDIUM, easing = LinearEasing))
    fun scrimOut(still: Boolean): ExitTransition = if (still) ExitTransition.None else fadeOut(tween(EXIT, easing = LinearEasing))

    // ---- bottom sheets / panes: slide up on a spring; close reverses (slide down, accelerating) ----
    fun sheetIn(still: Boolean): EnterTransition = if (still) EnterTransition.None else
        slideInVertically(surfaceSpring(IntOffset.VisibilityThreshold)) { it } + fadeIn(tween(SHORT))
    fun sheetOut(still: Boolean): ExitTransition = if (still) ExitTransition.None else
        slideOutVertically(exitTween()) { it } + fadeOut(tween(EXIT, delayMillis = 112))

    // ---- dialogs: scale from 0.9 with a fade ----
    fun dialogIn(still: Boolean): EnterTransition = if (still) EnterTransition.None else
        scaleIn(popSpring(), initialScale = 0.9f) + fadeIn(tween(SHORT, easing = LinearEasing))
    fun dialogOut(still: Boolean): ExitTransition = if (still) ExitTransition.None else
        scaleOut(exitTween(), targetScale = 0.9f) + fadeOut(tween(SHORT, easing = LinearEasing))

    // ---- dropdown / overflow menus: scale + fade from the anchor (caller supplies the transform origin) ----
    fun menuIn(still: Boolean, origin: TransformOrigin): EnterTransition = if (still) EnterTransition.None else
        scaleIn(popSpring(), initialScale = 0.8f, transformOrigin = origin) + fadeIn(tween(SHORT, easing = LinearEasing))
    fun menuOut(still: Boolean, origin: TransformOrigin): ExitTransition = if (still) ExitTransition.None else
        scaleOut(tween(SHORT, easing = emphasizedAccelerate), targetScale = 0.8f, transformOrigin = origin) + fadeOut(tween(SHORT, easing = LinearEasing))

    // ---- content switches ----
    /** Fade-through: old fades out fast, new fades + scales in from 92%. */
    fun fadeThrough(still: Boolean): ContentTransform = if (still) EnterTransition.None togetherWith ExitTransition.None else
        (fadeIn(tween(294, delayMillis = 126, easing = emphasized)) + scaleIn(tween(294, delayMillis = 126, easing = emphasized), initialScale = 0.92f)) togetherWith fadeOut(tween(126))
    /** Shared axis X; [forward] = push. */
    fun sharedAxisX(forward: Boolean, still: Boolean): ContentTransform {
        if (still) return fadeIn(tween(196)) togetherWith fadeOut(tween(140))
        val spec = tween<IntOffset>(MEDIUM, easing = emphasized)
        val d = if (forward) 1 else -1
        return (slideInHorizontally(spec) { d * (it * 0.08f).toInt() } + fadeIn(tween(294, delayMillis = 84))) togetherWith
            (slideOutHorizontally(spec) { -d * (it * 0.08f).toInt() } + fadeOut(tween(126)))
    }
    /** Shared axis Y; [up] = opening (content rises), false = closing. Used by the player panes. */
    fun sharedAxisY(up: Boolean, still: Boolean): ContentTransform {
        if (still) return EnterTransition.None togetherWith ExitTransition.None
        val d = if (up) 1 else -1
        return (slideInVertically(surfaceSpring(IntOffset.VisibilityThreshold)) { d * it / 5 } + fadeIn(tween(294, delayMillis = 84))) togetherWith
            (slideOutVertically(exitTween()) { -d * it / 8 } + fadeOut(tween(126)))
    }

    /**
     * 1.0.1 container transform for pages and full-screen panes: the new screen grows out of [origin] (the tapped
     * element, see TapOrigin) while the old one recedes slightly; [containerShrink] is the reverse for Back (the
     * closing screen stays on top and shrinks into the element it came from). Lite-effects devices
     * (Graph.liteFx) get a short fade-through instead; Reduce motion a plain quick fade.
     */
    fun containerGrow(still: Boolean, origin: TransformOrigin): ContentTransform {
        if (still) return fadeIn(tween(196)) togetherWith fadeOut(tween(140))
        if (com.ravi.musicspot.Graph.liteFx) return fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(90))
        return (scaleIn(tween(MEDIUM, easing = emphasizedDecelerate), initialScale = 0.86f, transformOrigin = origin) +
            fadeIn(tween(210, delayMillis = 30, easing = LinearEasing))) togetherWith
            (scaleOut(tween(MEDIUM, easing = emphasized), targetScale = 0.96f) + fadeOut(tween(168, delayMillis = 60)))
    }
    fun containerShrink(still: Boolean, origin: TransformOrigin): ContentTransform {
        if (still) return fadeIn(tween(196)) togetherWith fadeOut(tween(140))
        if (com.ravi.musicspot.Graph.liteFx) return fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(90))
        return ((scaleIn(tween(MEDIUM, easing = emphasizedDecelerate), initialScale = 0.96f) + fadeIn(tween(210, easing = LinearEasing))) togetherWith
            (scaleOut(tween(EXIT, easing = emphasizedAccelerate), targetScale = 0.86f, transformOrigin = origin) + fadeOut(tween(EXIT, delayMillis = 40, easing = LinearEasing))))
            .apply { targetContentZIndex = -1f }
    }

    /**
     * v2.0.7 player panes: a Material container transform rising out of the tab row. Opening, the pane grows from
     * 88% at its bottom edge on the surface spring while the artwork shrinks back toward the top; closing reverses
     * (the pane sinks into the tabs, accelerating, the artwork springs back from 94%).
     */
    fun containerRise(open: Boolean, still: Boolean): ContentTransform {
        if (still) return EnterTransition.None togetherWith ExitTransition.None
        val bottom = TransformOrigin(0.5f, 1f); val top = TransformOrigin(0.5f, 0f)
        return if (open)
            (scaleIn(surfaceSpring(), initialScale = 0.88f, transformOrigin = bottom) + slideInVertically(surfaceSpring(IntOffset.VisibilityThreshold)) { it / 6 } +
                fadeIn(tween(308, delayMillis = 56, easing = emphasizedDecelerate))) togetherWith
                (scaleOut(tween(EXIT, easing = emphasizedAccelerate), targetScale = 0.92f, transformOrigin = top) + fadeOut(tween(168)))
        else
            (scaleIn(popSpring(), initialScale = 0.94f, transformOrigin = top) + fadeIn(tween(308, delayMillis = 84, easing = emphasizedDecelerate))) togetherWith
                (scaleOut(tween(EXIT, easing = emphasizedAccelerate), targetScale = 0.88f, transformOrigin = bottom) +
                    slideOutVertically(exitTween()) { it / 6 } + fadeOut(tween(224)))
    }

    /**
     * The dock's expanded ↔ mini (and hide) progress. Critically damped so the progress never leaves 0..1: one value
     * drives the dock and the mini player together, so nothing can overshoot into the other.
     */
    fun dockSpring() = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 194f, visibilityThreshold = 0.001f)

    /** Bounds spring for shared cover art (container-transform-like). */
    fun <T> containerSpring() = spring<T>(dampingRatio = 0.86f, stiffness = 194f)
}

object PageMotion {
    /*
     * v2.0.2: Material motion. Push/pop = shared X axis (short 30 dp slide + fade, emphasised easing), tab switch =
     * fade-through (old page fades out fast, new one fades + scales in from 92%). Reduce motion = plain crossfade.
     * v2.0.6: built from MotionSpec.
     */
    fun push(still: Boolean): ContentTransform = MotionSpec.sharedAxisX(forward = true, still = still)
    fun pop(still: Boolean): ContentTransform = MotionSpec.sharedAxisX(forward = false, still = still)
    /** 1.0.1: page push / pop as a container transform out of / back into the tapped element. */
    fun pushFrom(still: Boolean, origin: androidx.compose.ui.graphics.TransformOrigin): ContentTransform = MotionSpec.containerGrow(still, origin)
    fun popTo(still: Boolean, origin: androidx.compose.ui.graphics.TransformOrigin): ContentTransform = MotionSpec.containerShrink(still, origin)
    fun tab(still: Boolean): ContentTransform = if (still) fadeIn(tween(168)) togetherWith fadeOut(tween(126)) else MotionSpec.fadeThrough(false)
    val playerSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 214f)
}

/**
 * v2.0.9 Material motion helpers for the player's controls.
 * staggerIn: each block rises 24 dp and fades in on first show, [index] × 55 ms apart (emphasized decelerate).
 * iconSwap: an icon changing state scales + fades through (M3 "icon morph" stand-in).
 */
@Composable
fun Modifier.staggerIn(index: Int): Modifier {
    val still = reducedMotion()
    val a = remember { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!still) { kotlinx.coroutines.delay(index * 55L); a.animateTo(1f, tween(MotionSpec.MEDIUM + 140, easing = MotionSpec.emphasizedDecelerate)) }
    }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 24.dp.toPx() }
}

fun iconSwap(still: Boolean): ContentTransform = if (still) EnterTransition.None togetherWith ExitTransition.None else
    (scaleIn(MotionSpec.popSpring(), initialScale = 0.4f) + fadeIn(tween(MotionSpec.SHORT, delayMillis = 60))) togetherWith
        (scaleOut(tween(MotionSpec.SHORT, easing = MotionSpec.emphasizedAccelerate), targetScale = 0.4f) + fadeOut(tween(MotionSpec.SHORT)))
