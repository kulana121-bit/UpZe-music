package com.ravi.musicspot

import com.ravi.musicspot.dsp.Biquad
import com.ravi.musicspot.dsp.BiquadDesign
import com.ravi.musicspot.dsp.EngineCore
import com.ravi.musicspot.dsp.EngineSettings
import com.ravi.musicspot.dsp.EnhanceLevel
import com.ravi.musicspot.dsp.EqConfig
import com.ravi.musicspot.dsp.FilterType
import com.ravi.musicspot.dsp.Limiter
import com.ravi.musicspot.dsp.ParamBand
import com.ravi.musicspot.dsp.Resampler
import com.ravi.musicspot.dsp.TpdfDither
import com.ravi.musicspot.dsp.responseDb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class BiquadResponseTest {
    private val fs = 48_000.0

    @Test fun peakingEq() {
        val c = BiquadDesign.design(FilterType.PEAK, fs, 1_000.0, 1.41, 6.0)
        assertEquals(6.0, c.magnitudeDb(1_000.0, fs), 1e-6)
        assertEquals(0.0, c.magnitudeDb(20.0, fs), 0.1)
        assertEquals(0.0, c.magnitudeDb(16_000.0, fs), 0.1)
        assertTrue(c.magnitudeDb(700.0, fs) in 1.0..5.9)
        val cut = BiquadDesign.design(FilterType.PEAK, fs, 3_000.0, 4.0, -9.0)
        assertEquals(-9.0, cut.magnitudeDb(3_000.0, fs), 1e-6)
    }

    @Test fun shelves() {
        val ls = BiquadDesign.design(FilterType.LOW_SHELF, fs, 200.0, 0.707, 6.0)
        assertEquals(6.0, ls.magnitudeDb(20.0, fs), 0.15)
        assertEquals(3.0, ls.magnitudeDb(200.0, fs), 1e-6) // half gain at the corner
        assertEquals(0.0, ls.magnitudeDb(10_000.0, fs), 0.05)
        val hs = BiquadDesign.design(FilterType.HIGH_SHELF, fs, 5_000.0, 0.707, -4.0)
        assertEquals(-4.0, hs.magnitudeDb(20_000.0, fs), 0.3)
        assertEquals(-2.0, hs.magnitudeDb(5_000.0, fs), 1e-6)
        assertEquals(0.0, hs.magnitudeDb(100.0, fs), 0.05)
    }

    @Test fun passFilters() {
        val hp = BiquadDesign.design(FilterType.HIGH_PASS, fs, 100.0, 0.7071067811865476, 0.0)
        assertEquals(-3.0103, hp.magnitudeDb(100.0, fs), 1e-3)
        assertTrue(hp.magnitudeDb(25.0, fs) < -23.0) // 12 dB/oct: two octaves down ≈ −24 dB
        assertEquals(0.0, hp.magnitudeDb(5_000.0, fs), 0.01)
        val lp = BiquadDesign.design(FilterType.LOW_PASS, fs, 8_000.0, 0.7071067811865476, 0.0)
        assertEquals(-3.0103, lp.magnitudeDb(8_000.0, fs), 1e-3)
        assertEquals(0.0, lp.magnitudeDb(100.0, fs), 0.01)
    }

    @Test fun timeDomainMatchesAnalyticResponse() {
        val c = BiquadDesign.design(FilterType.PEAK, fs, 2_000.0, 2.0, 8.0)
        for (f in listOf(250.0, 1_500.0, 2_000.0, 6_000.0)) {
            val bq = Biquad(1, c)
            var inE = 0.0; var outE = 0.0
            for (n in 0 until 96_000) {
                val x = sin(2 * PI * f * n / fs)
                val y = bq.process(x, 0)
                if (n >= 48_000) { inE += x * x; outE += y * y }
            }
            assertEquals("f=$f", c.magnitudeDb(f, fs), 10 * log10(outE / inE), 0.05)
        }
    }

    @Test fun eqConfigResponseAndHeadroom() {
        val cfg = EqConfig(graphic = List(10) { if (it == 5) 6.0 else 0.0 }, autoHeadroom = true)
        // Auto headroom subtracts the boost so the peak of the total response sits at ~0 dB.
        assertEquals(0.0, cfg.responseDb(1_000.0), 0.05)
        assertTrue(cfg.responseDb(100.0) < -5.5)
        val noHr = cfg.copy(autoHeadroom = false)
        assertEquals(6.0, noHr.responseDb(1_000.0), 0.05)
        assertEquals(0.0, cfg.copy(bypass = true).responseDb(1_000.0), 0.0)
        assertTrue(EqConfig().isNeutral)
        assertTrue(!EqConfig(parametric = listOf(ParamBand(FilterType.HIGH_PASS, 30.0, 0.0, 0.7))).isNeutral)
    }
}

