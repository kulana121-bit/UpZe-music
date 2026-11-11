package com.ravi.musicspot

import com.ravi.musicspot.model.Track
import com.ravi.musicspot.recognition.RecognitionMatch
import com.ravi.musicspot.recognition.RecognizedSong
import com.ravi.musicspot.recognition.ShazamClient
import com.ravi.musicspot.recognition.ShazamSignature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

/** Pack 6 Part 2: song recognition — answer parsing, best-match pick, signature format. No network (except the gated live test). */
class RecognitionTest {
    private val song = RecognizedSong("Blinding Lights", "The Weeknd", "After Hours")

    @Test fun parsesAMatch() {
        val json = """{"matches":[{"id":"1"}],"track":{"title":"Blinding Lights","subtitle":"The Weeknd","isrc":"USUG11904206",
            "images":{"coverart":"https://x/y.jpg"},"sections":[{"type":"SONG","metadata":[{"title":"Album","text":"After Hours"}]}]}}"""
        val s = ShazamClient.parse(json)!!
        assertEquals("Blinding Lights", s.title); assertEquals("The Weeknd", s.artist)
        assertEquals("After Hours", s.album); assertEquals("USUG11904206", s.isrc); assertEquals("https://x/y.jpg", s.artUrl)
    }

    @Test fun noMatchAnswersAreNull() {
        assertNull(ShazamClient.parse("""{"matches":[],"tagid":"x"}"""))
        assertNull(ShazamClient.parse("""{"matches":[],"track":{"title":"a","subtitle":"b"}}"""))
        assertNull(ShazamClient.parse("not json"))
        assertNull(ShazamClient.parse("""{"matches":[{"id":"1"}],"track":{"title":"only a title"}}"""))
    }

    @Test fun picksTheBestMatchAcrossSources() {
        val yt = listOf(
            Track("yt:1", "Blinding Lights (Slowed + Reverb)", "Lofi Guy"),
            Track("yt:2", "Blinding Lights (Official Video)", "The Weeknd"),
        )
        val js = listOf(Track("js:1", "Blinding Lights", "The Weeknd"), Track("js:2", "Save Your Tears", "The Weeknd"))
        // The slowed/reverb upload never wins; the official upload (title junk ignored) ties with JioSaavn's exact
        // title, so the user's higher-priority source (first list) is picked.
        assertEquals("yt:2", RecognitionMatch.pick(song, listOf(yt, js))!!.id)
        assertEquals("js:1", RecognitionMatch.pick(song, listOf(js, yt))!!.id)
        assertEquals("js:1", RecognitionMatch.pick(song, listOf(yt.take(1), js))!!.id)
        // Equally good on two sources: the user's higher-priority source wins.
        val qobuz = listOf(Track("qz:9", "Blinding Lights", "The Weeknd"))
        assertEquals("qz:9", RecognitionMatch.pick(song, listOf(qobuz, js))!!.id)
    }

    @Test fun noPlayableMatchIsNull() {
        val wrong = listOf(Track("yt:1", "Blinding Lights", "Some Cover Band"), Track("yt:2", "Starboy", "The Weeknd"))
        assertNull(RecognitionMatch.pick(song, listOf(wrong)))
        assertNull(RecognitionMatch.pick(song, listOf(emptyList(), emptyList())))
        assertEquals("Blinding Lights The Weeknd", RecognitionMatch.query(song))
    }

    @Test fun isrcDecidesWhenBothHaveOne() {
        val s = song.copy(isrc = "USUG11904206")
        val c = listOf(Track("yt:1", "Blinding Lights", "The Weeknd", isrc = "XX0000000000"), Track("qz:1", "Blinding Lights", "The Weeknd", isrc = "USUG11904206"))
        assertEquals("qz:1", RecognitionMatch.pick(s, listOf(c))!!.id)
    }

    /** Tones that change every half second, 16 kHz mono. */
    private fun tones(secs: Int): ShortArray {
        val fr = doubleArrayOf(440.0, 660.0, 880.0, 1320.0, 2200.0, 3300.0, 4400.0, 523.0)
        return ShortArray(16_000 * secs) { i ->
            val seg = i / 8_000
            val f1 = fr[seg % fr.size]; val f2 = fr[(seg * 3 + 1) % fr.size]
            (8000 * sin(2 * PI * f1 * i / 16_000) + 6000 * sin(2 * PI * f2 * i / 16_000)).toInt().toShort()
        }
    }

    @Test fun signatureHasTheShazamLayout() {
        val sig = ShazamSignature().apply { feed(tones(8)) }
        assertTrue("peaks ${sig.peakCount}", sig.peakCount > 20)
        assertEquals(8_000L, sig.durationMs)
        val b = sig.encode()
        val le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xCAFE2580.toInt(), le.getInt(0))
        assertEquals(CRC32().apply { update(b, 8, b.size - 8) }.value.toInt(), le.getInt(4))
        assertEquals(b.size - 48, le.getInt(8))
        assertEquals(0x94119C00.toInt(), le.getInt(12))
        assertEquals(3 shl 27, le.getInt(28))
        assertEquals((128_000 + 16_000 * 0.24).toInt(), le.getInt(40))
        assertEquals(0x40000000, le.getInt(48))
        assertEquals(b.size - 48, le.getInt(52))
        // Every band chunk is tagged 0x60030040 + band and padded to 4 bytes.
        var p = 56
        while (p < b.size) {
            val band = le.getInt(p) - 0x60030040
            assertTrue("band $band", band in 0..3)
            val len = le.getInt(p + 4)
            p += 8 + len + (4 - len % 4) % 4
        }
        assertEquals(b.size, p)
        assertTrue(sig.uri().startsWith(ShazamSignature.DATA_URI_PREFIX))
    }

    @Test fun silenceHasNoPeaks() {
        assertEquals(0, ShazamSignature().apply { feed(ShortArray(16_000 * 5)) }.peakCount)
    }

    /**
     * Live end-to-end check (skipped unless MUSICSPOT_SHAZAM_PCM points at a raw 16 kHz mono s16le file of a known
     * song and MUSICSPOT_SHAZAM_EXPECT holds a word of its title): our signature → Shazam → the right song.
     */
    @Test fun liveIdentify() = runBlocking {
        val path = System.getenv("MUSICSPOT_SHAZAM_PCM"); val expect = System.getenv("MUSICSPOT_SHAZAM_EXPECT")
        assumeTrue("set MUSICSPOT_SHAZAM_PCM and MUSICSPOT_SHAZAM_EXPECT", path != null && expect != null)
        val bytes = File(path!!).readBytes()
        val all = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val start = 16_000 * 8
        val pcm = all.copyOfRange(start, minOf(all.size, start + 16_000 * 10))
        val sig = ShazamSignature().apply { feed(pcm) }
        val s = ShazamClient.identify(sig)
        println("LIVE: ${sig.peakCount} peaks → $s")
        assertTrue("got $s", s != null && s.title.contains(expect!!, ignoreCase = true))
    }
}
