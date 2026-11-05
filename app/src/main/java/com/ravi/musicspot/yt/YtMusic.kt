/*
 * YouTube Music catalogue for UpZe, built on the parser ported from OpenTune 1.0.4 (InnertubeParser.kt) and the
 * browse flow of its MusicRepository.kt (GPL-3.0-or-later). GPL-3.0-or-later.
 */
package com.ravi.musicspot.yt

import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.Shelf
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.MusicSource
import com.ravi.musicspot.source.Resolved
import com.ravi.musicspot.source.parseClock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun Song.toTrack(): Track = Track(
    id = "yt:$videoId", title = title, artist = artist, album = albumName,
    durationMs = parseClock(durationText), artUrl = upscale(thumbnailUrl), artistId = artistId?.let { "yt:$it" },
    albumId = albumId?.let { "yt:$it" }, explicit = isExplicit == true,
)

/** YouTube thumbnails carry their size in the URL; ask for a sharper copy for the player. */
fun upscale(url: String?): String? = url?.let {
    when {
        "googleusercontent.com" in it || "ggpht.com" in it -> it.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj")
        else -> it
    }
}

private fun BrowseItem.toCollection(): Collection = Collection(
    id = "yt:$browseId",
    kind = when (type) { BrowseType.ALBUM -> Collection.Kind.ALBUM; BrowseType.ARTIST -> Collection.Kind.ARTIST; else -> Collection.Kind.PLAYLIST },
    title = title, subtitle = subtitle, artUrl = upscale(thumbnailUrl),
)

private fun ShelfItem.toCollection(): Collection? {
    val id = browseId ?: return null
    val kind = when {
        id.startsWith("UC") || id.startsWith("MPLAUC") -> Collection.Kind.ARTIST
        id.startsWith("MPREb") -> Collection.Kind.ALBUM
        else -> Collection.Kind.PLAYLIST
    }
    return Collection("yt:$id", kind, title, subtitle, upscale(thumbnailUrl))
}

private fun ShelfItem.toTrackOrNull(): Track? = videoId?.let {
    Track("yt:$it", title, InnertubeParser.artistFromSubtitle(subtitle), null, 0, upscale(thumbnailUrl), explicit = explicit)
}

private fun HomeShelf.toShelf(): Shelf {
    val tracks = items.mapNotNull { it.toTrackOrNull() }
    val cols = items.filter { it.videoId == null }.mapNotNull { it.toCollection() }
    return Shelf(title, tracks, cols, subtitle.ifBlank { null }, moreBrowseId?.let { "yt:$it" })
}

data class CollectionPage(val collection: Collection, val tracks: List<Track>, val description: String? = null, val shelves: List<Shelf> = emptyList())
data class ArtistInfo(val collection: Collection, val topTracks: List<Track>, val shelves: List<Shelf>, val description: String?, val subscribers: String?)
data class SearchAll(val tracks: List<Track>, val collections: List<Collection>, val top: Track? = null)
data class Mood(val title: String, val browseId: String, val params: String?)
data class MoodSection(val title: String, val moods: List<Mood>)
data class Lyric(val text: String, val source: String?)

