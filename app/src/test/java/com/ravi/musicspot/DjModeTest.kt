package com.ravi.musicspot

import com.ravi.musicspot.data.BpmStore
import com.ravi.musicspot.logic.DjMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Pack 6 Part 2: DJ Mode — BPM detection on synthetic audio, tempo-match maths, beat-aligned plan, the BPM store. */
class DjModeTest {
    private val sr = 44_100

    /** A click track: a 1 kHz decaying blip on every beat, starting at [offsetSec]. */
    private fun clicks(bpm: Double, secs: Int = 30, offsetSec: Double = 0.25, rate: Int = sr): FloatArray {
        val x = FloatArray(rate * secs)
        var t = offsetSec
        while (t < secs) {
            val i = (t * rate).toInt()
            for (k in 0 until 200) if (i + k < x.size) x[i + k] += (sin(2 * PI * 1000 * k / rate) * exp(-k / 50.0) * 0.6).toFloat()
            t += 60.0 / bpm
        }
        return x
    }

    private fun analyze(x: FloatArray, rate: Int = sr): DjMode.Grid? =
        DjMode.analyze(DjMode.OnsetEnvelope(rate).apply { x.toList().chunked(4096).forEach { feed(it.toFloatArray()) } })

    @Test fun clickTracksWithinOneBpm() {
        for (bpm in listOf(128.0, 93.5, 120.0, 174.0, 100.3, 71.0)) {
            val g = analyze(clicks(bpm))
            assertNotNull("no tempo for $bpm", g)
            assertTrue("$bpm reported ${g!!.bpm}", abs(g.bpm - bpm) <= 1.0)
            assertTrue("confidence ${g.confidence}", g.confidence >= DjMode.MIN_CONFIDENCE)
        }
    }

    @Test fun clickTrackAtOtherSampleRateAndFirstBeat() {
        val g = analyze(clicks(126.0, offsetSec = 0.4, rate = 48_000), 48_000)!!
        assertTrue("reported ${g.bpm}", abs(g.bpm - 126.0) <= 1.0)
        // The first detected beat is the first click (0.4 s), within one 5 ms frame plus the smoothing.
        assertTrue("first beat ${g.firstBeatMs}", abs(g.firstBeatMs - 400) <= 20)
    }

    @Test fun pulseAboveRangeReportsHalfTime() {
        val g = analyze(clicks(200.0))!!
        assertTrue("reported ${g.bpm}", abs(g.bpm - 100.0) <= 1.0)
    }

    @Test fun noiseIsUnknownNotAWrongNumber() {
        val rnd = java.util.Random(1)
        assertNull(analyze(FloatArray(sr * 30) { (rnd.nextGaussian() * 0.1).toFloat() }))
    }

    @Test fun randomClicksAreUnknown() {
        val rnd = java.util.Random(7)
        val x = FloatArray(sr * 30)
        repeat(60) {
            val i = rnd.nextInt(x.size - 200)
            for (k in 0 until 200) x[i + k] += (sin(0.14 * k) * exp(-k / 50.0) * 0.6).toFloat()
        }
        assertNull(analyze(x))
    }

    @Test fun silenceAndTooShortAreUnknown() {
        assertNull(analyze(FloatArray(sr * 30)))
        assertNull(analyze(clicks(120.0, secs = 5)))
    }

    @Test fun headAndTailCombineOrDrift() {
        val h = DjMode.Grid(120.0, 250, 0.8)
        assertEquals(240_100L, DjMode.combine(h, DjMode.Grid(120.4, 100, 0.8), 240_000)!!.tailBeatMs)
        // Tail in double time is the same pulse.
        assertEquals(120.0, DjMode.combine(h, DjMode.Grid(240.0, 0, 0.8), 1_000)!!.tailBpm, 1e-9)
        assertNull("3% drift", DjMode.combine(h, DjMode.Grid(123.6, 0, 0.8), 1_000))
        assertNull(DjMode.combine(h, null, 1_000))
        assertNull(DjMode.combine(null, h, 1_000))
    }

