package com.ravi.musicspot

import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.yt.BrowseType
import com.ravi.musicspot.yt.InnertubeParser
import com.ravi.musicspot.yt.SearchResult
import com.ravi.musicspot.yt.YtFormats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.10: fixture tests for the three Innertube shapes playback depends on: search results, album/playlist pages and
 * stream format selection. The fixtures in src/test/resources/innertube are small hand-written trims of the real
 * responses (only the paths the parser reads). If YouTube moves one of those paths, the parser returns empty/null and
 * these tests fail instead of playback breaking silently.
 */
class InnertubeParserTest {
    private fun fixture(name: String): JsonObject {
        val text = requireNotNull(javaClass.classLoader?.getResource("innertube/$name")) { "missing fixture $name" }.readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    // ---- (1) search results -------------------------------------------------------------------------------------

    @Test fun searchAllTabReadsAlbumAndSongAndDropsVideo() {
        val page = InnertubeParser.parseSearchPage(fixture("search.json"))
        assertEquals("album row + song row; the music-video row stays off the All tab", 2, page.rows.size)

        val album = (page.rows[0] as SearchResult.Browse).item
        assertEquals("MPREb_album1", album.browseId)
        assertEquals("Brahmastra", album.title)
        assertEquals("Album • Pritam • 2022", album.subtitle)
        assertEquals("https://img/album1", album.thumbnailUrl)
        assertEquals(BrowseType.ALBUM, album.type)
        assertTrue("explicit badge", album.explicit)

        val song = (page.rows[1] as SearchResult.Track).song
        assertEquals("vidSong1", song.videoId)
        assertEquals("Kesariya", song.title)
        assertEquals("Arijit Singh", song.artist)
        assertEquals("UCarijit", song.artistId)
        assertEquals("MPREb_album1", song.albumId)
        assertEquals("Brahmastra", song.albumName)
        assertEquals("4:28", song.durationText)
        assertEquals("https://img/song1", song.thumbnailUrl)
        assertFalse(song.isVideo)
        assertNull("search rows carry no playlistSetVideoId", song.setVideoId)

        assertEquals("CONT_SEARCH", page.continuation)
        assertEquals(listOf("vidSong1"), InnertubeParser.parseSearchSongs(fixture("search.json")).map { it.videoId })
    }

    @Test fun searchVideosTabKeepsOnlyTheVideoUpload() {
        val page = InnertubeParser.parseSearchPage(fixture("search.json"), includeVideos = true)
        val tracks = page.rows.filterIsInstance<SearchResult.Track>().map { it.song }
        assertEquals(listOf("vidVideo1"), tracks.map { it.videoId })
        assertTrue(tracks.single().isVideo)
        assertEquals("4:30", tracks.single().durationText)
    }

    // ---- (2) album / playlist pages -----------------------------------------------------------------------------

    @Test fun albumPageRowsTakeTheHeaderCreditAndFixedColumnDuration() {
        val root = fixture("album.json")
        assertNull("an album is not playlist-shaped; callers fall back to the deep walk", InnertubeParser.parsePlaylistShelf(root))

        val header = assertNotNullAnd(InnertubeParser.parseBrowseHeader(root))
        assertEquals("Brahmastra", header.title)
        assertEquals("Pritam • Album • 2022", header.subtitle)
        assertEquals("https://img/albumcover", header.thumbnailUrl)

        val songs = InnertubeParser.collectSongsDeep(root)
        assertEquals(listOf("aT1", "aT2"), songs.map { it.videoId })
        assertEquals(listOf("Kesariya", "Deva Deva"), songs.map { it.title })
        songs.forEach {
            assertEquals("header credit, not the play count", "Pritam", it.artist)
            assertEquals("UCpritam", it.artistId)
        }
        assertEquals(listOf("4:28", "4:39"), songs.map { it.durationText })
    }

    @Test fun playlistPageSplitsOwnTracksFromSuggestions() {
        val page = assertNotNullAnd(InnertubeParser.parsePlaylistShelf(fixture("playlist.json")))
        assertEquals("duplicate row shown once", listOf("p1", "p2"), page.songs.map { it.videoId })
        assertEquals(listOf("SET_p1", "SET_p2"), page.songs.map { it.setVideoId })
        assertEquals("Arijit Singh", page.songs[0].artist)
        assertEquals("UCarijit", page.songs[0].artistId)
        assertEquals("4:28", page.songs[0].durationText)
        assertEquals("suggestions exclude songs already in the playlist", listOf("s1"), page.suggested.map { it.videoId })
        assertNull(page.suggested.single().setVideoId)
        assertEquals("only the playlist shelf's own continuation pages tracks", "CONT_PLAYLIST", page.continuation)

        val header = assertNotNullAnd(InnertubeParser.parseBrowseHeader(fixture("playlist.json")))
        assertEquals("Road Trip", header.title)
    }

    @Test fun playlistContinuationAppendShape() {
        val page = InnertubeParser.parsePlaylistContinuation(fixture("playlist_continuation.json"))
        assertEquals(listOf("p3"), page.songs.map { it.videoId })
        assertEquals("Raataan Lambiyan", page.songs.single().title)
        assertEquals("Jubin Nautiyal", page.songs.single().artist)
        assertEquals("SET_p3", page.songs.single().setVideoId)
        assertEquals("CONT_PLAYLIST_2", page.continuation)
    }

    // ---- (3) stream format selection ----------------------------------------------------------------------------

    @Test fun formatsParseAudioOnlyWithUrlOrCipher() {
        val formats = YtFormats.parse(fixture("player_formats.json"))
        assertEquals("video itag 137 is dropped", listOf(140, 251, 774, 139), formats.map { it.itag })
        val byItag = formats.associateBy { it.itag }
        byItag.getValue(140).let {
            assertEquals("https://a/140", it.url); assertEquals(130, it.kbps); assertEquals("AAC", it.codec)
            assertEquals(44_100, it.sampleRate); assertEquals(4_300_000L, it.contentLength)
        }
        byItag.getValue(251).let { assertEquals("Opus", it.codec); assertEquals(160, it.kbps); assertEquals(48_000, it.sampleRate) }
        byItag.getValue(774).let { assertNull(it.url); assertEquals("s=SIG&sp=sig&url=https%3A%2F%2Fa%2F774", it.cipher) }
        assertEquals("averageBitrate used when bitrate is absent", 49, byItag.getValue(139).kbps)
    }

    @Test fun formatRankingPerQuality() {
        val formats = YtFormats.parse(fixture("player_formats.json"))
        fun order(q: Quality, metered: Boolean) = YtFormats.rank(formats, q, metered).map { it.itag }
        assertEquals("HIGH caps at ~160 kbps; over-cap renditions go last", listOf(251, 140, 139, 774), order(Quality.HIGH, false))
        assertEquals(listOf(774, 251, 140, 139), order(Quality.MAX, false))
        assertEquals(listOf(774, 251, 140, 139), order(Quality.LOSSLESS, true))
        assertEquals("AUTO on metered = HIGH", order(Quality.HIGH, false), order(Quality.AUTO, true))
        assertEquals("AUTO on unmetered = MAX", order(Quality.MAX, false), order(Quality.AUTO, false))
    }

    @Test fun missingStreamingDataYieldsNoFormats() {
        assertTrue(YtFormats.parse(Json.parseToJsonElement("""{"playabilityStatus":{"status":"OK"}}""").jsonObject).isEmpty())
    }

    private fun <T : Any> assertNotNullAnd(v: T?): T { assertNotNull(v); return v!! }
}
