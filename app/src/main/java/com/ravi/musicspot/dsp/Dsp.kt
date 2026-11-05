package com.ravi.musicspot.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/* ==========================================================================================================
 * UpZe software audio engine — pure Kotlin, no Android types, JVM unit-tested.
 * Float32 I/O, Float64 filter state. Used by EngineAudioProcessor inside ExoPlayer's audio sink.
 * ======================================================================================================== */

/** Normalised biquad coefficients (a0 = 1). */
data class Coefs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    companion object { val IDENTITY = Coefs(1.0, 0.0, 0.0, 0.0, 0.0) }

    /** Magnitude response in dB at [f] Hz for sample rate [fs]. */
    fun magnitudeDb(f: Double, fs: Double): Double {
        val w = 2 * PI * f / fs
        val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2; val ni = -(b1 * sw + b2 * s2)
        val dr = 1 + a1 * cw + a2 * c2; val di = -(a1 * sw + a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }
}

enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS }

/** RBJ Audio-EQ-Cookbook designs. */
object BiquadDesign {
    fun design(type: FilterType, fs: Double, f0In: Double, q: Double, gainDb: Double): Coefs {
        // v2.0.16: fs < ~21 Hz made coerceIn(10, fs*0.49) throw; NaN/inf settings produced NaN coefficients (silence/NaN out).
        if (!(fs > 21.0) || !f0In.isFinite() || !q.isFinite() || !gainDb.isFinite()) return Coefs.IDENTITY
        val f0 = f0In.coerceIn(10.0, fs * 0.49)
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * f0 / fs
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2 * max(q, 0.05))
        val (b0, b1, b2, a0, a1, a2) = when (type) {
            FilterType.PEAK -> Six(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            FilterType.LOW_SHELF -> { val s = 2 * sqrt(a) * alpha
                Six(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s),
                    (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s) }
            FilterType.HIGH_SHELF -> { val s = 2 * sqrt(a) * alpha
                Six(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s),
                    (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s) }
            FilterType.LOW_PASS -> Six((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
            FilterType.HIGH_PASS -> Six((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
        }
        return Coefs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
    private data class Six(val b0: Double, val b1: Double, val b2: Double, val a0: Double, val a1: Double, val a2: Double)
}

/** Transposed direct form II biquad with per-channel Float64 state. */
class Biquad(channels: Int, var c: Coefs = Coefs.IDENTITY) {
    private val z1 = DoubleArray(channels); private val z2 = DoubleArray(channels)
    fun process(x: Double, ch: Int): Double {
        val y = c.b0 * x + z1[ch]
        z1[ch] = c.b1 * x - c.a1 * y + z2[ch]
        z2[ch] = c.b2 * x - c.a2 * y
        return y
    }
    fun reset() { z1.fill(0.0); z2.fill(0.0) }
}

data class ParamBand(val type: FilterType, val freqHz: Double, val gainDb: Double, val q: Double, val enabled: Boolean = true)

/** Everything the EQ stage needs; immutable so the audio thread can swap it atomically. */
data class EqConfig(
    val enabled: Boolean = true,
    /** A/B: true = B (bypass) → bit-exact pass-through of the EQ stage. */
    val bypass: Boolean = false,
    val graphic: List<Double> = List(10) { 0.0 },
    val parametric: List<ParamBand> = emptyList(),
    val preampDb: Double = 0.0,
    val autoHeadroom: Boolean = true,
    val bassBoostDb: Double = 0.0,
    /** 0..1, mild mid/side widening (side gain up to +3.5 dB). */
    val widen: Double = 0.0,
    val limiter: Boolean = true,
) {
    val isNeutral: Boolean get() = !enabled || bypass ||
        (graphic.all { it == 0.0 } && parametric.none { it.enabled && (it.gainDb != 0.0 || it.type == FilterType.LOW_PASS || it.type == FilterType.HIGH_PASS) } &&
            preampDb == 0.0 && bassBoostDb == 0.0 && widen == 0.0)

    companion object {
        val GRAPHIC_FREQS = doubleArrayOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        const val GRAPHIC_Q = 1.41
        const val BASS_FREQ = 90.0
    }

    fun designs(fs: Double): List<Coefs> = buildList {
        // v2.0.16: a persisted graphic list longer than 10 bands threw ArrayIndexOutOfBounds on the audio thread.
        graphic.forEachIndexed { i, g -> val f = GRAPHIC_FREQS.getOrNull(i) ?: return@forEachIndexed; if (g != 0.0) add(BiquadDesign.design(FilterType.PEAK, fs, f, GRAPHIC_Q, g)) }
        parametric.filter { it.enabled }.forEach { add(BiquadDesign.design(it.type, fs, it.freqHz, it.q, it.gainDb)) }
        if (bassBoostDb != 0.0) add(BiquadDesign.design(FilterType.LOW_SHELF, fs, BASS_FREQ, 0.7, bassBoostDb))
    }

    /** Peak of the combined response (dB), sampled on a dense log grid. */
    fun peakResponseDb(fs: Double): Double {
        val ds = designs(fs)
        var peak = Double.NEGATIVE_INFINITY
        for (i in 0 until 240) {
            val f = 20.0 * (min(20_000.0, fs * 0.47) / 20.0).pow(i / 239.0)
            peak = max(peak, ds.sumOf { it.magnitudeDb(f, fs) })
        }
        return peak + if (widen > 0) 20 * log10(1 + widen * 0.5) else 0.0
    }

    /** Preamp actually applied: the user's preamp minus any boost the curve adds (auto headroom). */
    fun effectivePreampDb(fs: Double): Double = preampDb - if (autoHeadroom) max(0.0, peakResponseDb(fs) + preampDb).coerceAtMost(24.0) else 0.0
}

/** One complete EQ chain for a fixed config and sample rate. */
class EqChain(val config: EqConfig, private val fs: Double, private val channels: Int) {
    private val filters = config.designs(fs).map { Biquad(channels, it) }
    private val gain = 10.0.pow(config.effectivePreampDb(fs) / 20)
    private val sideGain = 1 + config.widen * 0.5

    /** Processes one interleaved frame in place (Float64 working buffer). */
    fun frame(buf: DoubleArray) {
        for (ch in 0 until channels) {
            var x = buf[ch] * gain
            for (f in filters) x = f.process(x, ch)
            buf[ch] = x
        }
        if (channels == 2 && config.widen > 0) {
            val m = (buf[0] + buf[1]) * 0.5; val s = (buf[0] - buf[1]) * 0.5 * sideGain
            buf[0] = m + s; buf[1] = m - s
        }
    }
}

/** Peak limiter: instant attack, smooth release; output never exceeds [ceiling] (default −0.3 dBFS). */
class Limiter(private val fs: Double, private val ceiling: Double = 10.0.pow(-0.3 / 20), releaseMs: Double = 60.0) {
    private var g = 1.0
    private val rel = exp(-1.0 / (fs * releaseMs / 1000))
    var reductionDb = 0.0; private set
    fun frame(buf: DoubleArray, channels: Int) {
        var peak = 0.0
        for (ch in 0 until channels) peak = max(peak, abs(buf[ch]))
        val target = if (peak * g > ceiling) ceiling / peak else min(1.0, if (peak > 0) ceiling / peak else 1.0)
        g = if (target < g) target else target + (g - target) * rel
        for (ch in 0 until channels) buf[ch] = (buf[ch] * g).coerceIn(-ceiling, ceiling)
        reductionDb = 20 * log10(g)
    }
}

/**
 * Bandwidth extension (DSEE-like, honest): adds generated harmonics above the detected lossy low-pass cutoff.
 * Detects the cutoff with band energy detectors; a harmonic exciter (full-wave product of the band below the
 * cutoff) is high-passed at the cutoff and mixed in at Light/Strong level. It does not recover lost data.
 */
class BandwidthExtender(private val fs: Double, private val channels: Int, private val level: Double) {
    private val probes = doubleArrayOf(11_000.0, 14_000.0, 15_500.0, 16_500.0, 17_500.0, 19_000.0).filter { it < fs * 0.45 }
    private val bands = probes.map { f -> Biquad(1, BiquadDesign.design(FilterType.PEAK, fs, f, 8.0, 0.0)).also { it.c = bandPass(f) } }
    private val energy = DoubleArray(probes.size)
    var cutoffHz: Double = fs / 2; private set
    private var srcHp = Array(channels) { Biquad(1) }
    private var outHp1 = Array(channels) { Biquad(1) }
    private var outHp2 = Array(channels) { Biquad(1) }
    private var counter = 0
    private var active = false

    private fun bandPass(f: Double): Coefs {
        val w0 = 2 * PI * f / fs; val alpha = sin(w0) / (2 * 6.0); val a0 = 1 + alpha
        return Coefs(alpha / a0, 0.0, -alpha / a0, -2 * cos(w0) / a0, (1 - alpha) / a0)
    }

    private fun retune(cut: Double) {
        cutoffHz = cut
        active = cut < min(19_500.0, fs * 0.44)
        srcHp.forEach { it.c = BiquadDesign.design(FilterType.HIGH_PASS, fs, cut * 0.55, 0.707, 0.0) }
        outHp1.forEach { it.c = BiquadDesign.design(FilterType.HIGH_PASS, fs, cut, 0.707, 0.0) }
        outHp2.forEach { it.c = BiquadDesign.design(FilterType.HIGH_PASS, fs, cut, 0.707, 0.0) }
    }

    init { retune(fs / 2) }

    fun frame(buf: DoubleArray) {
        val mono = (0 until channels).sumOf { buf[it] } / channels
        for (i in bands.indices) { val y = bands[i].process(mono, 0); energy[i] = energy[i] * 0.9995 + y * y * 0.0005 }
        if (++counter >= 4096) {
            counter = 0
            val ref = energy.firstOrNull() ?: 0.0
            if (ref > 1e-9) {
                // First probe band that sits 35 dB under the 11 kHz reference marks the codec's low-pass.
                val idx = (1 until energy.size).firstOrNull { energy[it] < ref * 10.0.pow(-3.5) }
                val cut = idx?.let { (probes[it - 1] + probes[it]) / 2 } ?: fs / 2
                if (abs(cut - cutoffHz) > 300) retune(cut)
            }
        }
        if (!active || level <= 0) return
        for (ch in 0 until channels) {
            val band = srcHp[ch].process(buf[ch], 0)
            val harm = band * abs(band) * 4.0 // even-order harmonics, rises with level like real content
            buf[ch] += outHp2[ch].process(outHp1[ch].process(harm, 0), 0) * level
        }
    }
}

/** Subtle transient restore: lifts attacks by up to [maxDb] when a fast envelope outruns a slow one. */
class TransientShaper(fs: Double, private val channels: Int, private val maxDb: Double = 1.5) {
    private val fast = exp(-1.0 / (fs * 0.002)); private val slow = exp(-1.0 / (fs * 0.06))
    private var ef = 0.0; private var es = 0.0
    private val maxG = 10.0.pow(maxDb / 20)
    fun frame(buf: DoubleArray) {
        var p = 0.0
        for (ch in 0 until channels) p = max(p, abs(buf[ch]))
        ef = max(p, ef * fast); es = p + (es - p) * slow
        val g = if (es > 1e-6) (ef / es).coerceIn(1.0, maxG) else 1.0
        if (g > 1.0) for (ch in 0 until channels) buf[ch] *= 1 + (g - 1) * 0.5
    }
}

/** TPDF dither + rounding for reducing float to 16-bit. Error stays within ±1.5 LSB. */
class TpdfDither(seed: Long = 0x5EED) {
    private var s = seed xor 0x2545F4914F6CDD1DL
    private fun rnd(): Double { s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17); return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) }
    fun to16(x: Double): Short {
        val v = x * 32767.0 + (rnd() - rnd())
        return v.let { kotlin.math.round(it) }.coerceIn(-32768.0, 32767.0).toInt().toShort()
    }
}

/**
 * Polyphase windowed-sinc resampler (Kaiser window, β≈10, 64 taps per phase → ~95 dB stopband, passband to
 * ~0.45·min(fsIn, fsOut)). Rational ratio L/M; coefficient table built once per rate pair.
 */
class Resampler(val inRate: Int, val outRate: Int, private val channels: Int, private val taps: Int = 64) {
    private val l: Int; private val m: Int
    private val poly: Array<FloatArray>
    /** Per-channel input not yet fully consumed; index 0 is absolute sample [start]. Pre-filled with taps-1 zeros. */
    private var buf: Array<FloatArray>
    private var len = 0
    private var start = 0L
    /** Absolute index of the newest input sample the next output needs, and the polyphase branch. */
    private var n0 = 0L
    private var phase = 0

    init {
        val g = gcd(inRate, outRate); l = outRate / g; m = inRate / g
        val total = l * taps
        val cutoff = 0.5 * min(1.0, l.toDouble() / m) * 0.93 / l // normalised to the L·fsIn rate
        val beta = 10.0
        val i0b = besselI0(beta)
        val center = (total - 1) / 2.0
        val proto = DoubleArray(total) { n ->
            val t = n - center
            val sinc = if (abs(t) < 1e-9) 2 * cutoff else sin(2 * PI * cutoff * t) / (PI * t)
            val r = t / (total / 2.0)
            val w = if (abs(r) >= 1) 0.0 else besselI0(beta * sqrt(1 - r * r)) / i0b
            sinc * w * l
        }
        poly = Array(l) { ph -> FloatArray(taps) { k -> proto[ph + k * l].toFloat() } }
        buf = Array(channels) { FloatArray(taps * 4 + 4096) }
        len = taps - 1; start = -(taps - 1).toLong(); n0 = 0
    }

    val ratio: Double get() = outRate.toDouble() / inRate
    /** Group delay in input samples (half the prototype length). */
    val delayIn: Double get() = (taps - 1) / 2.0
    fun maxOutputFor(inFrames: Int) = (inFrames.toLong() * l / m + 4).toInt()

    fun reset() { len = taps - 1; start = -(taps - 1).toLong(); n0 = 0; phase = 0; buf.forEach { it.fill(0f) } }

    /** Interleaved float in → interleaved float out (out must hold maxOutputFor(inFrames) frames); returns frames written. */
    fun process(input: FloatArray, inFrames: Int, out: FloatArray): Int {
        if (len + inFrames > buf[0].size) buf = Array(channels) { ch -> buf[ch].copyOf(max(buf[0].size * 2, len + inFrames + 1024)) }
        for (ch in 0 until channels) { val b = buf[ch]; for (i in 0 until inFrames) b[len + i] = input[i * channels + ch] }
        len += inFrames
        var written = 0
        val end = start + len
        while (n0 < end) {
            val coefs = poly[phase]
            val newest = (n0 - start).toInt()
            for (ch in 0 until channels) {
                val b = buf[ch]; var acc = 0.0f
                for (k in 0 until taps) acc += coefs[k] * b[newest - k]
                out[written * channels + ch] = acc
            }
            written++
            phase += m
            n0 += phase / l
            phase %= l
        }
        // Keep only the history the next outputs still need.
        val keepFrom = (n0 - (taps - 1) - start).toInt().coerceIn(0, len)
        if (keepFrom > 0) {
            for (ch in 0 until channels) System.arraycopy(buf[ch], keepFrom, buf[ch], 0, len - keepFrom)
            len -= keepFrom; start += keepFrom
        }
        return written
    }

    companion object {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        fun besselI0(x: Double): Double {
            var sum = 1.0; var term = 1.0; val q = x * x / 4
            for (k in 1..60) { term *= q / (k * k); sum += term; if (term < 1e-14 * sum) break }
            return sum
        }
    }
}

/** Measures DSP cost against real time; reports overload so heavy stages can switch themselves off. */
class CpuGuard(private val budget: Double = 0.35, private val strikes: Int = 6) {
    private var bad = 0
    var overloaded = false; private set
    fun record(processNanos: Long, audioNanos: Long) {
        if (audioNanos <= 0) return
        if (processNanos.toDouble() / audioNanos > budget) { if (++bad >= strikes) overloaded = true } else bad = max(0, bad - 1)
    }
    fun reset() { bad = 0; overloaded = false }
}

/** Convenience used by tests and the UI curve: combined EQ response in dB at [f]. */
fun EqConfig.responseDb(f: Double, fs: Double = 48_000.0): Double =
    if (!enabled || bypass) 0.0 else designs(fs).sumOf { it.magnitudeDb(f, fs) } + effectivePreampDb(fs)

@Suppress("unused") private fun db(x: Double) = 20 * ln(x) / ln(10.0)
