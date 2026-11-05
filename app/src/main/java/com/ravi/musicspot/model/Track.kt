package com.ravi.musicspot.model

import kotlinx.serialization.Serializable

/** Where a track (or a match for it) can be played from. Order here is only the default priority. */
enum class SourceKind(val label: String, val prefix: String) {
    YOUTUBE("YouTube Music", "yt"),
    JIOSAAVN("JioSaavn", "js"),
    AUDIUS("Audius", "au"),
    SOUNDCLOUD("SoundCloud", "sc"),
    JAMENDO("Jamendo", "jm"),
    ARCHIVE("Internet Archive", "ia"),
    LOCAL("On this device", "local"),
    SUBSONIC("Subsonic / Navidrome", "sub"),
    JELLYFIN("Jellyfin", "jf"),
    SPOTIFY("Spotify (metadata only)", "sp");

    val playable: Boolean get() = this != SPOTIFY

    companion object {
        fun ofId(id: String): SourceKind = entries.firstOrNull { id.startsWith(it.prefix + ":") } ?: YOUTUBE
    }
}

/**
 * One song, from any source. [id] is "<prefix>:<native id>", e.g. "yt:dQw4w9WgXcQ", "js:AbC123",
 * "local:42", "sub:<serverId>:<id>", "jf:<serverId>:<id>", "sp:<spotify id>" (metadata only; played via a match).
 */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val artUrl: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val explicit: Boolean = false,
    val year: String? = null,
    /** ISRC when the source gives one (Spotify, some servers): the strongest cross-source match key. */
    val isrc: String? = null,
    /** Container/codec hint from the source listing ("flac", "mp3", "m4a", "alac"), when known before playback. */
    val format: String? = null,
    val bitrateKbps: Int? = null,
) {
    val source: SourceKind get() = SourceKind.ofId(id)
    val nativeId: String get() = id.substringAfter(':')
}

/** A browsable collection (album, playlist, artist, mood) on some source. */
@Serializable
data class Collection(
    val id: String,
    val kind: Kind,
    val title: String,
    val subtitle: String? = null,
    val artUrl: String? = null,
) {
    enum class Kind { ALBUM, PLAYLIST, ARTIST, MOOD, LOCAL_PLAYLIST }
}

/** A row of items on Home/Explore/Artist pages. */
data class Shelf(
    val title: String,
    val tracks: List<Track> = emptyList(),
    val collections: List<Collection> = emptyList(),
    val subtitle: String? = null,
    val moreId: String? = null,
)

/** What the player actually got for a track: real codec/bitrate as resolved (and later refined by ExoPlayer's Format). */
@Serializable
data class StreamInfo(
    val source: SourceKind,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val sourceTrackId: String? = null,
) {
    val label: String get() = buildString {
        append(source.label)
        codec?.let { append(" · ").append(it) }
        bitrateKbps?.let { append(" · ").append(it).append(" kbps") }
        sampleRateHz?.let { append(" · ").append("%.1f".format(it / 1000.0)).append(" kHz") }
    }
}

/** User-facing quality setting. YouTube/JioSaavn top out at lossy 256–320 kbps; Lossless only comes from FLAC/ALAC/WAV sources. */
enum class Quality(val label: String, val detail: String) {
    AUTO("Auto", "Best on Wi-Fi, ~160 kbps on mobile data"),
    HIGH("High", "~160 kbps Opus / 320 kbps AAC"),
    MAX("Max", "Highest bitrate any source offers"),
    LOSSLESS("Lossless when available", "FLAC/ALAC/WAV from your files, servers and free FLAC catalogues; otherwise Max"),
}
