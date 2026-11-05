package com.ravi.musicspot

import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.MusicSource
import com.ravi.musicspot.source.QualityEstimate
import com.ravi.musicspot.source.Resolved
import com.ravi.musicspot.source.SourceManager
import com.ravi.musicspot.source.SourceRouter
import com.ravi.musicspot.source.SourceUnavailableException
import com.ravi.musicspot.source.TrackMatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun t(id: String, title: String = "Blinding Lights", artist: String = "The Weeknd", dur: Long = 200_000, format: String? = null, bitrate: Int? = null, isrc: String? = null) =
    Track(id, title, artist, durationMs = dur, format = format, bitrateKbps = bitrate, isrc = isrc)

class SourceRouterTest {
    private val yt = t("yt:abc"); private val js = t("js:def"); private val au = t("au:1"); private val sp = t("sp:9")
    private val all = SourceKind.entries.toSet()

    @Test fun autoFollowsUserPriority() {
        val p1 = SourceRouter.plan(yt, listOf(js, au), listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN, SourceKind.AUDIUS), all, Quality.AUTO)
        assertEquals(listOf("yt:abc", "js:def", "au:1"), p1.map { it.id })
        val p2 = SourceRouter.plan(yt, listOf(js, au), listOf(SourceKind.AUDIUS, SourceKind.JIOSAAVN, SourceKind.YOUTUBE), all, Quality.HIGH)
        assertEquals(listOf("au:1", "js:def", "yt:abc"), p2.map { it.id })
    }

    @Test fun unknownPriorityGoesLast() {
        val p = SourceRouter.plan(yt, listOf(js), listOf(SourceKind.JIOSAAVN), all, Quality.AUTO)
        assertEquals(listOf("js:def", "yt:abc"), p.map { it.id })
    }

    @Test fun disabledAndMetadataOnlyAreDropped() {
        val p = SourceRouter.plan(sp, listOf(yt, js, au), SourceKind.entries.toList(), setOf(SourceKind.YOUTUBE, SourceKind.AUDIUS, SourceKind.SPOTIFY), Quality.AUTO)
        assertEquals(listOf("yt:abc", "au:1"), p.map { it.id })
    }

    @Test fun duplicatesRemoved() {
        val p = SourceRouter.plan(yt, listOf(yt, js), SourceKind.entries.toList(), all, Quality.AUTO)
        assertEquals(listOf("yt:abc", "js:def"), p.map { it.id })
    }

    @Test fun localOriginalAlwaysPlayable() {
        val local = t("local:42")
        val p = SourceRouter.plan(local, emptyList(), SourceKind.entries.toList(), emptySet(), Quality.AUTO)
        assertEquals(listOf("local:42"), p.map { it.id })
    }

    @Test fun maxPrefersQualityOverPriority() {
        // YT 160 kbps (non-premium) loses to JioSaavn 320 even though YT is first in priority.
        val p = SourceRouter.plan(yt, listOf(js), listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN), all, Quality.MAX)
        assertEquals(listOf("js:def", "yt:abc"), p.map { it.id })
    }

    @Test fun losslessPutsFlacFirstAndPriorityBreaksTies() {
        val flac = t("sub:s:1", format = "flac")
        val p = SourceRouter.plan(yt, listOf(js, au, flac), listOf(SourceKind.AUDIUS, SourceKind.JIOSAAVN, SourceKind.YOUTUBE, SourceKind.SUBSONIC), all, Quality.LOSSLESS)
        assertEquals(listOf("sub:s:1", "au:1", "js:def", "yt:abc"), p.map { it.id }) // au & js both 320: priority decides
    }

    @Test fun sourcesToSearchAutoOnlyHigherPriority() {
        val k = SourceRouter.sourcesToSearch(js, listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN, SourceKind.AUDIUS), all, Quality.AUTO, false)
        assertEquals(listOf(SourceKind.YOUTUBE), k)
    }

    @Test fun sourcesToSearchSpotifySearchesEverythingEnabled() {
        val k = SourceRouter.sourcesToSearch(sp, listOf(SourceKind.YOUTUBE, SourceKind.SOUNDCLOUD, SourceKind.JIOSAAVN), setOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN, SourceKind.SOUNDCLOUD), Quality.AUTO, false)
        assertEquals(listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN), k) // SoundCloud is not searchable for matches
    }

    @Test fun sourcesToSearchMaxOnlyBetter() {
        val k = SourceRouter.sourcesToSearch(t("js:x"), listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN, SourceKind.SUBSONIC, SourceKind.JAMENDO), all, Quality.LOSSLESS, false)
        assertEquals(listOf(SourceKind.SUBSONIC), k)
    }
}

