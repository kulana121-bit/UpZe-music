package com.ravi.musicspot.data

import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Track

/**
 * 1.0.7: pure duplicate clean-up for the library (unit-tested in LogicTest).
 *
 * A Spotify import could store the same song twice: a Spotify playlist that holds a song twice, or two Spotify
 * songs matched to the same playable recording. Every Lazy list in the app keys its rows by id, and Compose throws
 * ("Key ... was already used") on a repeated key, so opening Liked Songs or that playlist right after signing in to
 * Spotify crashed the app. All merges now keep the first copy of an id, and [clean] fixes libraries already on disk.
 */
object LibraryClean {
    fun tracks(list: List<Track>): List<Track> = distinctOrSame(list) { it.id }
    fun collections(list: List<Collection>): List<Collection> = distinctOrSame(list) { it.id }

    /** [existing] then the new [incoming] ids not already present, each id once. O(n) with a hash set (was O(n·m)). */
    fun mergeTracks(existing: List<Track>, incoming: List<Track>): List<Track> {
        val seen = HashSet<String>(existing.size + incoming.size)
        val out = ArrayList<Track>(existing.size + incoming.size)
        for (t in existing) if (seen.add(t.id)) out += t
        for (t in incoming) if (seen.add(t.id)) out += t
        return out
    }

    fun mergeCollections(existing: List<Collection>, incoming: List<Collection>): List<Collection> {
        val seen = HashSet<String>(existing.size + incoming.size)
        val out = ArrayList<Collection>(existing.size + incoming.size)
        for (c in existing) if (seen.add(c.id)) out += c
        for (c in incoming) if (seen.add(c.id)) out += c
        return out
    }

    fun isClean(d: LibraryData): Boolean =
        isDistinct(d.liked) { it.id } && isDistinct(d.saved) { it.id } && isDistinct(d.playlists) { it.id } &&
            d.playlists.all { p -> isDistinct(p.tracks) { it.id } }

    /** The same data with each id once per list (first copy kept); returns [d] itself when nothing repeats. */
    fun clean(d: LibraryData): LibraryData {
        if (isClean(d)) return d
        return d.copy(
            liked = tracks(d.liked),
            saved = collections(d.saved),
            playlists = distinctOrSame(d.playlists) { it.id }.map { p -> tracks(p.tracks).let { if (it === p.tracks) p else p.copy(tracks = it) } },
        )
    }

    private inline fun <T> isDistinct(list: List<T>, key: (T) -> String): Boolean {
        if (list.size < 2) return true
        val seen = HashSet<String>(list.size)
        for (x in list) if (!seen.add(key(x))) return false
        return true
    }

    private inline fun <T> distinctOrSame(list: List<T>, key: (T) -> String): List<T> {
        if (isDistinct(list, key)) return list
        val seen = HashSet<String>(list.size)
        return list.filter { seen.add(key(it)) }
    }
}
