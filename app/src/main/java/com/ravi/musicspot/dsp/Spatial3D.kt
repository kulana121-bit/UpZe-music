package com.ravi.musicspot.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 1.0.8 "3D Sound" (Spatial 3D): HRTF-style spatial processing of a STEREO signal for headphones, built from a few
 * cheap stages instead of a measured-HRTF convolution engine. It runs on Android 10 / API 29 with no libraries and
 * no allocation after construction, at roughly 110 multiply-adds per frame.
 *
 * Honest label: this is a virtual "two speakers in front of you, in a real room" rendering of stereo audio. It does
 * not create height or surround channels that the recording doesn't have, and it is not Dolby Atmos.
 *
 * The model is a listener with two speakers at ±30°, about 2 m away, in a medium living room. Each frame (L, R) goes
 * through these stages:
 *  1. **Shuffler widening**: the side signal (L−R) above ~250 Hz is lifted, so the image opens out past the ears
 *     while the bass stays centred and mono-compatible.
 *  2. **Head model (crossfeed)**: each ear also hears the opposite speaker ~0.26 ms later (ITD at 30°). The far ear
 *     sits in the head's shadow, so it gets the lows almost in full but only a quarter of the highs: a frequency-
 *     dependent level difference (ILD), modelled as a shelf rather than a plain low-pass, which is closer to measured
 *     HRTFs.
 *  3. **Pinna / front cue**: a small concha lift near 3 kHz and the outer-ear notch near 8.5 kHz, which the brain reads
 *     as "in front". Both are blended in with the intensity.
 *  4. **Early reflections**: the floor bounce (~1.6 ms, the strongest cue for "outside the head, at a distance"), then
 *     side walls, ceiling and back wall (6–29 ms). Same-side and cross-side taps differ per ear so the ears decorrelate,
 *     and each later bounce is darker, as real walls absorb highs.
 *  5. **Late room tail**: a 4-line feedback delay network (Hadamard mix, ~0.35 s RT60, damped highs, 18 ms pre-delay)
 *     at a low level. It is the diffuse "air" of a real room, with no long reverb wash.
 *  6. **Level compensation**, so switching it on is not a loudness jump. The engine's limiter still catches overs.
 *
 * [intensity] 0..1 scales the effect. Changes of intensity and switching on/off glide (~40 ms), so nothing clicks.
 * After [fadeOut] the processor reports [finished] once it is fully dry and can be dropped.
 */
class Spatial3D(private val fs: Double, intensity: Double, instant: Boolean = false) {
    private var kTarget = intensity.coerceIn(0.0, 1.0)
    private var k = kTarget
    private var mixTarget = 1.0
    /** Wet/dry; starts dry and glides in when switched on mid-song, fully wet when the engine starts with it on. */
    private var mix = if (instant) 1.0 else 0.0
    /** Per-sample smoothing toward targets (~40 ms time constant). */
    private val glide = 1.0 - exp(-1.0 / (fs * 0.04))

    // Stage 1: side high-pass split (~250 Hz one-pole).
    private val aSide = onePole(250.0)
    private var lpS = 0.0

    // Stage 2: head model. ITD ≈ 0.26 ms (speakers at ±30°); shadow shelf split at ~1.4 kHz.
    private val itd = (fs * 0.00026).roundToInt().coerceAtLeast(1)
    private val aShadow = onePole(1400.0)
    private var shL = 0.0
    private var shR = 0.0

    // Stage 3: pinna / front cue, full-strength designs blended by k.
    private val presence = Biquad(2, BiquadDesign.design(FilterType.PEAK, fs, 3000.0, 1.0, 2.5))
    private val notch = Biquad(2, BiquadDesign.design(FilterType.PEAK, fs, 8500.0, 2.0, -3.0))

    // Stage 4: early reflections from a sparse image-source pattern.
    private val size = pow2AtLeast((fs * 0.035).toInt() + itd + 2)
    private val mask = size - 1
    private val bufL = DoubleArray(size)
    private val bufR = DoubleArray(size)
    private var w = 0
    /** Tap delays (ms) per ear: floor, near side wall, far side wall, ceiling, back wall, far corner. */
    private val tapDl = msToSamples(doubleArrayOf(1.6, 6.3, 9.1, 12.4, 18.7, 28.9))
    private val tapDr = msToSamples(doubleArrayOf(1.7, 6.9, 8.6, 13.1, 19.6, 27.8))
    private val tapG = doubleArrayOf(0.30, 0.20, 0.15, 0.12, 0.08, 0.055)
    private val tapCross = booleanArrayOf(false, false, true, false, true, true)
    /** Two absorption groups: the floor/near bounces stay brighter (~7 kHz) than the later ones (~3.5 kHz). */
    private val aEarly = onePole(7000.0)
    private val aLate = onePole(3500.0)
    private var e1L = 0.0; private var e1R = 0.0
    private var e2L = 0.0; private var e2R = 0.0

    // Stage 5: late tail, a 4-line FDN.
    private val fdnLen = msToSamplesRaw(doubleArrayOf(29.7, 37.1, 41.3, 47.9))
    private val fdnSize = pow2AtLeast(fdnLen.max() + 2)
    private val fdnMask = fdnSize - 1
    private val fdn = Array(4) { DoubleArray(fdnSize) }
    private val fdnLp = DoubleArray(4)
    private var fw = 0
    private val rt60 = 0.35
    private val fdnG = DoubleArray(4) { 10.0.pow(-3.0 * fdnLen[it] / (rt60 * fs)) }
    private val aFdn = onePole(4500.0)
    private val preDelay = (fs * 0.018).roundToInt().coerceIn(1, size - 1)
    private val fdnOut = DoubleArray(4)

