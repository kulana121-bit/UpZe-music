package com.ravi.musicspot.dsp

import java.util.concurrent.atomic.AtomicReference
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
    /** 1.0.8 "3D Sound" (Spatial 3D) for headphones: see [Spatial3D]. Light enough to run on low-end devices too. */
    val spatial: Boolean = false,
    /** 1.0.8: 0..1 strength of [spatial]. */
    val spatialIntensity: Double = 0.5,
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
    /** 1.0.8: the 3D Sound stage is running. */
    val spatial: Boolean = false,
) {
    /** "Upscaled 96k · Enhanced" etc. Never claims lossless. Null when nothing is applied. */
    val badge: String? get() {
        val parts = buildList {
            upscaledTo?.let { add("Upscaled ${it / 1000}k") }
            if (bwe != EnhanceLevel.OFF || transient) add("Enhanced")
            if (spatial) add("3D Sound") // 1.0.8: honest label, never "Atmos"
        }
        return parts.joinToString(" · ").ifBlank { null }
    }
}

/**
 * Pure processing core: float interleaved PCM in → float interleaved PCM out.
 * Order: EQ (preamp/auto-headroom, graphic, parametric, bass shelf, widener) → bandwidth extension → transient
 * restore → 3D Sound (1.0.8, stereo only) → resampler → limiter. With the EQ bypassed and enhancements off, output == input exactly.
 */
class EngineCore(val inRate: Int, val channels: Int, initial: EngineSettings) {
    /**
     * 1.0.5: everything a settings change needs is built on the caller's (UI/collector) thread and handed to the audio
     * thread as one immutable snapshot. Previously the audio thread built the EqChain itself (RBJ designs + a 240-point
     * auto-headroom scan of every filter ≈ thousands of cos/sin/log10 + List allocations) inside process().
     * The objects are fresh (zero filter state) exactly as before, and are only adopted under the same conditions as
     * before, so the output is unchanged.
     */
    private class Prepared(
        val settings: EngineSettings,
        val chain: EqChain,
        val eqNeutral: Boolean,
        val bwe: BandwidthExtender?,
        val shaper: TransientShaper?,
        /** 1.0.8: a fresh 3D Sound stage, built here (not on the audio thread) in case this swap turns it on. */
        val spatial: Spatial3D?,
    )

    // 1.0.5: AtomicReference.getAndSet(null) instead of read-then-null on a @Volatile (an update() landing between the
    // two was lost). No allocation on the audio thread.
    private val pending = AtomicReference<Prepared?>(null)
    private val fsIn = inRate.toDouble()
    private var settings = initial
    /** 1.0.5: settings.eq.isNeutral cached per swap; the getter walks Lists (iterator + unboxing) and was hit per frame while fading. */
    private var eqNeutral = initial.eq.isNeutral
    private var chain = EqChain(initial.eq, fsIn, channels)
    private var fading: EqChain? = null
    private var fadePos = 0
    private val heavyAllowed = !initial.lowEndDevice
    val outRate: Int = if (heavyAllowed && initial.upscaleRate > inRate) initial.upscaleRate else inRate
    private val resampler = if (outRate != inRate) Resampler(inRate, outRate, channels) else null
    private var bwe: BandwidthExtender? = makeBwe(initial)
    private var shaper: TransientShaper? = makeShaper(initial)
    /** 1.0.8: the 3D Sound stage; kept while it fades out after being switched off, then dropped. */
    private var spatial: Spatial3D? = makeSpatial(initial, instant = true)
    private val limiter = Limiter(outRate.toDouble())
    private val frame = DoubleArray(channels)
    private val frameB = DoubleArray(channels)
    private var mid = FloatArray(0)
    val guard = CpuGuard()
    /** 1.0.5: caller-thread cache so repeated update()s with the same EQ don't rebuild the chain. Never touched by process(). */
    @Volatile private var lastBuilt: EqChain? = null
    /** 1.0.8: caller-thread memory of whether the previous update had 3D Sound on (see [update]). */
    @Volatile private var lastUpdateSpatial = initial.spatial

    private fun makeBwe(s: EngineSettings) = if (heavyAllowed && s.bandwidthExtension != EnhanceLevel.OFF)
        BandwidthExtender(inRate.toDouble(), channels, if (s.bandwidthExtension == EnhanceLevel.STRONG) 0.18 else 0.08) else null

    private fun makeShaper(s: EngineSettings) = if (heavyAllowed && s.transientRestore) TransientShaper(inRate.toDouble(), channels) else null

    /** 1.0.8: stereo sources only (mono has no image to place; multichannel is left untouched). */
    private fun makeSpatial(s: EngineSettings, instant: Boolean = false) = if (s.spatial && channels == 2) Spatial3D(inRate.toDouble(), s.spatialIntensity, instant) else null

