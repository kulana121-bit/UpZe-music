@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ravi.musicspot.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ravi.musicspot.Graph
import com.ravi.musicspot.MainActivity
import com.ravi.musicspot.core.CrashGuard
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.data.QueueSnapshot
import com.ravi.musicspot.logic.SleepTimer
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.Resolved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What is really playing: the resolved source plus the decoder's actual format (codec, bitrate, sample rate). */
data class NowStream(
    val trackId: String,
    val info: StreamInfo,
    val lossless: Boolean = false,
    val hiRes: Boolean = false,
    val failovers: List<String> = emptyList(),
    /** Set when YouTube was exhausted/excluded and this plays from another source (UI: "Playing from JioSaavn"). */
    val fallbackFrom: com.ravi.musicspot.model.SourceKind? = null,
)

/** What resolution learned about a track: the stream, the candidates that failed, and any YouTube fallback. */
private data class Known(val info: StreamInfo, val failures: List<String>, val fallbackFrom: com.ravi.musicspot.model.SourceKind?)

/** Shared playback state between the service and the UI (same process). */
object Playback {
    val stream = MutableStateFlow<NowStream?>(null)
    val error = MutableStateFlow<String?>(null)
    val sleep = SleepTimer()
    val sleepTick = MutableStateFlow(0L)
    val engine = EngineAudioProcessor { Graph.settings.value.engine(Graph.lowEnd) }
    val bitPerfectStatus = MutableStateFlow<String?>(null)
}

object TrackUri {
    const val SCHEME = "musicspot"
    fun of(t: Track): Uri = Uri.Builder().scheme(SCHEME).authority("track").appendQueryParameter("id", t.id)
        .appendQueryParameter("j", Base64.encodeToString(Http.json.encodeToString(Track.serializer(), t).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)).build()
    fun track(uri: Uri): Track? = uri.getQueryParameter("j")?.let {
        runCatching { Http.json.decodeFromString(Track.serializer(), String(Base64.decode(it, Base64.URL_SAFE))) }.getOrNull()
    }
}

fun Track.toMediaItem(autoplay: Boolean = false): MediaItem {
    val uri = TrackUri.of(this)
    val meta = MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setArtworkUri(artUrl?.let(Uri::parse)).setIsPlayable(true).setIsBrowsable(false)
        .setExtras(Bundle().apply { putString("track", Http.json.encodeToString(Track.serializer(), this@toMediaItem)); if (autoplay) putBoolean(EXTRA_AUTOPLAY, true) }).build()
    return MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(meta)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build()).build()
}

const val EXTRA_AUTOPLAY = "autoplay"
/** True for items Infinite play appended. */
val MediaItem.isAutoplay: Boolean get() = mediaMetadata.extras?.getBoolean(EXTRA_AUTOPLAY, false) == true

