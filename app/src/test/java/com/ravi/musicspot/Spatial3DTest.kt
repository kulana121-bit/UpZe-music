package com.ravi.musicspot

import com.ravi.musicspot.dsp.EngineCore
import com.ravi.musicspot.dsp.EngineSettings
import com.ravi.musicspot.dsp.EngineStatus
import com.ravi.musicspot.dsp.Spatial3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** 1.0.8: 3D Sound (Spatial 3D) stage. */
class Spatial3DTest {
    private fun tone(frames: Int, left: Double, right: Double) = FloatArray(frames * 2) { i ->
        val f = i / 2; val v = 0.5 * sin(2 * Math.PI * 440.0 * f / 48_000.0)
        (if (i % 2 == 0) v * left else v * right).toFloat()
    }

    @Test fun offIsBitExact() {
        val e = EngineCore(48_000, 2, EngineSettings())
        assertTrue(e.isIdentity)
        val input = tone(512, 1.0, 0.3)
        val out = FloatArray(input.size)
        e.process(input, 512, out)
        for (i in input.indices) assertEquals(input[i], out[i], 0f)
    }

    @Test fun monoSourcesAreLeftAlone() {
        val e = EngineCore(48_000, 1, EngineSettings(spatial = true))
        assertTrue(e.isIdentity)
    }

    @Test fun crossfeedPlacesAHardLeftSoundInBothEars() {
        val e = EngineCore(48_000, 2, EngineSettings(spatial = true, spatialIntensity = 1.0))
        assertFalse(e.isIdentity)
        val n = 4_800
        val input = tone(n, 1.0, 0.0)
        val out = FloatArray(input.size)
        e.process(input, n, out)
        var right = 0.0; var peak = 0.0
        for (f in n / 2 until n) { right = maxOf(right, abs(out[f * 2 + 1].toDouble())); peak = maxOf(peak, abs(out[f * 2].toDouble())) }
        assertTrue("right ear hears the left speaker", right > 0.02)
        assertTrue("no overload", peak < 1.0)
        assertTrue(out.all { it.isFinite() })
    }

    @Test fun switchingOffGlidesToDry() {
        val s = Spatial3D(48_000.0, 0.8, instant = true)
        val buf = DoubleArray(2)
        s.fadeOut()
        repeat(48_000) { buf[0] = 0.3; buf[1] = -0.3; s.frame(buf) }
        assertTrue(s.finished)
    }

    @Test fun honestLabel() {
        val b = EngineStatus(spatial = true).badge.orEmpty()
        assertTrue(b.contains("3D Sound"))
        assertFalse(b.contains("Atmos", ignoreCase = true))
    }
}
