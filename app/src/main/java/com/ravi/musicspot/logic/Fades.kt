package com.ravi.musicspot.logic

import kotlin.math.PI
import kotlin.math.sin

/**
 * 1.0.6 volume curves (pure, unit-tested in LogicTest). No Android.
 *
 * Equal-power curves keep the perceived loudness steady through a fade: a linear gain ramp sounds like it "drops
 * out" in the middle, because loudness follows power (gain²). With g_in = sin(t·π/2) and g_out = cos(t·π/2),
 * g_in² + g_out² = 1 at every point.
 */
object Fades {
    /** Length of the play / pause / resume volume ramp. */
    const val RAMP_MS = 150L
    /** Step of the ramp loop: about one frame, so each volume step is tiny (no zipper noise). */
    const val RAMP_STEP_MS = 8L
    /** The quietest the start of a crossfade-in gets (the pre-1.0.6 linear fade used the same floor). */
    const val FADE_IN_FLOOR = 0.05f

    /** 0 → 1 equal-power rise for progress [t] (clamped to 0..1). */
    fun rise(t: Float): Float = sin(t.coerceIn(0f, 1f) * (PI / 2).toFloat())

    /** 1 → 0 equal-power fall for progress [t] (clamped to 0..1). */
    fun fall(t: Float): Float = rise(1f - t.coerceIn(0f, 1f))

    /**
     * Crossfade gain of a song at [posMs] with [remainingMs] left, for a crossfade of [crossfadeMs] (0 = off):
     * the last [crossfadeMs] fall and the first half of it rises, both equal-power. Songs shorter than three
     * crossfades are left alone (same rule as before 1.0.6). Returns 1 when no fade applies.
     */
    fun crossfadeGain(posMs: Long, remainingMs: Long, durationMs: Long, crossfadeMs: Long): Float {
        if (crossfadeMs <= 0 || durationMs <= crossfadeMs * 3) return 1f
        var g = 1f
        if (remainingMs < crossfadeMs) g *= rise(remainingMs.toFloat() / crossfadeMs)
        val half = crossfadeMs / 2
        if (half > 0 && posMs < half) g *= rise(posMs.toFloat() / half).coerceAtLeast(FADE_IN_FLOOR)
        return g
    }

    /** True while [crossfadeGain] is shaping the volume (the ticker then runs at a fine step). */
    fun inCrossfade(posMs: Long, remainingMs: Long, durationMs: Long, crossfadeMs: Long): Boolean =
        crossfadeMs > 0 && durationMs > crossfadeMs * 3 && (remainingMs < crossfadeMs || posMs < crossfadeMs / 2)

    /** Gain of a play/resume ramp [elapsedMs] in, starting from [from] (a ramp reversed mid-way starts where it was). */
    fun rampUp(from: Float, elapsedMs: Long, lengthMs: Long = RAMP_MS): Float {
        if (lengthMs <= 0) return 1f
        val t = elapsedMs.toFloat() / lengthMs
        return (from + (1f - from) * rise(t)).coerceIn(0f, 1f)
    }

    /** Gain of a pause ramp [elapsedMs] in, starting from [from]. */
    fun rampDown(from: Float, elapsedMs: Long, lengthMs: Long = RAMP_MS): Float {
        if (lengthMs <= 0) return 0f
        val t = elapsedMs.toFloat() / lengthMs
        return (from * fall(t)).coerceIn(0f, 1f)
    }
}
