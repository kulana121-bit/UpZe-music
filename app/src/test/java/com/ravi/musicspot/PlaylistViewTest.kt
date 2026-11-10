package com.ravi.musicspot

import com.ravi.musicspot.logic.PlaylistSort
import com.ravi.musicspot.logic.PlaylistView
import com.ravi.musicspot.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistViewTest {
    private fun t(id: String, title: String, artist: String, ms: Long = 0) = Track("yt:$id", title, artist, durationMs = ms)
    private val list = listOf(t("1", "Kesariya", "Arijit Singh", 260), t("2", "Apna Bana Le", "Arijit Singh", 240), t("3", "Brown Munde", "AP Dhillon", 270))

    @Test fun searchMatchesTitleOrArtistAnyCase() {
        assertEquals(listOf("3"), PlaylistView.apply(list, "dhillon", PlaylistSort.CUSTOM).map { it.nativeId })
        assertEquals(listOf("2"), PlaylistView.apply(list, "arijit apna", PlaylistSort.CUSTOM).map { it.nativeId })
        assertTrue(PlaylistView.apply(list, "zzz", PlaylistSort.CUSTOM).isEmpty())
    }

    @Test fun sorts() {
        assertEquals(listOf("2", "3", "1"), PlaylistView.apply(list, "", PlaylistSort.TITLE).map { it.nativeId })
        assertEquals(listOf("3", "2", "1"), PlaylistView.apply(list, "", PlaylistSort.RECENT).map { it.nativeId })
        assertEquals(listOf("2", "1", "3"), PlaylistView.apply(list, "", PlaylistSort.DURATION).map { it.nativeId })
        assertEquals(listOf("3", "2", "1"), PlaylistView.apply(list, "", PlaylistSort.ARTIST).map { it.nativeId })
        assertEquals(PlaylistSort.CUSTOM, PlaylistSort.of("nope"))
    }
}