    @Test fun tempoMatchCapAndFallbacks() {
        val m = DjMode.tempoMatch(124.0, 120.0)
        assertTrue(m.matched); assertEquals(124.0 / 120.0, m.stretch, 1e-9)
        assertTrue(DjMode.tempoMatch(127.2, 120.0).matched)          // exactly +6 %
        assertTrue(DjMode.tempoMatch(112.8, 120.0).matched)          // exactly -6 %
        DjMode.tempoMatch(128.0, 120.0).let { assertFalse(it.matched); assertEquals(1.0, it.stretch, 0.0) } // +6.7 %
        DjMode.tempoMatch(110.0, 120.0).let { assertFalse(it.matched); assertEquals(1.0, it.stretch, 0.0) }
        DjMode.tempoMatch(null, 120.0).let { assertFalse(it.matched); assertEquals(1.0, it.stretch, 0.0) }
        DjMode.tempoMatch(120.0, null).let { assertFalse(it.matched); assertEquals(1.0, it.stretch, 0.0) }
        // Half/double time is matched on the nearest octave.
        DjMode.tempoMatch(170.0, 86.0).let { assertTrue(it.matched); assertEquals(170.0 / 172.0, it.stretch, 1e-9) }
    }

    @Test fun parametersKeepTheUsersPitchChoice() {
        assertEquals(1.04f to 1f, DjMode.parameters(1f, keepPitch = true, stretch = 1.04))
        val (sp, pitch) = DjMode.parameters(1.25f, keepPitch = false, stretch = 0.96)
        assertEquals(1.2f, sp, 1e-6f); assertEquals(sp, pitch, 0f)
        assertEquals(1.5f to 1f, DjMode.parameters(1.5f, keepPitch = true, stretch = 1.0)) // no DJ: user's speed exactly
    }

    @Test fun planLandsFirstBeatOnTheOutgoingGrid() {
        val out = DjMode.TrackBeats(124.0, 300, 124.0, 180_120)
        val inc = DjMode.TrackBeats(120.0, 480, 120.0, 0)
        val stretch = DjMode.tempoMatch(out.tailBpm, inc.bpm).stretch
        val dur = 200_000L; val window = dur - 8_000
        val p = DjMode.plan(out, dur, window, inc, stretch)!!
        assertTrue(p.outStartMs >= window); assertTrue(p.firstBeatAtOutMs < dur)
        // The landing point is an outgoing beat…
        val period = 60_000.0 / out.tailBpm
        val k = (p.firstBeatAtOutMs - out.tailBeatMs) / period
        assertEquals(Math.round(k).toDouble(), k, 0.02)
        // …and it is where the incoming first beat plays: start + (firstBeat - inStart) / stretch.
        assertEquals(p.firstBeatAtOutMs.toDouble(), p.outStartMs + (inc.firstBeatMs - p.inStartMs) / stretch, 2.0)
        assertEquals(0L, p.inStartMs)
    }

    @Test fun planSkipsWholeBeatsOfALongIntro() {
        val out = DjMode.TrackBeats(120.0, 0, 120.0, 100_000)
        val inc = DjMode.TrackBeats(120.0, 20_250, 120.0, 0) // first beat 20 s in
        val p = DjMode.plan(out, 200_000, 192_000, inc, 1.0)!!
        assertTrue(p.inStartMs > 0)
        assertEquals(0.0, ((20_250 - p.inStartMs) % 500).toDouble(), 1.0) // skipped a whole number of beats
        assertTrue(p.firstBeatAtOutMs in 192_000 until 200_000)
    }

    @Test fun storeKeepsKnownAndUnknownAcrossRestarts() {
        val f = File.createTempFile("bpm", ".tsv").apply { delete() }
        val a = BpmStore(f).apply { loadNow() }
        a.put("yt:abc", DjMode.TrackBeats(121.5, 230, 121.4, 190_010))
        a.put("js:def", null)
        a.flushForTest()
        val b = BpmStore(f).apply { loadNow() }
        assertTrue(b.has("js:def")); assertNull(b.beats("js:def")) // unknown is remembered: never re-analysed
        assertEquals(121.5, b.beats("yt:abc")!!.bpm, 1e-3)
        assertEquals(190_010L, b.beats("yt:abc")!!.tailBeatMs)
        assertFalse(b.has("yt:zzz"))
        f.delete()
    }
}