    /** True once a [fadeOut] has fully completed: output equals input and the stage can be removed. */
    val finished: Boolean get() = mixTarget == 0.0 && mix < 1e-4

    fun setIntensity(v: Double) { kTarget = v.coerceIn(0.0, 1.0) }
    fun fadeOut() { mixTarget = 0.0 }
    fun fadeIn(v: Double) { kTarget = v.coerceIn(0.0, 1.0); mixTarget = 1.0 }

    /** Processes one stereo frame in place ([buf] has at least 2 channels; only 0 and 1 are touched). */
    fun frame(buf: DoubleArray) {
        mix += (mixTarget - mix) * glide
        k += (kTarget - k) * glide
        val l = buf[0]; val r = buf[1]

        // 1. Shuffler: widen the side signal above ~250 Hz.
        val m = (l + r) * 0.5
        val s = (l - r) * 0.5
        lpS += aSide * (s - lpS)
        val s2 = s + 0.55 * k * (s - lpS)
        val l1 = m + s2
        val r1 = m - s2

        // Write into the delay lines (used by the head model and the room).
        bufL[w] = l1; bufR[w] = r1

        // 2. Head model: the far ear hears the other speaker later (ITD), with the highs shadowed (shelf ILD).
        val dl = bufL[(w - itd) and mask]
        val dr = bufR[(w - itd) and mask]
        shL += aShadow * (dl - shL)
        shR += aShadow * (dr - shR)
        val xf = 0.20 + 0.26 * k // far-ear level of the lows: ≈ -14 dB … -6.7 dB
        var l2 = l1 + xf * (shR + 0.25 * (dr - shR))
        var r2 = r1 + xf * (shL + 0.25 * (dl - shL))

        // 3. Pinna / front cue (filters always run so their state stays continuous; blended by k).
        val pl = notch.process(presence.process(l2, 0), 0)
        val pr = notch.process(presence.process(r2, 1), 1)
        l2 += k * (pl - l2)
        r2 += k * (pr - r2)

        // 4. Early reflections: floor and near-wall bounces (bright), then later bounces (darker).
        var aL = 0.0; var aR = 0.0; var bL = 0.0; var bR = 0.0
        for (i in 0 until 6) {
            val g = tapG[i]
            val iL = (w - tapDl[i]) and mask
            val iR = (w - tapDr[i]) and mask
            val vL = if (tapCross[i]) bufR[iL] else bufL[iL]
            val vR = if (tapCross[i]) bufL[iR] else bufR[iR]
            if (i < 2) { aL += g * vL; aR += g * vR } else { bL += g * vL; bR += g * vR }
        }
        e1L += aEarly * (aL - e1L); e1R += aEarly * (aR - e1R)
        e2L += aLate * (bL - e2L); e2R += aLate * (bR - e2R)
        val room = 0.6 * k
        l2 += room * (e1L + e2L)
        r2 += room * (e1R + e2R)

        // 5. Late tail: FDN fed with the pre-delayed mono-ish signal, Hadamard-mixed, damped.
        val inL = bufL[(w - preDelay) and mask]
        val inR = bufR[(w - preDelay) and mask]
        for (j in 0 until 4) {
            val v = fdn[j][(fw - fdnLen[j]) and fdnMask]
            fdnLp[j] += aFdn * (v - fdnLp[j])
            fdnOut[j] = fdnLp[j]
        }
        val o0 = fdnOut[0]; val o1 = fdnOut[1]; val o2 = fdnOut[2]; val o3 = fdnOut[3]
        // Hadamard 4×4 / 2: lossless mixing, so the decay is set by fdnG alone.
        val h0 = (o0 + o1 + o2 + o3) * 0.5
        val h1 = (o0 - o1 + o2 - o3) * 0.5
        val h2 = (o0 + o1 - o2 - o3) * 0.5
        val h3 = (o0 - o1 - o2 + o3) * 0.5
        fdn[0][fw] = inL + fdnG[0] * h0
        fdn[1][fw] = inR + fdnG[1] * h1
        fdn[2][fw] = inL + fdnG[2] * h2
        fdn[3][fw] = inR + fdnG[3] * h3
        fw = (fw + 1) and fdnMask
        val tail = 0.16 * k
        l2 += tail * (o0 - o2 + 0.5 * o1)
        r2 += tail * (o1 - o3 + 0.5 * o0)

        // 6. Level compensation (roughly the energy the stages add).
        val g = 1.0 / (1.0 + 0.45 * xf + 0.3 * room + 0.25 * tail)
        l2 *= g; r2 *= g

        w = (w + 1) and mask
        buf[0] = l + (l2 - l) * mix
        buf[1] = r + (r2 - r) * mix
    }

    private fun onePole(hz: Double) = 1.0 - exp(-2.0 * PI * hz / fs)
    private fun msToSamples(ms: DoubleArray) = IntArray(ms.size) { (ms[it] * fs / 1000.0).roundToInt().coerceIn(1, size - 1) }
    private fun msToSamplesRaw(ms: DoubleArray) = IntArray(ms.size) { (ms[it] * fs / 1000.0).roundToInt().coerceAtLeast(1) }

    companion object {
        private fun pow2AtLeast(n: Int): Int { var p = 1; while (p < n) p = p shl 1; return p }
    }
}
