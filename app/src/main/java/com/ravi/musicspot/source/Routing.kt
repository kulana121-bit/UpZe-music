package com.ravi.musicspot.source

import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import java.text.Normalizer
import kotlin.math.abs

/** Pure matching of the same recording across sources (Spotify import, cross-source upgrades, failover). */
object TrackMatcher {
    private val BRACKETS = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val FEAT = Regex("""\s+(?:feat\.?|ft\.?|featuring|with)\s.*$""", RegexOption.IGNORE_CASE)
    private val DASH_NOTE = Regex("""\s+[-–—]\s+.*(?:remaster|version|edit|mix|live|mono|stereo).*$""", RegexOption.IGNORE_CASE)
    private val VERSION = Regex("""\b(remix|live|instrumental|karaoke|acoustic|cover|sped up|slowed|reverb|8d|nightcore|lofi|lo-fi)\b""", RegexOption.IGNORE_CASE)

    /** YouTube title junk: "| Artist | Label", "Official Video", "Lyrical", "Full Song", "HD", "4K"… */
    private val PIPE_TAIL = Regex("""\s*[|｜•].*$""")
    private val JUNK = Regex("""\b(official\s+(music\s+)?(video|audio|song|lyric(al)?\s*video)|lyrical(\s+video)?|lyrics?(\s+video)?|full\s+(video\s+)?song|video\s+song|audio\s+song|new\s+\w+\s+song|hd|4k|1080p|720p|mv|m\/v|visualizer)\b""", RegexOption.IGNORE_CASE)
    private val CHANNEL_JUNK = Regex("""\s*(-\s*topic|vevo|official|music|records|films|entertainment|channel)\s*$""", RegexOption.IGNORE_CASE)
    // Strip accents from Latin letters only: Indic vowel signs (Devanagari, Odia…) are combining marks too and must stay.
    private val LATIN_MARKS = Regex("(?<=\\p{IsLatin})\\p{M}+")

    fun norm(s: String): String = LATIN_MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
        .lowercase().replace("&", " and ").replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun coreTitle(t: String) = norm(DASH_NOTE.replace(FEAT.replace(BRACKETS.replace(t, ""), ""), "")).ifEmpty { norm(t) }

    /** [coreTitle] that also drops "| …" tails and YouTube upload junk ("Official Video", "Lyrical"…). */
    fun cleanTitle(t: String): String {
        val noPipe = PIPE_TAIL.replace(t, "").ifBlank { t }
        val s = norm(JUNK.replace(DASH_NOTE.replace(FEAT.replace(BRACKETS.replace(noPipe, ""), ""), ""), " "))
        return s.ifEmpty { coreTitle(t) }
    }

    fun artists(a: String): Set<String> = a.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|\bx\b|\band\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE))
        .map { norm(CHANNEL_JUNK.replace(it.trim(), "")) }.filter { it.isNotBlank() }.toSet()

    /** Search queries for finding [t] on another source: clean title + primary artist, then the clean title alone. */
    fun searchQueries(t: Track): List<String> {
        val title = PIPE_TAIL.replace(BRACKETS.replace(t.title, ""), "").let { JUNK.replace(it, " ") }.replace(Regex("\\s+"), " ").trim().ifBlank { t.title }
        val artist = CHANNEL_JUNK.replace(t.artist.substringBefore(',').substringBefore('&').trim(), "").trim()
        return listOfNotNull("$title $artist".trim(), title.takeIf { artist.isNotBlank() }).distinct()
    }

    private fun tokens(s: String) = s.split(' ').filter { it.isNotBlank() }.toSet()
    private fun jaccard(a: Set<String>, b: Set<String>) = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    /**
     * 0..1: how likely [b] is the same recording as [a]. [lenient] (YouTube → other-source fallback) cleans YouTube
     * title junk, accepts the artist when it appears in the other title ("Koyal | Duryodhan Rana"), and turns a long
     * duration mismatch (video intros/outros) into a penalty instead of a veto.
     */
    fun score(a: Track, b: Track, lenient: Boolean = false): Double {
        if (a.isrc != null && b.isrc != null) return if (a.isrc.equals(b.isrc, true)) 1.0 else 0.0
        val ta = if (lenient) cleanTitle(a.title) else coreTitle(a.title)
        val tb = if (lenient) cleanTitle(b.title) else coreTitle(b.title)
        val title = when {
            ta == tb -> 1.0
            ta.isNotEmpty() && tb.isNotEmpty() && (ta.startsWith(tb) || tb.startsWith(ta)) -> 0.85
            lenient && ta.isNotEmpty() && tb.isNotEmpty() && (tokens(tb).all { it in tokens(ta) } || tokens(ta).all { it in tokens(tb) }) -> 0.8
            else -> jaccard(tokens(ta), tokens(tb))
        }
        // A remix/live/cover only matches a remix/live/cover.
        if (VERSION.containsMatchIn(a.title) != VERSION.containsMatchIn(b.title)) return title * 0.4
        val aa = artists(a.artist); val ab = artists(b.artist)
        val fullA = norm(a.title + " " + a.artist); val fullB = norm(b.title + " " + b.artist)
        val artist = when {
            aa.isEmpty() || ab.isEmpty() -> 0.5
            aa.intersect(ab).isNotEmpty() -> 1.0
            aa.any { x -> ab.any { y -> x.contains(y) || y.contains(x) } } -> 0.85
            lenient && (ab.any { it.length >= 3 && fullA.contains(it) } || aa.any { it.length >= 3 && fullB.contains(it) }) -> 0.85
            else -> jaccard(aa.flatMap { tokens(it) }.toSet(), ab.flatMap { tokens(it) }.toSet()).let { if (lenient) maxOf(it, 0.2) else it }
        }
        val dur = if (a.durationMs <= 0 || b.durationMs <= 0) 0.75 else {
            val d = abs(a.durationMs - b.durationMs)
            when { d <= 3_000 -> 1.0; d <= 7_000 -> 0.8; d <= 15_000 -> 0.4; lenient && d <= 90_000 -> 0.2; else -> 0.0 }
        }
        if (dur == 0.0) return 0.0
        return title * 0.5 + artist * 0.3 + dur * 0.2
    }

    const val THRESHOLD = 0.72
    /** Fallback threshold: an exact clean title with a matching artist passes even with a long video intro. */
    const val LENIENT_THRESHOLD = 0.66

    fun best(target: Track, candidates: List<Track>, threshold: Double = THRESHOLD, lenient: Boolean = false): Track? =
        candidates.map { it to score(target, it, lenient) }.filter { it.second >= threshold }.maxByOrNull { it.second }?.first
}

