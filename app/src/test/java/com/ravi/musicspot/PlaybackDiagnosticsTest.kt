package com.ravi.musicspot

import com.ravi.musicspot.core.PlaybackLog
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.MusicSource
import com.ravi.musicspot.source.Resolved
import com.ravi.musicspot.source.SourceManager
import com.ravi.musicspot.source.SourceUnavailableException
import com.ravi.musicspot.source.TrackMatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** v2.0.4: lenient YouTube → JioSaavn matching, fallback time budget, and playback log redaction. */
class PlaybackDiagnosticsTest {
    private class Src(override val kind: SourceKind, val results: List<Track> = emptyList(), val fail: Boolean = false, val slowMs: Long = 0) : MusicSource {
        val queries = mutableListOf<String>()
        override fun available() = true
        override suspend fun search(query: String, limit: Int): List<Track> { queries += query; return results }
        override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
            if (slowMs > 0) delay(slowMs)
            if (fail) throw SourceUnavailableException("${kind.label} down")
            return Resolved("https://example.invalid/${track.id}", StreamInfo(kind), cacheKey = track.id)
        }
        override suspend fun probe() = "ok"
    }

    private val ytKoyal = Track("yt:k", "Koyal | Duryodhan Rana | New Sambalpuri Song", "Duryodhan Rana - Topic", durationMs = 312_000)
    private val jsKoyal = Track("js:k", "Koyal", "Duryodhan Rana", durationMs = 268_000)

    @Test fun lenientMatcherAcceptsYouTubeTitleJunkAndVideoLength() {
        // Strict matching vetoes a 44 s longer video; the fallback (lenient) accepts the same song.
        assertNull(TrackMatcher.best(ytKoyal, listOf(jsKoyal)))
        assertEquals("js:k", TrackMatcher.best(ytKoyal, listOf(jsKoyal), TrackMatcher.LENIENT_THRESHOLD, lenient = true)?.id)
        assertEquals("koyal", TrackMatcher.cleanTitle(ytKoyal.title))
        assertTrue("duryodhan rana" in TrackMatcher.artists(ytKoyal.artist))
    }

    @Test fun lenientMatcherFindsArtistInsideTitleAndRejectsOtherSongs() {
        val upload = Track("yt:u", "Koyal (Official Video) - Duryodhan Rana", "Odia Hits Channel", durationMs = 270_000)
        assertNotNull(TrackMatcher.best(upload, listOf(jsKoyal), TrackMatcher.LENIENT_THRESHOLD, lenient = true))
        val other = Track("js:o", "Rangabati", "Jitendra Haripal", durationMs = 268_000)
        assertNull(TrackMatcher.best(ytKoyal, listOf(other), TrackMatcher.LENIENT_THRESHOLD, lenient = true))
        val remix = Track("js:r", "Koyal Remix", "Duryodhan Rana", durationMs = 268_000)
        assertNull(TrackMatcher.best(ytKoyal, listOf(remix), TrackMatcher.LENIENT_THRESHOLD, lenient = true))
    }

    @Test fun indicScriptKeepsVowelSigns() {
        // Devanagari vowel signs are combining marks; v2.0.3 stripped them ("कोयल" → "कयल"), merging different words.
        assertEquals("कोयल", TrackMatcher.norm("कोयल"))
        assertFalse(TrackMatcher.norm("कोयल") == TrackMatcher.norm("कयल"))
        assertEquals("beyonce", TrackMatcher.norm("Beyoncé"))
    }

    @Test fun searchQueriesAreClean() {
        val q = TrackMatcher.searchQueries(ytKoyal)
        assertEquals("Koyal Duryodhan Rana", q.first())
        assertEquals("Koyal", q[1])
    }

    @Test fun fallbackGetsItsOwnBudgetAfterSlowYouTube() = runTest {
        val yt = Src(SourceKind.YOUTUBE, slowMs = 60_000) // never answers within its budget
        val js = Src(SourceKind.JIOSAAVN, results = listOf(jsKoyal))
        val m = SourceManager(mapOf(SourceKind.YOUTUBE to yt, SourceKind.JIOSAAVN to js), { listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN) },
            { setOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN) }, { false }, { null })
        val r = m.resolve(ytKoyal, Quality.HIGH, false)
        assertEquals("js:k", r.cacheKey)
        assertEquals(SourceKind.YOUTUBE, r.fallbackFrom)
        assertEquals("Koyal Duryodhan Rana", js.queries.first())
    }

    @Test fun failureSummaryNamesEachSource() = runTest {
        val yt = Src(SourceKind.YOUTUBE, fail = true)
        val js = Src(SourceKind.JIOSAAVN, results = emptyList())
        val m = SourceManager(mapOf(SourceKind.YOUTUBE to yt, SourceKind.JIOSAAVN to js), { listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN) },
            { setOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN) }, { false }, { null })
        val attempt = PlaybackLog.Attempt("test")
        attempt.step("YouTube", "all clients refused (403)")
        try {
            withContext(attempt) { m.resolve(ytKoyal, Quality.HIGH, false) }
            fail("expected failure")
        } catch (e: SourceUnavailableException) {
            assertEquals("YouTube: all clients refused (403) · JioSaavn: no results", e.message)
        }
    }

    @Test fun redactsCookiesTokensAndQueryStrings() {
        val s = PlaybackLog.redact("GET https://rr3---sn-x.googlevideo.com/videoplayback?expire=1&sig=ABC&n=xyz Cookie: SAPISID=secret123; token=abc sp_dc=zzz")
        assertTrue(s, s.contains("https://rr3---sn-x.googlevideo.com/videoplayback?…"))
        assertFalse(s, s.contains("secret123")); assertFalse(s, s.contains("ABC")); assertFalse(s, s.contains("zzz")); assertFalse(s, s.contains("token=abc"))
    }

    @Test fun ringBufferKeepsLast300() {
        PlaybackLog.clear()
        repeat(350) { PlaybackLog.add("line $it") }
        assertEquals(PlaybackLog.MAX, PlaybackLog.size())
        assertTrue(PlaybackLog.text().endsWith("line 349"))
        assertFalse(PlaybackLog.text().contains("line 49\n"))
    }
}