class ResamplerTest {
    /** Least-squares fit of a sine at [f]; returns (amplitude, residual RMS). */
    private fun fit(x: DoubleArray, f: Double, fs: Double): Pair<Double, Double> {
        var ss = 0.0; var cc = 0.0; var sc = 0.0; var xs = 0.0; var xc = 0.0
        for (n in x.indices) { val s = sin(2 * PI * f * n / fs); val c = cos(2 * PI * f * n / fs); ss += s * s; cc += c * c; sc += s * c; xs += x[n] * s; xc += x[n] * c }
        val det = ss * cc - sc * sc
        val a = (xs * cc - xc * sc) / det; val b = (xc * ss - xs * sc) / det
        var r = 0.0
        for (n in x.indices) { val e = x[n] - (a * sin(2 * PI * f * n / fs) + b * cos(2 * PI * f * n / fs)); r += e * e }
        return sqrt(a * a + b * b) to sqrt(r / x.size)
    }

    private fun run(inRate: Int, outRate: Int, f: Double, seconds: Double = 1.0, amp: Double = 0.5, chunk: Int = 1_000): DoubleArray {
        val r = Resampler(inRate, outRate, 1)
        val total = (inRate * seconds).toInt()
        val out = ArrayList<Double>()
        var pos = 0
        while (pos < total) {
            val n = minOf(chunk, total - pos)
            val inp = FloatArray(n) { (amp * sin(2 * PI * f * (pos + it) / inRate)).toFloat() }
            val o = FloatArray(r.maxOutputFor(n))
            val w = r.process(inp, n, o)
            for (i in 0 until w) out += o[i].toDouble()
            pos += n
        }
        return out.toDoubleArray()
    }

    @Test fun outputLengthMatchesRatio() {
        val o = run(44_100, 48_000, 1_000.0)
        assertEquals(48_000.0, o.size.toDouble(), 2.0)
        val d = run(96_000, 48_000, 1_000.0)
        assertEquals(48_000.0, d.size.toDouble(), 2.0)
    }

    @Test fun upsampleThdPlusNoise() {
        for ((inR, outR) in listOf(44_100 to 48_000, 44_100 to 96_000, 48_000 to 96_000)) {
            val o = run(inR, outR, 1_000.0)
            val steady = o.copyOfRange(4_000, o.size - 1_000)
            val (a, res) = fit(steady, 1_000.0, outR.toDouble())
            val thdn = 20 * log10(res / (a / sqrt(2.0)))
            println("Resampler $inR→$outR 1 kHz: gain ${"%.4f".format(20 * log10(a / 0.5))} dB, THD+N ${"%.1f".format(thdn)} dB")
            assertEquals(0.0, 20 * log10(a / 0.5), 0.05)
            assertTrue("THD+N $thdn dB", thdn < -80)
        }
    }

    @Test fun highPassbandTone() {
        val o = run(44_100, 48_000, 18_000.0)
        val (a, _) = fit(o.copyOfRange(4_000, o.size - 1_000), 18_000.0, 48_000.0)
        assertEquals(0.0, 20 * log10(a / 0.5), 0.2)
    }

    @Test fun downsampleRejectsAliases() {
        // 96k → 48k: a tone above the new Nyquist must not fold back into the audible band.
        for (f in listOf(30_000.0, 34_000.0, 40_000.0)) {
            val o = run(96_000, 48_000, f)
            val steady = o.copyOfRange(4_000, o.size - 1_000)
            val rms = sqrt(steady.sumOf { it * it } / steady.size)
            val db = 20 * log10(rms / (0.5 / sqrt(2.0)) + 1e-12)
            println("Resampler 96k→48k alias of ${f.toInt()} Hz: ${"%.1f".format(db)} dB")
            assertTrue("alias $f → $db dB", db < -90.0)
        }
    }

