package com.ravi.musicspot

import com.ravi.musicspot.logic.PlayStat
import com.ravi.musicspot.logic.ReplayPolicy
import com.ravi.musicspot.logic.Taste
import com.ravi.musicspot.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.0.5: "On repeat" ranking, autoplay dedupe/re-rank, play counting threshold, and the automatic replay cap. */
class TasteTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private fun t(id: String, title: String = id, artist: String = "Artist $id") = Track(id, title, artist, durationMs = 200_000)

    // ---- On repeat ----------------------------------------------------------------------------------------------
    @Test fun favouritesRankByPlaysRepeatsAndLikes() {
        val stats = listOf(
            PlayStat(t("a"), plays = 5, repeats = 0, lastMs = now),      // 5
            PlayStat(t("b"), plays = 2, repeats = 2, lastMs = now),      // 2 + 4 = 6
            PlayStat(t("c"), plays = 3, repeats = 0, lastMs = now),      // 3, +3 liked = 6 → tie with b, title order
            PlayStat(t("d"), plays = 1, repeats = 0, lastMs = now),      // 1 play, not liked → not a favourite
        )
        val r = Taste.favourites(stats, liked = listOf(t("c")), nowMs = now).map { it.id }
        assertEquals(listOf("b", "c", "a"), r)
    }

    @Test fun recencyDecayLetsFreshPlaysOvertakeOldOnes() {
        val stats = listOf(
            PlayStat(t("old"), plays = 10, lastMs = now - 90 * day), // 10 × 2^(-90/21) ≈ 0.51
            PlayStat(t("new"), plays = 3, lastMs = now - day),       // ≈ 2.9
        )
        assertEquals(listOf("new", "old"), Taste.favourites(stats, emptyList(), now).map { it.id })
        assertEquals(0.5, Taste.decay(now - 21 * day, now), 1e-9)
    }

    @Test fun likedSongWithoutPlaysQualifiesAndEmptyWhenNothing() {
        assertEquals(listOf("x"), Taste.favourites(emptyList(), listOf(t("x")), now).map { it.id })
        assertTrue(Taste.favourites(listOf(PlayStat(t("one"), plays = 1, lastMs = now)), emptyList(), now).isEmpty())
    }

    @Test fun favouritesCollapseTheSameSongFromTwoSources() {
        val stats = listOf(
            PlayStat(t("yt:1", "Koyal (Official Video)", "Duryodhan Rana"), plays = 4, lastMs = now),
            PlayStat(t("js:1", "Koyal", "Duryodhan Rana"), plays = 2, lastMs = now),
        )
        assertEquals(listOf("yt:1"), Taste.favourites(stats, emptyList(), now).map { it.id })
    }

    @Test fun playCountsAfterThirtySecondsOrHalfAShortSong() {
        assertEquals(30_000L, Taste.countThresholdMs(200_000))
        assertEquals(20_000L, Taste.countThresholdMs(40_000))
        assertEquals(30_000L, Taste.countThresholdMs(0)) // unknown duration
    }

    // ---- Autoplay mix -----------------------------------------------------------------------------------------
    @Test fun autoplayDedupesAgainstQueueHistoryAndItself() {
        val queue = listOf(t("q1", "Song Q", "A"))
        val recent = listOf(t("h1", "Heard Before", "B"))
        val cands = listOf(
            t("q1", "Song Q", "A"),                          // same id as queued
            t("yt:z", "Song Q (Lyric Video)", "A"),           // same song as queued, other id
            t("h2", "Heard Before", "B"),                     // in history by title+artist
            t("n1", "New One", "C"), t("n1", "New One", "C"), // duplicate candidate
            t("n2", "New Two", "D"),
            t("blank", "", "E"),
        )
        val r = Taste.autoplayMix(cands, queue, recent, emptyMap(), emptySet(), n = 10).map { it.id }
        assertEquals(listOf("n1", "n2"), r)
    }

    @Test fun autoplayBoostsLikedAndFrequentlyPlayedArtists() {
        val cands = listOf(t("1", "One", "Stranger"), t("2", "Two", "Stranger"), t("3", "Three", "Fav Artist"), t("4", "Four", "Other"), t("5", "Five", "Other2"))
        val stats = listOf(PlayStat(t("p", "Played", "Fav Artist"), plays = 8, lastMs = now))
        val weights = Taste.artistWeights(stats, emptyList(), now)
        val r = Taste.autoplayMix(cands, emptyList(), emptyList(), weights, likedIds = setOf("4"), n = 3).map { it.id }
        // Base 1.0/0.8/0.6/0.4/0.2: Fav Artist's "3" → 1.4 (1st), liked "4" → 0.9 (above "2" at 0.8); only 3 returned.
        assertEquals(listOf("3", "1", "4"), r)
    }

    @Test fun autoplayKeepsRecommenderOrderWithoutTaste() {
        val cands = (1..15).map { t("c$it") }
        assertEquals((1..10).map { "c$it" }, Taste.autoplayMix(cands, emptyList(), emptyList(), emptyMap(), emptySet()).map { it.id })
    }

    @Test fun artistWeightsSplitFeaturedArtists() {
        val w = Taste.artistWeights(listOf(PlayStat(t("k", "Koyal", "Jaiveer Thakur, Duryodhan Rana"), plays = 2, lastMs = now)), emptyList(), now)
        assertEquals(2.0, w["duryodhan rana"]!!, 1e-9)
        assertEquals(2.0, w["jaiveer thakur"]!!, 1e-9)
    }

    // ---- Replay cap -------------------------------------------------------------------------------------------
    @Test fun atMostTwoAutomaticReplaysPerTrackThenStop() {
        val p = ReplayPolicy()
        assertEquals(ReplayPolicy.Decision.REPLAY, p.onError("t", youTubeRefused = true))
        assertEquals(ReplayPolicy.Decision.REPLAY_WITHOUT_YOUTUBE, p.onError("t", youTubeRefused = true))
        repeat(6) { assertEquals(ReplayPolicy.Decision.STOP, p.onError("t", youTubeRefused = true)) }
        assertEquals(2, p.replays("t"))
    }

    @Test fun replayCapIsPerTrackAndResetsOnTrackChange() {
        val p = ReplayPolicy()
        p.onError("a", false); p.onError("a", false)
        assertEquals(ReplayPolicy.Decision.STOP, p.onError("a", false))
        assertEquals(ReplayPolicy.Decision.REPLAY, p.onError("b", false))
        p.keepOnly("b")
        assertEquals(0, p.replays("a"))
        assertEquals(ReplayPolicy.Decision.REPLAY, p.onError("a", false))
        // Non-refusal errors never drop YouTube; offline never replays.
        assertEquals(ReplayPolicy.Decision.REPLAY, p.onError("a", false))
        assertEquals(ReplayPolicy.Decision.STOP, ReplayPolicy().onError("c", true, online = false))
        assertFalse(ReplayPolicy.MAX_AUTO_REPLAYS > 2)
    }
}