fun MediaItem.track(): Track? = mediaMetadata.extras?.getString("track")?.let { runCatching { Http.json.decodeFromString(Track.serializer(), it) }.getOrNull() }
    ?: (localConfiguration?.uri ?: requestMetadata.mediaUri)?.let(TrackUri::track)

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** In-flight resolutions, bounded and cancelled when the item stops mattering (fixes OpenTune B3/B4). */
    private val inflight = Collections.synchronizedMap(object : LinkedHashMap<String, Deferred<Resolved>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<Resolved>>): Boolean =
            (size > 6).also { if (it) eldest.value.cancel() }
    })
    private val resolvedInfo = Collections.synchronizedMap(object : LinkedHashMap<String, Known>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Known>?) = size > 64
    })
    /** v2.0.5: at most [com.ravi.musicspot.logic.ReplayPolicy.MAX_AUTO_REPLAYS] automatic replays per track. */
    private val replays = com.ravi.musicspot.logic.ReplayPolicy()
    /** Time-to-first-audio bookkeeping: media id → when it became current (or was replayed). */
    private var ttfaId: String? = null
    private var ttfaStart = 0L
    /** Play counting: the current item was counted already; the next count is a repeat. */
    private var countedFor: String? = null
    private var lastCountedId: String? = null
    private var repeatPending = false
    private var pure = false

    override fun onCreate() {
        super.onCreate()
        pure = Graph.settings.value.pureHiRes || Graph.settings.value.bitPerfectUsb
        player = buildPlayer()
        current = this
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, SafePositionPlayer(player)).setSessionActivity(open).setCallback(object : MediaSession.Callback {
            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(mediaItems.map { item -> item.track()?.toMediaItem() ?: item }.toMutableList())
        }).build()
        player.addListener(listener)
        // v2.0.7 widget: buttons drive this player directly; the card follows the song and play state.
        com.ravi.musicspot.widget.NowPlayingWidget.Controls.handle = { a ->
            when (a) {
                com.ravi.musicspot.widget.NowPlayingWidget.ACTION_TOGGLE -> { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() } }
                com.ravi.musicspot.widget.NowPlayingWidget.ACTION_NEXT -> player.seekToNextMediaItem()
                com.ravi.musicspot.widget.NowPlayingWidget.ACTION_PREV -> player.seekToPrevious()
            }
            player.mediaItemCount > 0
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) pushWidget()
            }
        })
        player.addAnalyticsListener(analytics)
        scope.launch { Graph.awaitReady(); try { restoreQueue() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; CrashGuard.nonFatal(this@PlaybackService, "restoreQueue", e) } }
        scope.launch { Graph.settings.state.map { it.engine(Graph.lowEnd) }.distinctUntilChanged().collect { Playback.engine.apply(it) } }
        // Infinite play switched on near the end of the queue: append right away.
        scope.launch { Graph.settings.state.map { it.autoplay }.distinctUntilChanged().collect { on -> if (on) { autoplayFor = null; maybeAutoplay() } } }
        scope.launch { try { ticker() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; CrashGuard.nonFatal(this@PlaybackService, "ticker", e) } }
        if (Graph.settings.value.bitPerfectUsb) runCatching { applyBitPerfect(this) }.onFailure { CrashGuard.nonFatal(this, "bitPerfect", it) }
    }

    private fun buildPlayer(): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    // Pure hi-res: decoders output float and nothing touches the samples (the engine is bypassed).
                    .setEnableFloatOutput(pure)
                    .setAudioProcessors(if (pure) emptyArray() else arrayOf(Playback.engine))
                    .build()
        }.setEnableDecoderFallback(true)
        // v2.0.2: no setUserAgent(): OkHttpDataSource *adds* that UA after the per-request headers, so googlevideo saw two
        // User-Agent headers (the minting client's and a browser's) and could refuse/throttle the stream. The default UA
        // is now a default request property, which the resolver's per-DataSpec headers override.
        // v2.0.5: googlevideo is fetched in <=1 MB ranged chunks with exactly the probe's client/headers (ChunkedDataSource).
        val http = ChunkedDataSource.Factory(ChunkedDataSource.httpFactory())
        val upstream = DefaultDataSource.Factory(this, http)
        // The media cache is optional: if it cannot be opened (storage full, locked folder), stream uncached.
        val simple = if (!cacheEnabled) null else runCatching { cache(this) }.onFailure { CrashGuard.nonFatal(this, "SimpleCache", it) }.getOrNull()
        val cached: androidx.media3.datasource.DataSource.Factory = if (simple != null) CacheDataSource.Factory().setCache(simple)
            .setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR) else upstream
        val resolving = ResolvingDataSource.Factory(cached) { spec -> resolveSpec(spec) }
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            // v2.0.5 fast start: playback begins after ~1.2 s of audio (was ExoPlayer's 2.5 s), 2.5 s after a rebuffer.
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, BUFFER_FOR_PLAYBACK_MS, 2_500)
                .setPrioritizeTimeOverSizeThresholds(true).build())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /** Runs on a loader thread. Waits for the coroutine resolution; an interrupt (cancelled load) cancels it. */
    private fun resolveSpec(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != TrackUri.SCHEME) return spec
        val track = TrackUri.track(spec.uri) ?: throw java.io.IOException("Bad track uri")
        val failures = Collections.synchronizedList(mutableListOf<String>())
        urlCache[track.id]?.takeIf { System.currentTimeMillis() - it.second < URL_TTL_MS }?.let { (r, _) -> return specFor(spec, r) }
        // v2.0.14: getOrPut on a synchronizedMap is check-then-act; lock so two loader threads can't both launch a resolve.
        val job = synchronized(inflight) {
            inflight.getOrPut(track.id) {
                io.async { resolver(track, failures) }
            }
        }
        val r = try {
            job.asCompletableFuture().get(RESOLVE_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            job.cancel(); inflight.remove(track.id); throw InterruptedIOException("resolution cancelled")
        } catch (e: TimeoutException) {
            job.cancel(); inflight.remove(track.id)
            com.ravi.musicspot.core.PlaybackLog.add("Timed out finding a stream after $RESOLVE_TIMEOUT_S s: ${track.title}")
            throw java.io.IOException("Timed out finding a stream")
        } catch (e: ExecutionException) {
            inflight.remove(track.id); throw java.io.IOException(e.cause?.message ?: "No stream", e.cause)
        } catch (e: java.util.concurrent.CancellationException) {
            inflight.remove(track.id); throw InterruptedIOException("resolution cancelled")
        }
        inflight.remove(track.id)
        val known = Known(r.info, failures.toList(), r.fallbackFrom)
        resolvedInfo[track.id] = known
        scope.launch { if (player.currentMediaItem?.mediaId == track.id) publishStream(track.id, known) }
        urlCache[track.id] = r to System.currentTimeMillis()
        return specFor(spec, r)
    }

    /** Never touches the player: this runs on ExoPlayer's loader thread. */
    private fun specFor(spec: DataSpec, r: Resolved): DataSpec {
        val b = spec.buildUpon().setUri(Uri.parse(r.uri)).setKey(r.cacheKey)
        // v2.0.3: a googlevideo URL must be fetched as the client that minted it: its User-Agent always, Origin and
        // Referer only for the browser-shaped clients (TVHTML5, WEB_REMIX) - exactly PlayerClient.mediaHeaders().
        val headers = r.headers.ifEmpty { if (isYouTubeMedia(r.uri)) com.ravi.musicspot.yt.PlayerClient.forStreamUrl(r.uri).mediaHeaders() else emptyMap() }
        if (headers.isNotEmpty()) b.setHttpRequestHeaders(spec.httpRequestHeaders + headers)
        return b.build()
    }
    private val urlCache = Collections.synchronizedMap(object : LinkedHashMap<String, Pair<Resolved, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Resolved, Long>>?) = size > 32
    })

    private fun publishStream(id: String, k: Known) {
        val prev = Playback.stream.value?.takeIf { it.trackId == id }
        Playback.stream.value = NowStream(id, k.info, prev?.lossless ?: false, prev?.hiRes ?: false, k.failures, k.fallbackFrom)
    }

    private val analytics = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            val id = player.currentMediaItem?.mediaId ?: return
            val base = Playback.stream.value?.takeIf { it.trackId == id }?.info ?: resolvedInfo[id]?.info ?: return
            val mime = format.sampleMimeType ?: format.containerMimeType
            val codec = when (mime) {
                MimeTypes.AUDIO_OPUS -> "Opus"; MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_FLAC -> "FLAC"; MimeTypes.AUDIO_ALAC -> "ALAC"
                MimeTypes.AUDIO_MPEG -> "MP3"; MimeTypes.AUDIO_VORBIS -> "Vorbis"; MimeTypes.AUDIO_RAW -> "PCM"; MimeTypes.AUDIO_WAV -> "WAV"
                else -> mime?.substringAfter('/')?.uppercase() ?: base.codec
            }
            val lossless = mime in setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV)
            val highBits = format.pcmEncoding != Format.NO_VALUE && format.pcmEncoding != C.ENCODING_PCM_16BIT && format.pcmEncoding != C.ENCODING_PCM_8BIT
            val rate = format.sampleRate.takeIf { it != Format.NO_VALUE }
            val kbps = (format.averageBitrate.takeIf { it > 0 } ?: format.bitrate.takeIf { it > 0 })?.div(1000)
            val info = base.copy(codec = codec, sampleRateHz = rate ?: base.sampleRateHz, bitrateKbps = kbps ?: base.bitrateKbps)
            val prev = Playback.stream.value?.takeIf { it.trackId == id }
            Playback.stream.value = NowStream(id, info, lossless, lossless && ((rate ?: 0) > 48_000 || highBits), prev?.failovers ?: emptyList(),
                prev?.fallbackFrom ?: resolvedInfo[id]?.fallbackFrom)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            // Resolutions for items that are no longer current or among the next two are cancelled.
            val keep = setOfNotNull(id) + upcoming().map { it.id }
            prefetchJob?.cancel()
            synchronized(inflight) { inflight.entries.filter { it.key !in keep }.forEach { it.value.cancel() }; inflight.keys.retainAll(keep) }
            Playback.error.value = null
            replays.keepOnly(id)
            ttfaId = id; ttfaStart = System.currentTimeMillis()
            countedFor = null
            repeatPending = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || (id != null && id == lastCountedId)
            if (id != null) {
                val known = resolvedInfo[id]
                Playback.stream.value = known?.let { NowStream(id, it.info, failovers = it.failures, fallbackFrom = it.fallbackFrom) }
                mediaItem.track()?.let { t -> Graph.library.recordPlay(t, known?.info?.source?.label) }
            } else Playback.stream.value = null
            if (Playback.sleep.mode == SleepTimer.Mode.END_OF_TRACK && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { player.pause(); Playback.sleep.cancel() }
            saveQueue()
            maybeAutoplay()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val id = player.currentMediaItem?.mediaId
            if (isPlaying && id != null && id == ttfaId) {
                val ms = System.currentTimeMillis() - ttfaStart
                com.ravi.musicspot.core.PlaybackLog.add("⏱ Time to first audio: $ms ms (${resolvedInfo[id]?.info?.source?.label ?: "?"}${if (urlPrefetched.remove(id)) ", pre-resolved" else ""})",
                    player.currentMediaItem?.mediaMetadata?.title?.toString()?.take(24))
                ttfaId = null
                prefetchUpcoming()
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { saveQueue() }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveQueue()
        override fun onRepeatModeChanged(repeatMode: Int) = saveQueue()

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId
            val http = generateSequence(error as Throwable?) { it.cause?.takeIf { c -> c !== it } }
                .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            com.ravi.musicspot.core.PlaybackLog.add("ExoPlayer error ${error.errorCodeName} (${error.errorCode}): ${error.message} · cause ${error.cause?.javaClass?.simpleName}: ${error.cause?.message}" +
                (http?.let { " · stream HTTP ${it.responseCode} ${it.dataSpec.uri}" } ?: ""), player.currentMediaItem?.mediaMetadata?.title?.toString()?.take(24))
            // v2.0.5: googlevideo refused the stream (403/404/410): drop the cached URL and stand down ONLY the client that
            // minted it (YtStreamResolver.onPlaybackRefused). Then at most 2 automatic replays per track
            // (ReplayPolicy): the 1st re-resolves (next client), the 2nd goes without YouTube when YouTube refused;
            // after that the error stays on screen. No endless loop, no auto-skip.
            val refusedUrl = refusedYouTubeUrl(error)
            if (id != null) {
                urlCache.remove(id); resolvedInfo.remove(id)
                if (refusedUrl != null) runCatching { onRefused(refusedUrl) }
                when (replays.onError(id, refusedUrl != null, Graph.online.value)) {
                    com.ravi.musicspot.logic.ReplayPolicy.Decision.REPLAY -> {
                        com.ravi.musicspot.core.PlaybackLog.add("Automatic replay ${replays.replays(id)}/${com.ravi.musicspot.logic.ReplayPolicy.MAX_AUTO_REPLAYS}")
                        recoverAtSamePosition(); return
                    }
                    com.ravi.musicspot.logic.ReplayPolicy.Decision.REPLAY_WITHOUT_YOUTUBE -> {
                        excludeYouTube(id)
                        com.ravi.musicspot.core.PlaybackLog.add("Automatic replay ${replays.replays(id)}/${com.ravi.musicspot.logic.ReplayPolicy.MAX_AUTO_REPLAYS} without YouTube")
                        recoverAtSamePosition(); return
                    }
                    com.ravi.musicspot.logic.ReplayPolicy.Decision.STOP ->
                        com.ravi.musicspot.core.PlaybackLog.add("No more automatic replays for this track: showing the error")
                }
            }
            val summary = com.ravi.musicspot.core.PlaybackLog.summaryFor(id)
            val base = error.cause?.message ?: error.message ?: "Playback error"
            Playback.error.value = when {
                !summary.isNullOrBlank() && base.contains(summary) -> summary
                !summary.isNullOrBlank() -> summary
                http != null -> "Stream refused (HTTP ${http.responseCode}) · ${error.errorCodeName}"
                else -> "$base · ${error.errorCodeName}"
            }
        }
    }

    private fun recoverAtSamePosition() {
        val pos = player.currentPosition
        ttfaId = player.currentMediaItem?.mediaId; ttfaStart = System.currentTimeMillis()
        player.prepare(); player.seekTo(pos); player.play()
    }

    /** True the first time YouTube is excluded for [id] (one more recovery then goes to the other sources). */
    private fun excludeYouTube(id: String): Boolean = synchronized(ytExcluded) {
        if (ytExcluded.size > 200) ytExcluded.clear()
        ytExcluded.add(id)
    }

    private fun nextId(): String? = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { player.getMediaItemAt(it).mediaId }

    /**
     * Autoplay / "Infinite play" (v2.0.5; setting `autoplay`, default on, toggle in the Up next pane). When ≤ 2 songs
     * are left, about 10 recommendations are appended: YouTube Music radio for the current song (Innertube `next`
     * with playlistId RDAMVM<videoId>), or JioSaavn's song recommendations (reco.getreco) when YouTube fails; then
     * deduped against the queue and recent history and re-ranked towards the artists/songs the user plays and likes
     * (Taste.autoplayMix). Appended items carry the "autoplay" extra, so the queue shows them under an Autoplay divider.
     * Every player read/write is on the main thread; only the network calls run on IO (v2.0.2 fix).
     */
    private fun maybeAutoplay() {
        if (!Graph.settings.value.autoplay || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining > AUTOPLAY_WHEN_LEFT) return
        val seed = player.currentMediaItem?.track() ?: return
        if (autoplayFor == seed.id) return
        autoplayFor = seed.id
        val queue = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).track() }
        scope.launch {
            val add = withContext(Dispatchers.IO) {
                try {
                    val candidates = radioProvider(seed)
                    val lib = Graph.library.data.value
                    val now = System.currentTimeMillis()
                    val stats = Graph.playStats.data.value.stats.values
                    com.ravi.musicspot.logic.Taste.autoplayMix(candidates, queue, lib.history.take(60).map { it.track },
                        com.ravi.musicspot.logic.Taste.artistWeights(stats, lib.liked, now), lib.liked.mapTo(HashSet()) { it.id }, AUTOPLAY_COUNT)
                        .also { com.ravi.musicspot.core.PlaybackLog.add("Autoplay: ${candidates.size} recommendations for \"${seed.title.take(30)}\", appended ${it.size}") }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; CrashGuard.nonFatal(this@PlaybackService, "autoplay", e); emptyList() }
            }
            if (!Graph.settings.value.autoplay) return@launch
            // Back on the main thread (scope = Dispatchers.Main.immediate): the player's application thread.
            val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val items = add.filter { it.id !in existing }.map { it.toMediaItem(autoplay = true) }
            if (items.isNotEmpty()) player.addMediaItems(items)
        }
    }

    /** The next one or two queue items (shuffle aware), main thread only. */
    private fun upcoming(): List<Track> {
        val tl = player.currentTimeline
        if (tl.isEmpty) return emptyList()
        val out = mutableListOf<Track>()
        var i = player.currentMediaItemIndex
        repeat(PREFETCH_AHEAD) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || i >= player.mediaItemCount) return out
            player.getMediaItemAt(i).track()?.let(out::add)
        }
        return out
    }

    private var prefetchJob: kotlinx.coroutines.Job? = null
    private val urlPrefetched: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * v2.0.5: once the current song is playing, resolve the next 1–2 items in the background, one after another (the
     * resolver's googlevideo availability wait and probe included), so a skip or the next song starts from a ready URL.
     */
    private fun prefetchUpcoming() {
        if (!Graph.online.value) return
        val next = upcoming().filter { t -> urlCache[t.id]?.let { System.currentTimeMillis() - it.second < URL_TTL_MS } != true && t.id !in inflight }
        if (next.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = io.launch {
            for (t in next) {
                com.ravi.musicspot.core.PlaybackLog.add("Pre-resolving upcoming \"${t.title.take(30)}\"")
                val failures = Collections.synchronizedList(mutableListOf<String>())
                val job = inflight.getOrPut(t.id) { io.async { resolver(t, failures) } }
                val r = try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { inflight.remove(t.id); continue }
                inflight.remove(t.id)
                resolvedInfo[t.id] = Known(r.info, failures.toList(), r.fallbackFrom)
                urlCache[t.id] = r to System.currentTimeMillis()
                urlPrefetched += t.id
            }
        }
    }

    private var autoplayFor: String? = null

    private fun saveQueue() {
        val n = player.mediaItemCount
        if (n == 0) return
        val tracks = (0 until n).mapNotNull { player.getMediaItemAt(it).track() }
        Graph.queueStore.save(QueueSnapshot(tracks.take(500), player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition, player.shuffleModeEnabled, player.repeatMode))
    }

    private suspend fun restoreQueue() {
        if (player.mediaItemCount > 0) return
        val snap = io.async { Graph.queueStore.read() }.await() ?: return
        if (snap.tracks.isEmpty()) return
        player.setMediaItems(snap.tracks.map { it.toMediaItem() }, snap.index.coerceIn(0, snap.tracks.lastIndex), snap.positionMs)
        player.shuffleModeEnabled = snap.shuffle
        player.repeatMode = snap.repeat
        player.prepare()
    }

    /** Sleep timer + fade-out/fade-in crossfade + periodic queue position save. */
    private suspend fun ticker() {
        var saveCounter = 0
        while (scope.isActive) {
            delay(200)
            val s = Graph.settings.value
            val now = System.currentTimeMillis()
            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            val pos = player.currentPosition
            val remaining = if (dur > 0) dur - pos else Long.MAX_VALUE
            val sleep = Playback.sleep
            if (sleep.mode != SleepTimer.Mode.OFF) {
                Playback.sleepTick.value = now
                val last = player.currentMediaItemIndex >= player.mediaItemCount - 1
                if (player.isPlaying && sleep.shouldPause(now, remaining, last, s.sleepFinishSong)) { player.pause(); sleep.cancel(); player.volume = 1f; continue }
            }
            var vol = 1f
            if (s.sleepFade && sleep.mode == SleepTimer.Mode.DURATION) vol *= sleep.fadeFactor(now, 30_000)
            val cf = if (s.gapless) 0L else s.crossfadeSec * 1000L
            if (cf > 0 && dur > cf * 3) {
                if (remaining < cf) vol *= (remaining.toFloat() / cf).coerceIn(0f, 1f)
                if (pos < cf / 2) vol *= (pos.toFloat() / (cf / 2)).coerceIn(0.05f, 1f)
            }
            if (player.volume != vol) player.volume = vol
            // v2.0.5 play counting: after ~30 s (half of a short song); a consecutive replay counts as a repeat.
            val curItem = player.currentMediaItem
            if (curItem != null && player.isPlaying && countedFor != curItem.mediaId && pos >= com.ravi.musicspot.logic.Taste.countThresholdMs(dur)) {
                countedFor = curItem.mediaId
                curItem.track()?.let { t -> runCatching { Graph.playStats.count(t, repeatPending) } }
                lastCountedId = curItem.mediaId; repeatPending = false
            }
            if (++saveCounter >= 25) { saveCounter = 0; if (player.isPlaying) saveQueue() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private var stoppedByTaskRemoval = false

    override fun onTaskRemoved(rootIntent: Intent?) {
        stoppedByTaskRemoval = true
        // v2.0.8: closing the app (swiping it away from Recents) stops the music too.
        // v2.0.13: the notification used to stay after a swipe-away. Media3 re-posts a "paused" notification when the
        // player stops while the session is alive, so: save, release the session first, then drop the foreground
        // notification and cancel Media3's notification id (now and once more a moment later, after any late re-post).
        runCatching { player.playWhenReady = false; saveQueue(); player.stop() }
        runCatching { session?.run { release() }; session = null }
        runCatching { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) }
        clearMediaNotification(applicationContext)
        val app = applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ clearMediaNotification(app) }, 600)
        stopSelf()
    }

    private fun clearMediaNotification(ctx: android.content.Context) = runCatching {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.cancel(androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        // Any other notification on the playback channel (never the download channel).
        nm.activeNotifications.filter { it.notification.channelId == androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID }.forEach { nm.cancel(it.tag, it.id) }
    }

    private fun pushWidget() = runCatching {
        val md = player.currentMediaItem?.mediaMetadata
        com.ravi.musicspot.widget.NowPlayingWidget.update(this, com.ravi.musicspot.widget.NowPlayingWidget.State(
            md?.title?.toString(), md?.artist?.toString(), md?.artworkUri?.toString(), player.playWhenReady && player.mediaItemCount > 0))
    }

    override fun onDestroy() {
        runCatching {
            com.ravi.musicspot.widget.NowPlayingWidget.Controls.handle = null
            val md = if (::player.isInitialized) player.currentMediaItem?.mediaMetadata else null // v2.0.14: guard lateinit
            com.ravi.musicspot.widget.NowPlayingWidget.update(this, com.ravi.musicspot.widget.NowPlayingWidget.State(md?.title?.toString(), md?.artist?.toString(), md?.artworkUri?.toString(), false))
        }
        if (::player.isInitialized) runCatching { saveQueue() } // v2.0.14: never let a save failure skip release
        synchronized(inflight) { inflight.values.forEach { it.cancel() }; inflight.clear() }
        scope.cancel(); io.cancel()
        if (current === this) current = null
        if (::player.isInitialized) runCatching { player.release() } // v2.0.14: guard lateinit
        session?.run { runCatching { release() } }
        session = null
        if (stoppedByTaskRemoval) clearMediaNotification(applicationContext)
        super.onDestroy()
    }

    internal val playerForTest: ExoPlayer get() = player

    companion object {
        private const val URL_TTL_MS = 30 * 60_000L
        /** Autoplay radio source (network only, called on Dispatchers.IO). Replaceable in tests. */
        @Volatile internal var radioProvider: suspend (Track) -> List<Track> = { seed ->
            val log = com.ravi.musicspot.core.PlaybackLog
            val yt = if (com.ravi.musicspot.model.SourceKind.YOUTUBE !in Graph.settings.value.enabledSources) emptyList() else try {
                val ytSeed = if (seed.source == com.ravi.musicspot.model.SourceKind.YOUTUBE) seed
                    else Graph.yt.search("${seed.title} ${seed.artist}", 5).let { com.ravi.musicspot.source.TrackMatcher.best(seed, it) }
                ytSeed?.let { Graph.yt.radio(it.nativeId) }.orEmpty()
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; log.add("Autoplay: YouTube radio failed: ${e.javaClass.simpleName}: ${e.message}"); emptyList() }
            yt.ifEmpty {
                try { Graph.jiosaavn.recommendationsFor(seed) }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; log.add("Autoplay: JioSaavn reco failed: ${e.javaClass.simpleName}: ${e.message}"); emptyList() }
            }
        }
        /** Stream resolution (called on Dispatchers.IO). Replaceable in tests. */
        @Volatile internal var resolver: suspend (Track, MutableList<String>) -> Resolved = { track, failures ->
            Graph.awaitReady()
            val s = Graph.settings.value
            val exclude = if (track.id in ytExcluded) setOf(com.ravi.musicspot.model.SourceKind.YOUTUBE) else emptySet()
            val log = com.ravi.musicspot.core.PlaybackLog
            val attempt = com.ravi.musicspot.core.PlaybackLog.Attempt(track.title.take(24))
            kotlinx.coroutines.withContext(attempt) {
                log.log("▶ Play \"${track.title}\" by ${track.artist} (source ${track.source.label}, id ${track.id}, quality ${s.effectiveQuality(Graph.metered)}, metered=${Graph.metered}, online=${Graph.online.value})")
                try {
                    Graph.sources.resolve(track, s.effectiveQuality(Graph.metered), Graph.metered, exclude) { cand, e -> failures += "${cand.source.label}: ${e.message?.take(80)}" }
                        .also { r -> log.forget(track.id); log.log("✓ Stream from ${r.info.source.label}${r.fallbackFrom?.let { " (fallback from ${it.label})" } ?: ""}: ${r.uri}") }
                } catch (e: kotlinx.coroutines.CancellationException) { log.log("Resolution cancelled"); throw e
                } catch (e: Exception) {
                    val summary = attempt.summary()
                    log.remember(track.id, summary)
                    log.log("✗ No stream: ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
            }
        }
        /** Feeds a playback refusal back to the YouTube resolver. Replaceable in tests. */
        @Volatile internal var onRefused: (String) -> Unit = { url -> Graph.yt.onPlaybackRefused(url) }
        /** Tracks for which YouTube is excluded after repeated refusals (bounded). */
        internal val ytExcluded: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        internal const val BUFFER_FOR_PLAYBACK_MS = 1_200
        internal const val PREFETCH_AHEAD = 2
        internal const val AUTOPLAY_WHEN_LEFT = 2
        internal const val AUTOPLAY_COUNT = 10
        /** v2.0.4: YouTube budget + a fresh fallback budget must fit (was 45 s). */
        internal const val RESOLVE_TIMEOUT_S = 60L

        internal fun isYouTubeMedia(url: String): Boolean =
            runCatching { Uri.parse(url).host }.getOrNull()?.endsWith("googlevideo.com") == true

        /** The googlevideo URL a 403/404/410 came from, if [error] is one. */
        internal fun refusedYouTubeUrl(error: Throwable): String? {
            val e = generateSequence(error as Throwable?) { it.cause?.takeIf { c -> c !== it } }
                .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull() ?: return null
            if (e.responseCode !in setOf(403, 404, 410)) return null
            return e.dataSpec.uri.toString().takeIf { isYouTubeMedia(it) }
        }
        /** Test hook: Robolectric on aarch64 has no native SQLite for SimpleCache's index. */
        @Volatile internal var cacheEnabled = true
        /** Test hook: the service's player (main thread only). */
        @Volatile internal var current: PlaybackService? = null

        @Volatile private var simpleCache: SimpleCache? = null
        fun cache(ctx: Context): SimpleCache = simpleCache ?: synchronized(this) {
            simpleCache ?: SimpleCache(File(ctx.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(Graph.settings.value.cacheLimitMb.toLong() * 1024 * 1024),
                StandaloneDatabaseProvider(ctx)).also { simpleCache = it }
        }

        /** Android 14+: prefer bit-perfect mixer behaviour on an attached USB DAC (ported idea from OpenTune BitPerfectUsb). */
        fun applyBitPerfect(ctx: Context) {
            if (Build.VERSION.SDK_INT < 34) { Playback.bitPerfectStatus.value = "Needs Android 14+"; return }
            val am = ctx.getSystemService(AudioManager::class.java)
            val dac = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                ?: run { Playback.bitPerfectStatus.value = "No USB DAC connected"; return }
            val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val mixer = am.getSupportedMixerAttributes(dac).firstOrNull { it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
                ?: run { Playback.bitPerfectStatus.value = "${dac.productName}: bit-perfect not offered by this device"; return }
            Playback.bitPerfectStatus.value = if (runCatching { am.setPreferredMixerAttributes(attrs, dac, mixer) }.getOrDefault(false))
                "Bit-perfect on ${dac.productName} (${mixer.format.sampleRate} Hz)" else "${dac.productName}: request refused"
        }

        fun cacheBytes(ctx: Context): Long = runCatching { cache(ctx).cacheSpace }.getOrDefault(0L)
        fun clearCache(ctx: Context) { runCatching { val c = cache(ctx); c.keys.toList().forEach { c.removeResource(it) } } }
    }
}

/**
 * v2.0.9 crash fix (Lenovo TB-8505X, Android 10): the session asked BasePlayer.getBufferedPercentage while the
 * buffered position was a huge negative number, and Util.percentInt threw "Out of range". Positions the session
 * reads are clamped here so a bad value from a source can never crash the app.
 */
private class SafePositionPlayer(p: androidx.media3.common.Player) : androidx.media3.common.ForwardingPlayer(p) {
    private fun dur(): Long = runCatching { super.getDuration() }.getOrDefault(androidx.media3.common.C.TIME_UNSET)
    override fun getBufferedPosition(): Long {
        val b = runCatching { super.getBufferedPosition() }.getOrDefault(0L)
        val d = dur()
        return if (d == androidx.media3.common.C.TIME_UNSET || d <= 0) b.coerceAtLeast(0L) else b.coerceIn(0L, d)
    }
    override fun getBufferedPercentage(): Int {
        val d = dur()
        if (d == androidx.media3.common.C.TIME_UNSET || d <= 0) return 0
        return ((getBufferedPosition().toDouble() * 100.0) / d.toDouble()).toInt().coerceIn(0, 100)
    }
    override fun getTotalBufferedDuration(): Long = runCatching { super.getTotalBufferedDuration() }.getOrDefault(0L).coerceAtLeast(0L)
    override fun getContentBufferedPosition(): Long {
        val b = runCatching { super.getContentBufferedPosition() }.getOrDefault(0L)
        val d = dur()
        return if (d == androidx.media3.common.C.TIME_UNSET || d <= 0) b.coerceAtLeast(0L) else b.coerceIn(0L, d)
    }
    override fun getCurrentPosition(): Long = runCatching { super.getCurrentPosition() }.getOrDefault(0L).coerceAtLeast(0L)
}
