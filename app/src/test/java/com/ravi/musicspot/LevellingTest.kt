package com.ravi.musicspot

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.ravi.musicspot.dsp.ReplayGainMode
import com.ravi.musicspot.playback.Gain
import com.ravi.musicspot.playback.GainPlan
import com.ravi.musicspot.playback.LoudnessMeter
import com.ravi.musicspot.playback.Measured
import com.ravi.musicspot.playback.ReplayGain
import com.ravi.musicspot.playback.ReplayGainTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** 1.0.10: ReplayGain parsing, loudness measurement and the single gain decision (precedence, no clipping). */
class LevellingTest {
    private val tags = ReplayGainTags(track = Gain(-7.5, 0.98), album = Gain(-6.0, 0.99))
    private val loud = Measured(lufs = -9.0, peak = 1.0)
    private val quiet = Measured(lufs = -30.0, peak = 0.25)

    @Test fun replayGainWinsOverNormalisation() {
        val d = GainPlan.decide(ReplayGainMode.TRACK, normalize = true, tags = tags, measured = loud)
        assertEquals(GainPlan.Source.REPLAY_GAIN, d.source)
        assertEquals(-7.5, d.gainDb, 1e-9) // only the tag, never tag + normalisation
        assertEquals(-6.0, GainPlan.decide(ReplayGainMode.ALBUM, true, tags, loud).gainDb, 1e-9)
    }

    @Test fun normalisationFillsInWithoutTagsOrWhenReplayGainOff() {
        assertEquals(GainPlan.Source.LOUDNESS, GainPlan.decide(ReplayGainMode.TRACK, true, null, loud).source)
        assertEquals(-9.0, GainPlan.decide(ReplayGainMode.OFF, true, tags, loud).gainDb, 1e-9)
        assertEquals(GainPlan.Source.NONE, GainPlan.decide(ReplayGainMode.OFF, false, tags, loud).source)
        assertEquals(0.0, GainPlan.decide(ReplayGainMode.TRACK, false, null, loud).gainDb, 1e-9)
    }

    @Test fun albumModeFallsBackToTrackGain() {
        val onlyTrack = ReplayGainTags(track = Gain(-3.0, 0.9))
        assertEquals(-3.0, GainPlan.decide(ReplayGainMode.ALBUM, false, onlyTrack, null).gainDb, 1e-9)
    }

    @Test fun boostsAreCappedAndNeverClip() {
        // -30 LUFS wants +12 dB; capped at 6 dB, and the 0.25 peak (≈ -12 dBFS) leaves 11 dB of room.
        val d = GainPlan.decide(ReplayGainMode.OFF, true, null, quiet)
        assertEquals(GainPlan.MAX_BOOST_DB, d.gainDb, 1e-9)
        // A quiet track peaking at 0.5 (≈ -6 dBFS) can only rise to 1 dB under full scale (≈ +5 dB).
        val g = GainPlan.decide(ReplayGainMode.OFF, true, null, Measured(-30.0, 0.5)).gainDb
        assertEquals(-20 * kotlin.math.log10(0.5) - GainPlan.HEADROOM_DB, g, 1e-9)
        assert(0.5 * GainPlan.linear(g) <= 1.0)
        // Peaking at 0.95 leaves under 1 dB of room: no boost at all.
        assertEquals(0.0, GainPlan.decide(ReplayGainMode.OFF, true, null, Measured(-30.0, 0.95)).gainDb, 1e-9)
        // ReplayGain boost without a known peak: no boost.
        assertEquals(0.0, GainPlan.decide(ReplayGainMode.TRACK, false, ReplayGainTags(Gain(+4.0, null)), null).gainDb, 1e-9)
        // First play (provisional estimate): cuts only.
        assertEquals(0.0, GainPlan.decide(ReplayGainMode.OFF, true, null, quiet, provisional = true).gainDb, 1e-9)
        assertEquals(-9.0, GainPlan.decide(ReplayGainMode.OFF, true, null, loud, provisional = true).gainDb, 1e-9)
    }

    @Test fun parsesTagFormats() {
        val t = ReplayGain.fromPairs(listOf("replaygain_track_gain" to "-7.89 dB", "REPLAYGAIN_TRACK_PEAK" to "0.988525",
            "REPLAYGAIN_ALBUM_GAIN" to "+1,20 dB", "REPLAYGAIN_ALBUM_PEAK" to "1.0"))!!
        assertEquals(-7.89, t.track!!.gainDb, 1e-9); assertEquals(0.988525, t.track!!.peak!!, 1e-9)
        assertEquals(1.2, t.album!!.gainDb, 1e-9)
        val opus = ReplayGain.fromPairs(listOf("R128_TRACK_GAIN" to "-512"))!!
        assertEquals(-2.0 + 5.0, opus.track!!.gainDb, 1e-9) // -2 dB vs -23 LUFS = +3 dB vs the -18 LUFS reference
        assertNull(ReplayGain.fromPairs(listOf("TITLE" to "x")))
    }

    @Test fun parsesMedia3Metadata() {
        val md = Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-4.00 dB"),
            TextInformationFrame("TXXX", "REPLAYGAIN_ALBUM_GAIN", listOf("-5.50 dB")))
        val t = ReplayGain.fromMetadata(listOf(md))
        assertNotNull(t)
        assertEquals(-4.0, t!!.track!!.gainDb, 1e-9)
        assertEquals(-5.5, t.album!!.gainDb, 1e-9)
    }

    @Test fun meterMatchesBs1770Reference() {
        // BS.1770: a 0 dBFS 997 Hz sine in one channel reads -3.01 LUFS; at -20 dBFS, -23.01 LUFS.
        for ((amp, want) in listOf(1.0 to -3.01, 0.1 to -23.01)) {
            val rate = 48_000
            val m = LoudnessMeter(rate, 1)
            val x = FloatArray(rate * 5) { (amp * sin(2 * PI * 997.0 * it / rate)).toFloat() }
            m.feed(x, x.size)
            assertEquals(want, m.integrated()!!, 0.1)
            assertEquals(amp, m.peak, 1e-3)
        }
        assertNull(LoudnessMeter(44_100, 2).integrated())
    }
}