class QualityEstimateTest {
    @Test fun ranks() {
        assertEquals(160, QualityEstimate.rank(t("yt:a"), Quality.AUTO, premium = false))
        assertEquals(256, QualityEstimate.rank(t("yt:a"), Quality.AUTO, premium = true))
        assertEquals(320, QualityEstimate.rank(t("js:a"), Quality.MAX, false))
        assertEquals(128, QualityEstimate.rank(t("sc:a"), Quality.MAX, false))
        assertEquals(0, QualityEstimate.rank(t("sp:a"), Quality.MAX, false))
        assertEquals(1411, QualityEstimate.rank(t("jf:s:1", format = "FLAC"), Quality.LOSSLESS, false))
        assertEquals(300, QualityEstimate.rank(t("jf:s:1", format = "flac"), Quality.HIGH, false))
        assertEquals(1411, QualityEstimate.rank(t("local:1", format = "wav"), Quality.HIGH, false))
        assertEquals(96, QualityEstimate.rank(t("au:1", bitrate = 96), Quality.MAX, false)) // known bitrate wins
    }

    @Test fun losslessFormats() {
        assertTrue(QualityEstimate.isLosslessFormat("ALAC"))
        assertTrue(!QualityEstimate.isLosslessFormat("mp3"))
        assertTrue(!QualityEstimate.isLosslessFormat(null))
    }

    @Test fun losslessOrderingStrict() {
        val ordered = listOf(t("local:1", format = "flac"), t("js:a"), t("yt:a"), t("sc:a")).sortedByDescending { QualityEstimate.rank(it, Quality.LOSSLESS, false) }
        assertEquals(listOf("local:1", "js:a", "yt:a", "sc:a"), ordered.map { it.id })
    }
}

class TrackMatcherTest {
    private val spot = t("sp:1", "Blinding Lights", "The Weeknd", 200_040)

    @Test fun isrcIsDecisive() {
        assertEquals(1.0, TrackMatcher.score(t("sp:1", isrc = "USUG11904206"), t("js:1", "Totally different", "X", isrc = "usug11904206")), 0.0)
        assertEquals(0.0, TrackMatcher.score(t("sp:1", isrc = "AAA"), t("js:1", isrc = "BBB")), 0.0)
    }

    @Test fun exactMatchScoresHigh() {
        assertTrue(TrackMatcher.score(spot, t("yt:a", "Blinding Lights", "The Weeknd", 201_000)) > 0.95)
    }

    @Test fun bracketsFeatAndAccentsIgnored() {
        assertTrue(TrackMatcher.score(spot, t("yt:a", "Blinding Lights (Official Audio)", "The Weeknd", 202_000)) >= TrackMatcher.THRESHOLD)
        val a = t("sp:2", "Señorita", "Shawn Mendes, Camila Cabello", 191_000)
        val b = t("yt:b", "Senorita (feat. Camila Cabello)", "Shawn Mendes", 190_000)
        assertTrue(TrackMatcher.score(a, b) >= TrackMatcher.THRESHOLD)
        val c = t("js:c", "Song - 2011 Remaster", "Artist & Friend", 180_000)
        assertTrue(TrackMatcher.score(t("sp:3", "Song", "Artist", 181_000), c) >= TrackMatcher.THRESHOLD)
    }

    @Test fun remixDoesNotMatchOriginal() {
        assertTrue(TrackMatcher.score(spot, t("yt:r", "Blinding Lights (Remix)", "The Weeknd", 200_000)) < TrackMatcher.THRESHOLD)
        assertTrue(TrackMatcher.score(spot, t("yt:r", "Blinding Lights - Live", "The Weeknd", 200_000)) < TrackMatcher.THRESHOLD)
    }

    @Test fun durationGate() {
        assertEquals(0.0, TrackMatcher.score(spot, t("yt:a", "Blinding Lights", "The Weeknd", 230_000)), 0.0)
        assertTrue(TrackMatcher.score(spot, t("yt:a", "Blinding Lights", "The Weeknd", 0)) >= TrackMatcher.THRESHOLD) // unknown duration
    }

    @Test fun wrongArtistRejected() {
        assertTrue(TrackMatcher.score(spot, t("yt:a", "Blinding Lights", "Some Cover Band", 200_000)) < TrackMatcher.THRESHOLD)
    }

