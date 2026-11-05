package com.ravi.musicspot.dsp

import kotlin.math.max

enum class EnhanceLevel { OFF, LIGHT, STRONG }

/** Settings of the whole engine; swapped atomically from the UI thread. */
data class EngineSettings(
    val eq: EqConfig = EqConfig(),
    /** Target output rate for "Upscale" (0 = keep the source rate). Only rates the output device supports are offered. */
    val upscaleRate: Int = 0,
    val bandwidthExtension: EnhanceLevel = EnhanceLevel.OFF,
    val transientRestore: Boolean = false,
    /** Set when the device is low-RAM / slow: heavy stages are never started. */
    val lowEndDevice: Boolean = false,
) {
    val enhanceRequested: Boolean get() = upscaleRate > 0 || bandwidthExtension != EnhanceLevel.OFF || transientRestore
}

/** What the engine is actually doing right now, for the UI badge (kept separate from the source's real quality). */
data class EngineStatus(
    val eqActive: Boolean = false,
    val upscaledTo: Int? = null,
    val bwe: EnhanceLevel = EnhanceLevel.OFF,
    val detectedCutoffHz: Int? = null,
    val transient: Boolean = false,
    val disabledForCpu: Boolean = false,
    val limiterDb: Double = 0.0,
) {
    /** "Upscaled 96k · Enhanced" etc. Never claims lossless. Null when nothing is applied. */
    val badge: String? get() {
        val parts = buildList {
            upscaledTo?.let { add("Upscaled ${it / 1000}k") }
            if (bwe != EnhanceLevel.OFF || transient) add("Enhanced")
        }
        return parts.joinToString(" · ").ifBlank { null }
    }
}

/**
 * Pure processing core: float interleaved PCM in → float interleaved PCM out.
 * Order: EQ (preamp/auto-headroom, graphic, parametric, bass shelf, widener) → bandwidth extension → transient
 * restore → resampler → limiter. With the EQ bypassed and enhancements off, output == input exactly.
 */
class EngineCore(val inRate: Int, val channels: Int, initial: EngineSettings) {
    @Volatile private var pending: EngineSettings? = null
    private var settings = initial
    private var chain = EqChain(initial.eq, inRate.toDouble(), channels)
    private var fading: EqChain? = null
    private var fadePos = 0
    private val heavyAllowed = !initial.lowEndDevice
    val outRate: Int = if (heavyAllowed && initial.upscaleRate > inRate) initial.upscaleRate else inRate
    private val resampler = if (outRate != inRate) Resampler(inRate, outRate, channels) else null
    private var bwe: BandwidthExtender? = makeBwe(initial)
    private var shaper: TransientShaper? = if (heavyAllowed && initial.transientRestore) TransientShaper(inRate.toDouble(), channels) else null
    private val limiter = Limiter(outRate.toDouble())
    private val frame = DoubleArray(channels)
    private val frameB = DoubleArray(channels)
    private var mid = FloatArray(0)
    val guard = CpuGuard()

    private fun makeBwe(s: EngineSettings) = if (heavyAllowed && s.bandwidthExtension != EnhanceLevel.OFF)
        BandwidthExtender(inRate.toDouble(), channels, if (s.bandwidthExtension == EnhanceLevel.STRONG) 0.18 else 0.08) else null

    fun update(s: EngineSettings) { pending = s }

    val status: EngineStatus get() = EngineStatus(
        eqActive = !settings.eq.isNeutral, upscaledTo = resampler?.outRate,
        bwe = if (bwe != null) settings.bandwidthExtension else EnhanceLevel.OFF,
        detectedCutoffHz = bwe?.cutoffHz?.toInt()?.takeIf { it < inRate / 2 },
        transient = shaper != null, disabledForCpu = guard.overloaded && settings.enhanceRequested, limiterDb = limiter.reductionDb,
    )

    /** True when processing is an exact identity (bypass, no enhancement, same rate). */
    val isIdentity: Boolean get() = settings.eq.isNeutral && fading == null && bwe == null && shaper == null && resampler == null

    fun maxOutputFrames(inFrames: Int) = resampler?.maxOutputFor(inFrames) ?: inFrames

    /** Returns the output array (may be [input] itself when identity) and frame count. */
    fun process(input: FloatArray, frames: Int, out: FloatArray): Int {
        pending?.let { p ->
            pending = null
            if (p.eq != settings.eq) { fading = chain; fadePos = 0; chain = EqChain(p.eq, inRate.toDouble(), channels) }
            if (p.bandwidthExtension != settings.bandwidthExtension) bwe = makeBwe(p)
            if (p.transientRestore != settings.transientRestore) shaper = if (heavyAllowed && p.transientRestore) TransientShaper(inRate.toDouble(), channels) else null
            settings = p
        }
        if (guard.overloaded) { bwe = null; shaper = null }
        if (isIdentity) { System.arraycopy(input, 0, out, 0, frames * channels); return frames }

        val eqOn = !settings.eq.isNeutral || fading != null
        val target = if (resampler != null) { if (mid.size < frames * channels) mid = FloatArray(frames * channels); mid } else out
        for (i in 0 until frames) {
            val base = i * channels
            for (ch in 0 until channels) frame[ch] = input[base + ch].toDouble()
            if (eqOn) {
                val old = fading
                if (old != null) {
                    // Equal-gain crossfade between the old and new chain over FADE frames: no clicks on changes.
                    System.arraycopy(frame, 0, frameB, 0, channels)
                    old.frame(frameB)
                    if (!settings.eq.isNeutral) chain.frame(frame)
                    val t = fadePos.toDouble() / FADE
                    for (ch in 0 until channels) frame[ch] = frameB[ch] * (1 - t) + frame[ch] * t
                    if (++fadePos >= FADE) fading = null
                } else chain.frame(frame)
            }
            bwe?.frame(frame)
            shaper?.frame(frame)
            if (resampler == null && settings.eq.limiter) limiter.frame(frame, channels)
            for (ch in 0 until channels) target[base + ch] = frame[ch].toFloat()
        }
        val r = resampler ?: return frames
        val n = r.process(target, frames, out)
        if (settings.eq.limiter) for (i in 0 until n) {
            val base = i * channels
            for (ch in 0 until channels) frame[ch] = out[base + ch].toDouble()
            limiter.frame(frame, channels)
            for (ch in 0 until channels) out[base + ch] = frame[ch].toFloat()
        }
        return n
    }

    companion object { const val FADE = 2048 }
}

@Suppress("unused") private fun unused(x: Int) = max(x, 0)