/** Expected quality of a source/track before playback, used to order cross-source candidates. Higher is better. */
object QualityEstimate {
    private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv")
    fun isLosslessFormat(f: String?) = f?.lowercase()?.let { it in LOSSLESS } == true

    /** Rough rank in "kbps-equivalent": lossless 1000+, else the known/expected bitrate. */
    fun rank(t: Track, quality: Quality, premium: Boolean): Int {
        if (isLosslessFormat(t.format)) return if (quality == Quality.LOSSLESS || quality == Quality.MAX || t.source == SourceKind.LOCAL) 1411 else 300
        t.bitrateKbps?.let { return it }
        return when (t.source) {
            SourceKind.JIOSAAVN -> 320
            SourceKind.YOUTUBE -> if (premium) 256 else 160
            SourceKind.AUDIUS -> 320
            SourceKind.JAMENDO -> 200
            SourceKind.ARCHIVE -> 192
            SourceKind.SOUNDCLOUD -> 128
            SourceKind.LOCAL, SourceKind.SUBSONIC, SourceKind.JELLYFIN -> 256
            SourceKind.SPOTIFY -> 0
        }
    }
}

/**
 * Pure routing: given the track the user picked and same-recording matches from other sources, the ordered list
 * to try. Disabled and metadata-only sources are dropped. In Auto/High the user's source priority decides;
 * in Max/Lossless the highest expected quality wins and priority breaks ties. On failure the player walks on
 * to the next entry (failover).
 */
object SourceRouter {
    fun plan(
        original: Track,
        matches: List<Track>,
        priority: List<SourceKind>,
        enabled: Set<SourceKind>,
        quality: Quality,
        premium: Boolean = false,
    ): List<Track> {
        val all = (listOf(original) + matches).distinctBy { it.id }
            .filter { it.source.playable && (it.source in enabled || it.id == original.id && it.source == SourceKind.LOCAL) }
        fun prio(t: Track) = priority.indexOf(t.source).let { if (it < 0) Int.MAX_VALUE else it }
        return when (quality) {
            Quality.AUTO, Quality.HIGH -> all.sortedWith(compareBy(::prio))
            Quality.MAX, Quality.LOSSLESS -> all.sortedWith(compareByDescending<Track> { QualityEstimate.rank(it, quality, premium) }.thenBy(::prio))
        }
    }

    /** Which other sources are worth searching for a match of [original] (cost control: only ones that could win). */
    fun sourcesToSearch(original: Track, priority: List<SourceKind>, enabled: Set<SourceKind>, quality: Quality, premium: Boolean): List<SourceKind> {
        val searchable = setOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN, SourceKind.AUDIUS, SourceKind.JAMENDO, SourceKind.SUBSONIC, SourceKind.JELLYFIN, SourceKind.LOCAL)
        val candidates = priority.filter { it in enabled && it in searchable && it != original.source }
        if (!original.source.playable) return candidates // Spotify metadata: must find something
        return when (quality) {
            Quality.AUTO, Quality.HIGH -> candidates.takeWhile { it != original.source }.filter { priority.indexOf(it) < priority.indexOf(original.source) }
            Quality.MAX, Quality.LOSSLESS -> {
                val mine = QualityEstimate.rank(original, quality, premium)
                candidates.filter { QualityEstimate.rank(Track("${it.prefix}:x", "", "", format = if (it in setOf(SourceKind.SUBSONIC, SourceKind.JELLYFIN, SourceKind.LOCAL)) "flac" else null), quality, premium) > mine }
            }
        }
    }
}