    /**
     * Called from the UI/collector thread. A cached chain is reused only when its config equals the new EQ; a chain can
     * only be live on the audio thread while settings.eq equals its config, and then process() never re-adopts it.
     */
    fun update(s: EngineSettings) {
        val prev = lastBuilt
        val c = if (prev != null && prev.config == s.eq) prev else EqChain(s.eq, fsIn, channels)
        lastBuilt = c
        // 1.0.8: a 3D Sound stage (delay lines) is only built when this update switches it on, not for every EQ drag.
        // A stage built for an update the audio thread hasn't picked up yet is carried over (pending is latest-wins).
        val carried = pending.get()?.spatial
        val sp = when {
            !s.spatial -> null
            carried != null -> carried.also { it.setIntensity(s.spatialIntensity) }
            !lastUpdateSpatial -> makeSpatial(s)
            else -> null
        }
        lastUpdateSpatial = s.spatial
        pending.set(Prepared(s, c, s.eq.isNeutral, makeBwe(s), makeShaper(s), sp))
    }

    val status: EngineStatus get() = EngineStatus(
        eqActive = !eqNeutral, upscaledTo = resampler?.outRate,
        bwe = if (bwe != null) settings.bandwidthExtension else EnhanceLevel.OFF,
        detectedCutoffHz = bwe?.cutoffHz?.toInt()?.takeIf { it < inRate / 2 },
        transient = shaper != null, disabledForCpu = guard.overloaded && settings.enhanceRequested, limiterDb = limiter.reductionDb,
        spatial = spatial != null && settings.spatial,
    )

    /** True when processing is an exact identity (bypass, no enhancement, same rate). */
    val isIdentity: Boolean get() = eqNeutral && fading == null && bwe == null && shaper == null && resampler == null && spatial == null

    fun maxOutputFrames(inFrames: Int) = resampler?.maxOutputFor(inFrames) ?: inFrames

    /** Returns the output array (may be [input] itself when identity) and frame count. */
    fun process(input: FloatArray, frames: Int, out: FloatArray): Int {
        // 1.0.7: while an EQ crossfade is running, the next swap waits until it ends (<= FADE frames, ~43 ms). Before,
        // a slider drag replaced the fading chain mid-fade and the old curve was cut off (an audible tick); now drags
        // coalesce in [pending] (latest wins) and every change still gets its full click-free crossfade.
        val p = if (fading == null) pending.getAndSet(null) else null
        if (p != null) {
            val ns = p.settings
            if (ns.eq != settings.eq) { fading = chain; fadePos = 0; chain = p.chain }
            if (ns.bandwidthExtension != settings.bandwidthExtension) bwe = p.bwe
            if (ns.transientRestore != settings.transientRestore) shaper = p.shaper
            // 1.0.8 3D Sound: on/off and intensity glide inside the stage (no clicks); off drops it once fully dry.
            if (ns.spatial != settings.spatial) {
                val cur = spatial
                if (ns.spatial) spatial = cur?.also { it.fadeIn(ns.spatialIntensity) } ?: p.spatial
                else cur?.fadeOut()
            } else if (ns.spatial && ns.spatialIntensity != settings.spatialIntensity) spatial?.setIntensity(ns.spatialIntensity)
            settings = ns
            eqNeutral = p.eqNeutral
        }
        if (guard.overloaded) { bwe = null; shaper = null }
        if (isIdentity) { System.arraycopy(input, 0, out, 0, frames * channels); return frames }

        // 1.0.5: hoist per-buffer invariants out of the frame loop (no getters/List walks per frame).
        val neutral = eqNeutral
        val eqOn = !neutral || fading != null
        val limitOn = settings.eq.limiter
        val rs = resampler
        val be = bwe
        val sh = shaper
        val sp = spatial
        val target = if (rs != null) { if (mid.size < frames * channels) mid = FloatArray(frames * channels); mid } else out
        for (i in 0 until frames) {
            val base = i * channels
            for (ch in 0 until channels) frame[ch] = input[base + ch].toDouble()
            if (eqOn) {
                val old = fading
                if (old != null) {
                    // Equal-gain crossfade between the old and new chain over FADE frames: no clicks on changes.
                    System.arraycopy(frame, 0, frameB, 0, channels)
                    old.frame(frameB)
                    if (!neutral) chain.frame(frame)
                    val t = fadePos.toDouble() / FADE
                    for (ch in 0 until channels) frame[ch] = frameB[ch] * (1 - t) + frame[ch] * t
                    if (++fadePos >= FADE) fading = null
                } else if (!neutral) chain.frame(frame) // 1.0.5: bypass no longer leaks EQ for the rest of a buffer after a crossfade ends
            }
            be?.frame(frame)
            sh?.frame(frame)
            sp?.frame(frame)
            if (rs == null && limitOn) limiter.frame(frame, channels)
            for (ch in 0 until channels) target[base + ch] = frame[ch].toFloat()
        }
        if (sp != null && sp.finished) spatial = null
        if (rs == null) return frames
        val n = rs.process(target, frames, out)
        if (limitOn) for (i in 0 until n) {
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