    @Test fun chunkSizeDoesNotChangeOutput() {
        val a = run(44_100, 48_000, 1_234.0, 0.3, chunk = 4_096)
        val b = run(44_100, 48_000, 1_234.0, 0.3, chunk = 37)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-6)
    }
}

class LimiterAndBypassTest {
    private val rnd = java.util.Random(7)
    private fun noise(frames: Int, ch: Int, amp: Float) = FloatArray(frames * ch) { (rnd.nextFloat() * 2 - 1) * amp }

    @Test fun limiterNeverExceedsCeiling() {
        val lim = Limiter(48_000.0)
        val buf = DoubleArray(2)
        var peak = 0.0
        for (i in 0 until 200_000) {
            buf[0] = (rnd.nextDouble() * 2 - 1) * 8.0; buf[1] = sin(i * 0.01) * 4.0
            lim.frame(buf, 2); peak = maxOf(peak, abs(buf[0]), abs(buf[1]))
        }
        assertTrue(peak <= Math.pow(10.0, -0.3 / 20) + 1e-12)
    }

    @Test fun engineWithBoostNeverExceedsZeroDbfs() {
        val eq = EqConfig(graphic = List(10) { 12.0 }, preampDb = 6.0, autoHeadroom = false, bassBoostDb = 10.0, widen = 1.0, limiter = true)
        for (upscale in listOf(0, 96_000)) {
            val e = EngineCore(48_000, 2, EngineSettings(eq = eq, upscaleRate = upscale, bandwidthExtension = EnhanceLevel.STRONG, transientRestore = true))
            var peak = 0f
            repeat(50) {
                val inp = noise(1_024, 2, 1f)
                val out = FloatArray(e.maxOutputFrames(1_024) * 2)
                val n = e.process(inp, 1_024, out)
                for (i in 0 until n * 2) peak = maxOf(peak, abs(out[i]))
            }
            println("Engine peak with +12 dB EQ, upscale=$upscale: ${"%.3f".format(20 * log10(peak.toDouble()))} dBFS")
            assertTrue("peak $peak", peak <= 1.0f)
        }
    }

    private fun bits(a: FloatArray, n: Int) = IntArray(n) { java.lang.Float.floatToRawIntBits(a[it]) }

    @Test fun bypassIsBitExact() {
        val loud = EqConfig(graphic = List(10) { if (it % 2 == 0) 9.0 else -6.0 }, preampDb = 3.0, bassBoostDb = 5.0, widen = 0.5)
        val variants = listOf(loud.copy(bypass = true), loud.copy(enabled = false), EqConfig())
        for (cfg in variants) {
            val e = EngineCore(44_100, 2, EngineSettings(eq = cfg))
            assertTrue(e.isIdentity)
            val inp = noise(4_096, 2, 1.5f).also { it[0] = Float.MIN_VALUE; it[1] = -0f; it[2] = 1e-38f }
            val out = FloatArray(inp.size)
            assertEquals(4_096, e.process(inp, 4_096, out))
            assertArrayEquals(bits(inp, inp.size), bits(out, out.size))
        }
    }

    @Test fun switchingToBypassBecomesBitExactAfterCrossfade() {
        val loud = EqConfig(graphic = List(10) { 6.0 })
        val e = EngineCore(48_000, 2, EngineSettings(eq = loud))
        val out = FloatArray(8_192)
        e.process(noise(4_096, 2, 0.5f), 4_096, out)
        e.update(EngineSettings(eq = loud.copy(bypass = true)))
        e.process(noise(EngineCore.FADE, 2, 0.5f), EngineCore.FADE, out) // fade-out of the old chain
        assertTrue(e.isIdentity)
        val inp = noise(4_096, 2, 0.9f)
        e.process(inp, 4_096, out)
        assertArrayEquals(bits(inp, inp.size), bits(out, inp.size))
    }

    @Test fun ditherErrorBounded() {
        val d = TpdfDither()
        repeat(100_000) {
            val x = rnd.nextDouble() * 1.8 - 0.9
            assertTrue(abs(d.to16(x) - x * 32767.0) <= 1.5 + 1e-9)
        }
        assertEquals(32767.toShort(), d.to16(2.0))
        assertEquals((-32768).toShort(), d.to16(-2.0))
    }
}