class YtMusic(val tube: Innertube, private val resolver: YtStreamResolver, private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.YOUTUBE
    override fun available() = enabled()

    // ---- browse ----------------------------------------------------------------------------------------------
    suspend fun home(): Pair<List<Shelf>, String?> {
        val r = tube.browse("FEmusic_home")
        return InnertubeParser.parseHome(r).map { it.toShelf() }.filter { it.tracks.isNotEmpty() || it.collections.isNotEmpty() } to
            InnertubeParser.sectionListContinuation(r)
    }

    suspend fun homeMore(token: String): Pair<List<Shelf>, String?> {
        val r = tube.browseContinuation(token)
        return InnertubeParser.parseHomeContinuation(r).map { it.toShelf() }.filter { it.tracks.isNotEmpty() || it.collections.isNotEmpty() } to
            InnertubeParser.sectionListContinuation(r)
    }

    suspend fun shelvesOf(browseId: String, params: String? = null): List<Shelf> {
        val r = tube.browse(browseId, params)
        return InnertubeParser.parseHome(r).ifEmpty { InnertubeParser.parseHomeContinuation(r) }.map { it.toShelf() }
            .filter { it.tracks.isNotEmpty() || it.collections.isNotEmpty() }
    }

    suspend fun charts(country: String? = tube.region): List<Shelf> =
        InnertubeParser.parseHome(tube.charts(country)).map { it.toShelf() }.filter { it.tracks.isNotEmpty() || it.collections.isNotEmpty() }

    suspend fun newReleases(): List<Shelf> = shelvesOf("FEmusic_new_releases")

    suspend fun moods(): List<MoodSection> = InnertubeParser.parseMoodAndGenres(tube.browse("FEmusic_moods_and_genres"))
        .map { s -> MoodSection(s.title, s.items.map { Mood(it.title, it.browseId, it.params) }) }

    suspend fun search(query: String, filter: SearchFilter): SearchAll {
        val page = InnertubeParser.parseSearchPage(tube.search(query, filter.params), includeVideos = filter == SearchFilter.VIDEOS)
        var top: Track? = null
        val tracks = mutableListOf<Track>()
        val cols = mutableListOf<Collection>()
        page.rows.forEach { r ->
            when (r) {
                is SearchResult.TopTrack -> { val t = r.song.toTrack(); if (top == null) top = t; tracks += t }
                is SearchResult.Track -> tracks += r.song.toTrack()
                is SearchResult.Browse -> cols += r.item.toCollection()
            }
        }
        return SearchAll(tracks.distinctBy { it.id }, cols.distinctBy { it.id }, top)
    }

    suspend fun suggestions(input: String): List<String> = InnertubeParser.parseSearchSuggestions(tube.suggestions(input))

    override suspend fun search(query: String, limit: Int): List<Track> = search(query, SearchFilter.SONGS).tracks.take(limit)

    suspend fun collection(browseId: String, maxPages: Int = 10): CollectionPage {
        val r = tube.browse(browseId)
        val header = InnertubeParser.parseBrowseHeader(r)
        val page = InnertubeParser.parsePlaylistShelf(r)
        val songs = (page?.songs?.takeIf { it.isNotEmpty() } ?: InnertubeParser.collectSongsDeep(r)).distinctBy { it.videoId }.toMutableList()
        var token = page?.continuation
        var pages = 0
        while (token != null && pages++ < maxPages) {
            val next = withTimeoutOrNull(12_000) { InnertubeParser.parsePlaylistContinuation(tube.browseContinuation(token!!)) } ?: break
            val known = songs.mapTo(HashSet()) { it.videoId }
            songs += next.songs.filter { known.add(it.videoId) }
            token = next.continuation
        }
        val art = upscale(header?.thumbnailUrl)
        val kind = if (browseId.startsWith("MPREb")) Collection.Kind.ALBUM else Collection.Kind.PLAYLIST
        val tracks = songs.map { s -> s.toTrack().let { t -> if (t.artUrl == null) t.copy(artUrl = art) else t }.let { t -> if (kind == Collection.Kind.ALBUM && t.album == null) t.copy(album = header?.title, albumId = "yt:$browseId") else t } }
        return CollectionPage(Collection("yt:$browseId", kind, header?.title.orEmpty(), header?.subtitle, art), tracks, InnertubeParser.parseDescription(r))
    }

    suspend fun artist(browseId: String): ArtistInfo {
        val p = InnertubeParser.parseArtistPage(tube.browse(browseId))
        return ArtistInfo(
            Collection("yt:$browseId", Collection.Kind.ARTIST, p.name.orEmpty(), p.monthlyListenerCount ?: p.subscriberCountText, upscale(p.thumbnailUrl)),
            p.songs.map { it.toTrack() }, p.sections.map { it.toShelf() }.filter { it.tracks.isNotEmpty() || it.collections.isNotEmpty() },
            p.description, p.subscriberCountText,
        )
    }

    /** Radio / autoplay: YouTube Music's watch-next queue seeded by [videoId]. */
    suspend fun radio(videoId: String): List<Track> =
        InnertubeParser.parseWatchQueue(tube.next(videoId)).map { it.toTrack() }.filter { it.nativeId != videoId }

    /** Related shelves ("You might also like", "Similar artists") for the playing song. */
    suspend fun related(videoId: String): List<Shelf> {
        val id = tabBrowseId(tube.next(videoId), "MUSIC_PAGE_TYPE_TRACK_RELATED") ?: return emptyList()
        val r = tube.browse(id)
        val shelves = mutableListOf<HomeShelf>()
        walk(r) { o -> (o["musicCarouselShelfRenderer"] as? JsonObject)?.let { _ -> } }
        return InnertubeParser.parseHomeContinuation(r).ifEmpty { InnertubeParser.parseHome(r) }.map { it.toShelf() }
            .filter { it.tracks.isNotEmpty() || it.collections.isNotEmpty() }.also { shelves.clear() }
    }

    /** YouTube Music's own (plain, unsynced) lyrics, with the provider credit it shows. */
    suspend fun lyrics(videoId: String): Lyric? {
        val id = tabBrowseId(tube.next(videoId, null), "MUSIC_PAGE_TYPE_TRACK_LYRICS") ?: return null
        val r = tube.browse(id)
        var found: Lyric? = null
        walk(r) { o ->
            val shelf = o["musicDescriptionShelfRenderer"] as? JsonObject ?: return@walk
            val text = runs(shelf["description"])
            if (text.isNotBlank() && found == null) found = Lyric(text, runs(shelf["footer"]).ifBlank { null })
        }
        return found
    }

    suspend fun accountInfo(): Account? = InnertubeParser.parseAccount(tube.accountMenu())

    // ---- the signed-in user's library ---------------------------------------------------------------------------
    suspend fun likedSongs(): List<Track> = collection("VLLM", maxPages = 30).tracks
    suspend fun history(): List<Track> = InnertubeParser.collectSongsDeep(tube.browse("FEmusic_history")).distinctBy { it.videoId }.map { it.toTrack() }
    suspend fun libraryPlaylists(): List<Collection> = InnertubeParser.parseLibraryItemPage(tube.browse("FEmusic_liked_playlists")).items.mapNotNull { it.toCollection() }
        .filter { it.id != "yt:VLLM" }
    suspend fun libraryAlbums(): List<Collection> = InnertubeParser.parseLibraryItemPage(tube.browse("FEmusic_liked_albums")).items.mapNotNull { it.toCollection() }
    suspend fun subscriptions(): List<Collection> = InnertubeParser.parseLibraryItemPage(tube.browse("FEmusic_library_corpus_artists")).items
        .mapNotNull { it.toCollection()?.copy(kind = Collection.Kind.ARTIST) }

    // ---- MusicSource ---------------------------------------------------------------------------------------------
    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val r = resolver.resolve(track.nativeId, quality, metered)
        return Resolved(r.url, StreamInfo(kind, r.audio.codec, r.audio.kbps, r.audio.sampleRate, track.id), r.headers, "yt:${track.nativeId}@${r.audio.itag}")
    }

    /** Playback was refused (403/404/410) on a URL this source handed out: stand its client down for that track. */
    fun onPlaybackRefused(url: String) = resolver.onPlaybackRefused(url)

    override suspend fun probe(): String = "${search("lofi", SearchFilter.SONGS).tracks.size} results"

    // ---- json helpers ---------------------------------------------------------------------------------------------
    private fun tabBrowseId(next: JsonObject, pageType: String): String? {
        var id: String? = null
        walk(next) { o ->
            val be = o["browseEndpoint"] as? JsonObject ?: return@walk
            val type = ((((be["browseEndpointContextSupportedConfigs"] as? JsonObject)?.get("browseEndpointContextMusicConfig")) as? JsonObject)
                ?.get("pageType") as? JsonPrimitive)?.contentOrNull
            if (type == pageType && id == null) id = (be["browseId"] as? JsonPrimitive)?.contentOrNull
        }
        return id
    }

    private fun walk(e: JsonElement, f: (JsonObject) -> Unit) {
        when (e) {
            is JsonObject -> { f(e); e.values.forEach { walk(it, f) } }
            is JsonArray -> e.forEach { walk(it, f) }
            else -> {}
        }
    }

    private fun runs(e: JsonElement?): String = ((e as? JsonObject)?.get("runs") as? JsonArray).orEmpty()
        .joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
}