    @Test fun bestPicksHighestAboveThreshold() {
        val cands = listOf(
            t("js:1", "Blinding Lights (Remix)", "The Weeknd", 200_000),
            t("js:2", "Blinding Lights", "The Weeknd", 209_000),
            t("js:3", "Blinding Lights", "The Weeknd", 200_500),
        )
        assertEquals("js:3", TrackMatcher.best(spot, cands)?.id)
        assertNull(TrackMatcher.best(spot, listOf(t("js:9", "Save Your Tears", "The Weeknd", 215_000))))
    }

    @Test fun normalisation() {
        assertEquals("beyonce and jay z", TrackMatcher.norm("Beyoncé & JAY-Z"))
        assertEquals(setOf("a", "b", "c"), TrackMatcher.artists("A, B feat. C"))
    }
}

/** Fake source for failover tests. */
private class FakeSource(
    override val kind: SourceKind,
    private val results: List<Track> = emptyList(),
    private val fail: Boolean = false,
) : MusicSource {
    val resolved = mutableListOf<String>()
    override fun available() = true
    override suspend fun search(query: String, limit: Int) = results
    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        resolved += track.id
        if (fail) throw SourceUnavailableException("${kind.label} down")
        return Resolved("https://example.invalid/${track.id}", StreamInfo(kind), cacheKey = track.id)
    }
    override suspend fun probe() = "ok"
}

class SourceManagerFailoverTest {
    private fun mgr(vararg s: FakeSource, prio: List<SourceKind>, downloaded: (String) -> Resolved? = { null }) =
        SourceManager(s.associateBy { it.kind }, { prio }, { s.map { it.kind }.toSet() }, { false }, downloaded)

    @Test fun failsOverToNextSource() = runTest {
        val ytMatch = t("yt:m")
        val yt = FakeSource(SourceKind.YOUTUBE, results = listOf(ytMatch), fail = true)
        val js = FakeSource(SourceKind.JIOSAAVN)
        val failed = mutableListOf<String>()
        val r = mgr(yt, js, prio = listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN)).resolve(t("js:orig"), Quality.AUTO, false) { c, _ -> failed += c.id }
        assertEquals(listOf("yt:m"), yt.resolved)
        assertEquals(listOf("yt:m"), failed)
        assertEquals("js:orig", r.cacheKey)
    }

    @Test fun priorityWinnerUsedWhenHealthy() = runTest {
        val yt = FakeSource(SourceKind.YOUTUBE, results = listOf(t("yt:m")))
        val js = FakeSource(SourceKind.JIOSAAVN)
        val r = mgr(yt, js, prio = listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN)).resolve(t("js:orig"), Quality.AUTO, false)
        assertEquals("yt:m", r.cacheKey)
        assertTrue(js.resolved.isEmpty())
    }

    @Test fun allFailThrowsLastError() = runTest {
        val yt = FakeSource(SourceKind.YOUTUBE, results = listOf(t("yt:m")), fail = true)
        val js = FakeSource(SourceKind.JIOSAAVN, fail = true)
        try {
            mgr(yt, js, prio = listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN)).resolve(t("js:orig"), Quality.AUTO, false)
            fail("expected failure")
        } catch (e: SourceUnavailableException) { assertEquals("JioSaavn down", e.message) }
    }

    @Test fun downloadWinsWithoutNetwork() = runTest {
        val yt = FakeSource(SourceKind.YOUTUBE, fail = true)
        val dl = Resolved("file:///d/yt_a", StreamInfo(SourceKind.YOUTUBE), cacheKey = "dl")
        val r = mgr(yt, prio = listOf(SourceKind.YOUTUBE)) { if (it == "yt:a") dl else null }.resolve(t("yt:a"), Quality.HIGH, false)
        assertEquals("dl", r.cacheKey)
        assertTrue(yt.resolved.isEmpty())
    }

    @Test fun spotifyWithNoMatchExplains() = runTest {
        val yt = FakeSource(SourceKind.YOUTUBE, results = listOf(t("yt:x", "Other Song", "Other", 100_000)))
        try {
            mgr(yt, prio = listOf(SourceKind.YOUTUBE)).resolve(t("sp:1"), Quality.AUTO, false)
            fail("expected failure")
        } catch (e: SourceUnavailableException) { assertTrue(e.message!!.contains("Spotify")) }
    }

    @Test fun spotifyPlaysThroughMatch() = runTest {
        val js = FakeSource(SourceKind.JIOSAAVN, results = listOf(t("js:m")))
        val r = mgr(js, prio = listOf(SourceKind.JIOSAAVN)).resolve(t("sp:1"), Quality.AUTO, false)
        assertEquals("js:m", r.cacheKey)
    }
}
