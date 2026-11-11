package com.ravi.musicspot.playback

import com.ravi.musicspot.model.Track
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pack 6 Part 1: the Android Auto / MediaLibrarySession browse tree, as pure functions over plain data (no Android,
 * no session classes), so ids and tree shape are unit-tested.
 *
 * Tree (the user's own library only, never live source browsing):
 *   root → Liked songs · Playlists (→ one node per playlist → its tracks) · History (newest first) · On this device
 * Empty sections and empty playlists are not shown, so every browsable node has something playable under it.
 *
 * Media ids:
 *   upze:root, upze:liked, upze:playlists, upze:history, upze:device   sections
 *   upze:pl/<url-encoded playlist id>                                    one playlist
 *   upze:play|<parent node id>|<url-encoded track id>                    a song inside its parent
 * A playable id always names its parent, so playing it builds the queue from that parent's tracks and starts at
 * the tapped song ([resolve]). App queue items use the bare track id as media id and never start with "upze:".
 */
object AutoBrowse {
    const val ROOT = "upze:root"
    const val LIKED = "upze:liked"
    const val PLAYLISTS = "upze:playlists"
    const val HISTORY = "upze:history"
    const val DEVICE = "upze:device"
    private const val PL = "upze:pl/"
    private const val PLAY = "upze:play|"
    /** Search results cap (head units page through them). */
    const val MAX_SEARCH = 50

    data class Playlist(val id: String, val name: String, val tracks: List<Track>, val artUrl: String? = null)

    /** A snapshot of the library the tree is built from. */
    data class Lib(
        val liked: List<Track> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        /** Newest first (History's own order). */
        val history: List<Track> = emptyList(),
        /** Downloaded + fully cached songs. */
        val device: List<Track> = emptyList(),
    )

    enum class Kind { ROOT, SECTION, PLAYLIST, SONG }

    data class Node(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val artUrl: String? = null,
        val kind: Kind,
        /** The song (only for [Kind.SONG]). */
        val track: Track? = null,
    ) {
        val browsable: Boolean get() = kind != Kind.SONG
        val playable: Boolean get() = kind == Kind.SONG
    }

    /** The queue a playable id starts: its parent's tracks, beginning at the tapped one. */
    data class Play(val tracks: List<Track>, val start: Int)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String? = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()

    fun playlistId(playlistId: String) = PL + enc(playlistId)
    fun songId(parentId: String, trackId: String) = PLAY + parentId + "|" + enc(trackId)
    fun isOurs(mediaId: String?) = mediaId?.startsWith("upze:") == true
    fun isPlayable(mediaId: String?) = mediaId?.startsWith(PLAY) == true

    /** (parent id, track id) of a playable id, or null when it isn't one or is malformed. */
    fun parseSong(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(PLAY)) return null
        val rest = mediaId.removePrefix(PLAY)
        val cut = rest.lastIndexOf('|')
        if (cut <= 0 || cut == rest.lastIndex) return null
        val tid = dec(rest.substring(cut + 1)) ?: return null
        return rest.substring(0, cut) to tid
    }

    private fun sectionTitle(id: String) = when (id) {
        LIKED -> "Liked songs"; PLAYLISTS -> "Playlists"; HISTORY -> "History"; DEVICE -> "On this device"; else -> null
    }

    private fun playlistOf(lib: Lib, nodeId: String): Playlist? {
        if (!nodeId.startsWith(PL)) return null
        val pid = dec(nodeId.removePrefix(PL)) ?: return null
        return lib.playlists.firstOrNull { it.id == pid }
    }

    /** The tracks a browsable node plays, or null for an unknown node (playlists node and root have none). */
    fun tracksOf(lib: Lib, nodeId: String): List<Track>? = when (nodeId) {
        LIKED -> lib.liked
        HISTORY -> lib.history
        DEVICE -> lib.device
        else -> playlistOf(lib, nodeId)?.tracks
    }?.distinctBy { it.id }

    private fun song(parentId: String, t: Track) = Node(songId(parentId, t.id), t.title, t.artist.ifBlank { null }, t.artUrl, Kind.SONG, t)

    private fun nonEmptyPlaylists(lib: Lib) = lib.playlists.filter { p -> p.tracks.isNotEmpty() }

    /** Children of [nodeId] in display order, or null when the id is unknown (a clean failure, never a crash). */
    fun children(lib: Lib, nodeId: String): List<Node>? = when (nodeId) {
        ROOT -> listOfNotNull(
            Node(LIKED, "Liked songs", "${lib.liked.size} songs", kind = Kind.SECTION).takeIf { lib.liked.isNotEmpty() },
            Node(PLAYLISTS, "Playlists", null, kind = Kind.SECTION).takeIf { nonEmptyPlaylists(lib).isNotEmpty() },
            Node(HISTORY, "History", "Most recent first", kind = Kind.SECTION).takeIf { lib.history.isNotEmpty() },
            Node(DEVICE, "On this device", "Downloaded and cached", kind = Kind.SECTION).takeIf { lib.device.isNotEmpty() },
        )
        PLAYLISTS -> nonEmptyPlaylists(lib).map { p -> Node(playlistId(p.id), p.name, "${p.tracks.size} songs", p.artUrl ?: p.tracks.firstOrNull()?.artUrl, Kind.PLAYLIST) }
        else -> tracksOf(lib, nodeId)?.map { song(nodeId, it) }
    }

    /** One node by id (root, a section, a playlist or a song), or null when it doesn't resolve. */
    fun node(lib: Lib, mediaId: String): Node? {
        if (mediaId == ROOT) return Node(ROOT, "UpZe", kind = Kind.ROOT)
        sectionTitle(mediaId)?.let { return Node(mediaId, it, kind = Kind.SECTION) }
        playlistOf(lib, mediaId)?.let { p -> return Node(mediaId, p.name, "${p.tracks.size} songs", p.artUrl ?: p.tracks.firstOrNull()?.artUrl, Kind.PLAYLIST) }
        val (parent, tid) = parseSong(mediaId) ?: return null
        val t = tracksOf(lib, parent)?.firstOrNull { it.id == tid } ?: return null
        return song(parent, t)
    }

    /** The queue a playable id plays (parent's tracks, start at the song), or null when it no longer resolves. */
    fun resolve(lib: Lib, mediaId: String): Play? {
        val (parent, tid) = parseSong(mediaId) ?: return null
        val tracks = tracksOf(lib, parent) ?: return null
        val i = tracks.indexOfFirst { it.id == tid }
        return if (i < 0) null else Play(tracks, i)
    }

    /**
     * Songs matching [query] (title, artist or album, case-insensitive), each once, searched in Liked → playlists →
     * History → On this device; each result plays inside the section it was found in. Blank query → nothing.
     */
    fun search(lib: Lib, query: String): List<Node> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        fun hit(t: Track) = t.title.lowercase().contains(q) || t.artist.lowercase().contains(q) || (t.album?.lowercase()?.contains(q) == true)
        val seen = HashSet<String>()
        val out = ArrayList<Node>()
        val groups = listOf(LIKED to lib.liked) + lib.playlists.map { playlistId(it.id) to it.tracks } + listOf(HISTORY to lib.history, DEVICE to lib.device)
        for ((parent, tracks) in groups) for (t in tracks) {
            if (out.size >= MAX_SEARCH) return out
            if (hit(t) && seen.add(t.id)) out += song(parent, t)
        }
        return out
    }

    /** One page of [items] (Media3 page/pageSize; pageSize ≤ 0 = everything). */
    fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return items
        val from = page.toLong() * pageSize
        if (page < 0 || from >= items.size) return emptyList()
        return items.subList(from.toInt(), minOf(items.size, (from + pageSize).toInt()))
    }
}
