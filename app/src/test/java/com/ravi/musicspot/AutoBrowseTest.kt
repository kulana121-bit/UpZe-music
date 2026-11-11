package com.ravi.musicspot

import com.ravi.musicspot.model.Track
import com.ravi.musicspot.playback.AutoBrowse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pack 6: Android Auto browse tree, pure (ids round-trip, tree shape, unknown ids fail cleanly). */
class AutoBrowseTest {
    private fun t(id: String, title: String = "Song $id", artist: String = "Artist $id") = Track(id, title, artist)
    private val a = t("yt:a|1", "Tum Hi Ho", "Arijit Singh")   // an id with characters that need encoding
    private val b = t("saavn:b/2", "Excuses", "AP Dhillon")
    private val c = t("local:3", "Kesariya", "Arijit Singh")
    private val lib = AutoBrowse.Lib(
        liked = listOf(a, b),
        playlists = listOf(AutoBrowse.Playlist("pl-x|y", "Road trip", listOf(c, a, b)), AutoBrowse.Playlist("pl-empty", "Empty", emptyList())),
        history = listOf(b, c),
        device = listOf(c),
    )

    @Test fun rootHasTheFourSectionsInOrder() {
        val kids = AutoBrowse.children(lib, AutoBrowse.ROOT)!!
        assertEquals(listOf(AutoBrowse.LIKED, AutoBrowse.PLAYLISTS, AutoBrowse.HISTORY, AutoBrowse.DEVICE), kids.map { it.id })
        assertEquals(listOf("Liked songs", "Playlists", "History", "On this device"), kids.map { it.title })
        assertTrue(kids.all { it.browsable && !it.playable })
    }

    @Test fun emptySectionsAndPlaylistsAreHidden() {
        assertEquals(listOf(AutoBrowse.LIKED), AutoBrowse.children(AutoBrowse.Lib(liked = listOf(a)), AutoBrowse.ROOT)!!.map { it.id })
        assertTrue(AutoBrowse.children(AutoBrowse.Lib(), AutoBrowse.ROOT)!!.isEmpty())
        val pls = AutoBrowse.children(lib, AutoBrowse.PLAYLISTS)!!
        assertEquals(listOf("Road trip"), pls.map { it.title }) // the empty playlist is not shipped
    }

    @Test fun playlistNodeListsItsTracksInOrderAndEveryOnePlays() {
        val pl = AutoBrowse.children(lib, AutoBrowse.PLAYLISTS)!!.single()
        val songs = AutoBrowse.children(lib, pl.id)!!
        assertEquals(listOf(c, a, b), songs.map { it.track })
        songs.forEachIndexed { i, s ->
            assertTrue(s.playable && !s.browsable)
            val play = AutoBrowse.resolve(lib, s.id)!!
            assertEquals(listOf(c, a, b), play.tracks)  // the queue is the playlist
            assertEquals(i, play.start)                 // starting at the tapped song
        }
    }

    @Test fun mediaIdsRoundTrip() {
        val pid = AutoBrowse.playlistId("pl-x|y")
        val sid = AutoBrowse.songId(pid, a.id)
        assertEquals(pid to a.id, AutoBrowse.parseSong(sid))
        assertEquals(a, AutoBrowse.node(lib, sid)!!.track)
        assertEquals("Road trip", AutoBrowse.node(lib, pid)!!.title)
        for (sec in listOf(AutoBrowse.LIKED, AutoBrowse.HISTORY, AutoBrowse.DEVICE)) for (s in AutoBrowse.children(lib, sec)!!) {
            assertEquals(s, AutoBrowse.node(lib, s.id))
            assertEquals(s.track, AutoBrowse.resolve(lib, s.id)!!.let { it.tracks[it.start] })
        }
        assertTrue(AutoBrowse.isPlayable(sid)); assertFalse(AutoBrowse.isPlayable(a.id)); assertFalse(AutoBrowse.isOurs(a.id))
    }

    @Test fun historyIsNewestFirstAsStored() {
        assertEquals(listOf(b, c), AutoBrowse.children(lib, AutoBrowse.HISTORY)!!.map { it.track })
    }

    @Test fun unknownOrStaleIdsFailCleanly() {
        for (bad in listOf("nope", "upze:nope", "upze:pl/missing", "upze:play|", "upze:play|upze:liked|", "upze:play|upze:pl/missing|x", "upze:play|upze:liked|%ZZ", "")) {
            assertNull(bad, AutoBrowse.resolve(lib, bad))
        }
        assertNull(AutoBrowse.children(lib, "upze:pl/missing"))
        assertNull(AutoBrowse.children(lib, "whatever"))
        assertNull(AutoBrowse.node(lib, "upze:play|upze:liked|" + "local%3A3")) // c is not in Liked
        // A song removed from its list after the head unit loaded it: no queue, not a crash.
        val sid = AutoBrowse.songId(AutoBrowse.LIKED, b.id)
        assertNull(AutoBrowse.resolve(lib.copy(liked = listOf(a)), sid))
    }

    @Test fun searchFindsEachSongOnceInItsSection() {
        val r = AutoBrowse.search(lib, "arijit")
        assertEquals(listOf(a, c), r.map { it.track })
        assertEquals(AutoBrowse.LIKED, AutoBrowse.parseSong(r[0].id)!!.first)                      // found first in Liked
        assertEquals(AutoBrowse.playlistId("pl-x|y"), AutoBrowse.parseSong(r[1].id)!!.first)       // then in the playlist
        r.forEach { assertTrue(AutoBrowse.resolve(lib, it.id) != null) }
        assertTrue(AutoBrowse.search(lib, "   ").isEmpty())
    }

    @Test fun paging() {
        val xs = (1..5).toList()
        assertEquals(listOf(1, 2), AutoBrowse.page(xs, 0, 2)); assertEquals(listOf(5), AutoBrowse.page(xs, 2, 2))
        assertTrue(AutoBrowse.page(xs, 3, 2).isEmpty()); assertEquals(xs, AutoBrowse.page(xs, 0, Int.MAX_VALUE))
    }
}
