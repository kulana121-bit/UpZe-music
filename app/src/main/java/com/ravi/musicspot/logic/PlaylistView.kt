package com.ravi.musicspot.logic

import com.ravi.musicspot.model.Track
import java.text.Normalizer

/** 1.0.2: playlist page sort orders. CUSTOM is the playlist's own order (as added / reordered). */
enum class PlaylistSort(val label: String) {
    CUSTOM("Custom order"), RECENT("Recently added"), TITLE("Title (A–Z)"), ARTIST("Artist (A–Z)"), ALBUM("Album"), DURATION("Duration");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: CUSTOM }
}

/** 1.0.2: the playlist page's songs after search and sort (pure, unit-tested). */
object PlaylistView {
    fun apply(tracks: List<Track>, query: String, sort: PlaylistSort): List<Track> {
        val words = norm(query).split(' ').filter { it.isNotBlank() }
        val found = if (words.isEmpty()) tracks else tracks.filter { t ->
            val hay = norm("${t.title} ${t.artist} ${t.album.orEmpty()}")
            words.all { hay.contains(it) }
        }
        return when (sort) {
            PlaylistSort.CUSTOM -> found
            PlaylistSort.RECENT -> found.asReversed()
            PlaylistSort.TITLE -> found.sortedBy { norm(it.title) }
            PlaylistSort.ARTIST -> found.sortedWith(compareBy({ norm(it.artist) }, { norm(it.title) }))
            PlaylistSort.ALBUM -> found.sortedWith(compareBy({ norm(it.album.orEmpty()).ifBlank { "\uFFFF" } }, { norm(it.title) }))
            PlaylistSort.DURATION -> found.sortedBy { it.durationMs }
        }
    }

    /** Plain text for the share sheet: the name, then one "Title – Artist" line per song (first 200). */
    fun shareText(name: String, tracks: List<Track>, link: String? = null): String = buildString {
        append(name).append(" · ").append(tracks.size).append(" songs\n")
        link?.let { append(it).append('\n') }
        append('\n')
        tracks.take(200).forEachIndexed { i, t -> append(i + 1).append(". ").append(t.title).append(" – ").append(t.artist).append('\n') }
        if (tracks.size > 200) append("…and ").append(tracks.size - 200).append(" more\n")
        append("\nShared from UpZe")
    }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trim()
}
