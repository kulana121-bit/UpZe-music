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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.ravi.musicspot.core.BackgroundFaults)
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
    // 1.0.2 Settings › Playback extras
    @Volatile private var sbFor: Pair<String, List<SponsorBlock.Segment>>? = null
    private var pausedByMute = false
    private var errorSkips = 0
    private var switchedFor: String? = null
    /** 1.0.2: -1 = no fade; 0 = fade pending (waiting for audio); else the time the fade-in started. */
    private var fadeInFrom = -1L
    /** 1.0.6: the ticker's own gain (sleep fade, fade-in after a switch, crossfade). */
    private var baseVolume = 1f
    /** 1.0.6: the play/pause ramp's gain; multiplies [baseVolume]. Main thread only. */
    private var rampGain = 1f
    private var rampJob: kotlinx.coroutines.Job? = null
    /** 1.0.6: the item whose next song was pre-buffered at 80 % / 20 s before its end. */
    private var prebufferedFor: String? = null
    private var prebufferJob: kotlinx.coroutines.Job? = null
    /** 1.0.6: the media cache data-source factory (null when the cache could not be opened). */
    private var cacheFactory: CacheDataSource.Factory? = null
    private val volumeObserver by lazy {
        object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onVolumeChanged()
        }
    }
    private var lastCountedId: String? = null
    /** 1.0.5: posts work to the player's application looper (built on the main thread in onCreate). */
    private val playerHandler by lazy { android.os.Handler(player.applicationLooper) }
    private var repeatPending = false
    private var pure = false

    override fun onCreate() {
        super.onCreate()
        LoudnessStore.init(filesDir) // 1.0.10: measured loudness per track (normalisation)
        pure = Graph.settings.value.pureHiRes || Graph.settings.value.bitPerfectUsb
        player = buildPlayer()
        current = this
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, SafePositionPlayer(player, fader)).setSessionActivity(open).setCallback(object : MediaSession.Callback {
            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(mediaItems.map { item -> item.track()?.toMediaItem() ?: item }.toMutableList())
        }).build()
        player.addListener(listener)
        // v2.0.7 widget: buttons drive this player directly; the card follows the song and play state.
        com.ravi.musicspot.widget.NowPlayingWidget.Controls.handle = { a ->
            val act = {
                when (a) {
                    // 1.0.6: through the 150 ms volume ramp, like every other play/pause.
                    com.ravi.musicspot.widget.NowPlayingWidget.ACTION_TOGGLE -> { if (player.isPlaying) fader.pause { player.pause() } else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); fader.play { player.play() } } }
                    com.ravi.musicspot.widget.NowPlayingWidget.ACTION_NEXT -> player.seekToNextMediaItem()
                    com.ravi.musicspot.widget.NowPlayingWidget.ACTION_PREV -> player.seekToPrevious()
                    else -> {}
                }
                Unit
            }
            // 1.0.5: the player may only be touched on its application looper; a call from any other thread is posted.
            if (android.os.Looper.myLooper() == player.applicationLooper) { act(); player.mediaItemCount > 0 }
            else { playerHandler.post { runCatching { act() }.onFailure { com.ravi.musicspot.core.PlaybackLog.add("Posted player action failed: ${it.javaClass.simpleName}: ${it.message}") } }; true }
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
        runCatching { contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, volumeObserver) }
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
        val cacheF = if (simple != null) CacheDataSource.Factory().setCache(simple)
            .setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR) else null
        cacheFactory = cacheF // 1.0.6: also used to pre-buffer the next song's first chunk
        val cached: androidx.media3.datasource.DataSource.Factory = cacheF ?: upstream
        val resolving = ResolvingDataSource.Factory(cached) { spec -> resolveSpec(spec) }
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving).setLoadErrorHandlingPolicy(SeamlessRetryPolicy(::onLoadRetry, ::onLoadExpired)))
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
        // 1.0.2: a song already fully in the media cache plays from it, with or without a network.
        // 1.0.7: ...but a lossy cached copy no longer pins the song's quality: online, with lossless upgrades wanted,
        // the sources are asked first (lossless first in the priority order) and the cached copy is the fallback.
        val cachedCopy = if (track.source.isLive) null else runCatching { Graph.cached.offline(track) }.getOrNull()
        fun playCached(r: Resolved): DataSpec {
            val known = Known(r.info, emptyList(), null)
            resolvedInfo[track.id] = known
            scope.launch { if (player.currentMediaItem?.mediaId == track.id) publishStream(track.id, known) }
            return specFor(spec, r)
        }
        val fresh0 = urlCache[track.id]
        if (cachedCopy != null && (fresh0 == null || !urlUsable(fresh0.first, fresh0.second)) &&
            !com.ravi.musicspot.source.LosslessUpgrade.bypassCache(cachedCopy.info, Graph.online.value, upgradeWanted())) return playCached(cachedCopy)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        // 1.0.5: a cached URL is reused only while it is inside the TTL *and* not about to pass its own `expire` time.
        urlCache[track.id]?.takeIf { urlUsable(it.first, it.second) }?.let { (r, _) -> return checkedSpec(spec, track.id, r) }
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
            inflight.remove(track.id)
            if (cachedCopy != null && spec.position == 0L) { // 1.0.7: no better stream reachable: the cached copy still plays
                com.ravi.musicspot.core.PlaybackLog.add("No fresh stream (${e.cause?.message?.take(60)}): playing the cached copy")
                return playCached(cachedCopy)
            }
            throw java.io.IOException(e.cause?.message ?: "No stream", e.cause)
        } catch (e: java.util.concurrent.CancellationException) {
            inflight.remove(track.id); throw InterruptedIOException("resolution cancelled")
        }
        inflight.remove(track.id)
        val known = Known(r.info, failures.toList(), r.fallbackFrom)
        resolvedInfo[track.id] = known
        scope.launch { if (player.currentMediaItem?.mediaId == track.id) publishStream(track.id, known) }
        urlCache[track.id] = r to System.currentTimeMillis()
        runCatching { Graph.cached.record(track, r) }
        return checkedSpec(spec, track.id, r)
    }

    /**
     * 1.0.2 seamless switch: a load that starts at byte 0 fixes which bytes this song plays from ([streamKey]); a load
     * that resumes mid-stream (a retry after a refused/expired URL, or a seek) must get the very same bytes, or the
     * offset would land in the wrong file. Same key → continue silently; other key → [StreamSwitchException].
     */
    private fun checkedSpec(spec: DataSpec, trackId: String, r: Resolved): DataSpec {
        if (spec.position == 0L) streamKey[trackId] = r.cacheKey
        else streamKey[trackId]?.let { k -> if (k != r.cacheKey) throw StreamSwitchException(trackId, k, r.cacheKey) }
        return specFor(spec, r)
    }
    private val streamKey = Collections.synchronizedMap(object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    })

    /** Loader thread: before a seamless retry, forget the URL that failed so the retry resolves a fresh one. */
    private fun onLoadRetry(trackUri: Uri, refusedUrl: String?) {
        val id = TrackUri.track(trackUri)?.id ?: return
        // 1.0.5: drop the cached URL only when it is the one that failed (or is stale). A fresh URL that a resume
        // refresh ([refreshStaleUrlOnResume]) already stored must survive, so the retry continues from it at once.
        val dropped = dropCachedUrl(id, refusedUrl)
        val expired = refusedUrl != null && com.ravi.musicspot.yt.YtUrlExpiry.isExpired(refusedUrl, mintedAtMs = null, marginMs = 0)
        if (dropped || refusedUrl != null)
            com.ravi.musicspot.core.PlaybackLog.add("Stream hiccup${refusedUrl?.let { if (expired) " (URL expired)" else " (URL refused)" } ?: ""}: getting a fresh URL while the buffer keeps playing")
        // The resolver's client bookkeeping is done on main, like the onPlayerError path. The resolver itself tells an
        // expired URL from a refusal (YtStreamResolver.onPlaybackRefused) and only stands a client down for the latter.
        if (refusedUrl != null) scope.launch { runCatching { onRefused(refusedUrl) } }
    }

    /** 1.0.5 (loader thread): ChunkedDataSource saw the open URL pass its `expire` time; get a fresh one, same offset. */
    private fun onLoadExpired(trackUri: Uri, expiredUrl: String) {
        val id = TrackUri.track(trackUri)?.id ?: return
        dropCachedUrl(id, expiredUrl)
        com.ravi.musicspot.core.PlaybackLog.add("Stream URL expired during a pause: resolving a fresh one at the same position")
    }

    /** 1.0.5: removes [id]'s cached URL if it is [failedUrl] (null = any), or no longer usable. True when removed. */
    private fun dropCachedUrl(id: String, failedUrl: String?): Boolean = synchronized(urlCache) {
        val e = urlCache[id] ?: return@synchronized false
        if (failedUrl == null || e.first.uri == failedUrl || !urlUsable(e.first, e.second)) { urlCache.remove(id); true } else false
    }

    /** Never touches the player: this runs on ExoPlayer's loader thread. */
    private fun specFor(spec: DataSpec, r: Resolved): DataSpec {
        val b = spec.buildUpon().setUri(Uri.parse(r.uri)).setKey(r.cacheKey)
        // 1.0.2: a live radio stream never ends; keep it out of the media cache.
        if (r.info.source == com.ravi.musicspot.model.SourceKind.RADIO) b.setFlags(spec.flags or DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)
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

    /** 1.0.10: tells the engine's single levelling stage which track plays and its ReplayGain tags (if any). */
    private fun pushLevelling() {
        if (!::player.isInitialized) return
        val id = player.currentMediaItem?.mediaId
        val tags = runCatching { ReplayGain.fromTracks(player.currentTracks) }.getOrNull()
        Playback.engine.setTrack(id, tags)
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = pushLevelling()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            pushLevelling()
            val id = mediaItem?.mediaId
            // Resolutions for items that are no longer current or among the next two are cancelled.
            val keep = setOfNotNull(id) + upcoming().map { it.id }
            prefetchJob?.cancel()
            synchronized(inflight) { inflight.entries.filter { it.key !in keep }.forEach { it.value.cancel() }; inflight.keys.retainAll(keep) }
            Playback.error.value = null
            replays.keepOnly(id)
            if (switchedFor != id) switchedFor = null; fadeInFrom = -1L
            ttfaId = id; ttfaStart = System.currentTimeMillis()
            countedFor = null
            if (prebufferedFor != id) { prebufferedFor = null } // 1.0.6: the new song pre-buffers its own next one
            repeatPending = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || (id != null && id == lastCountedId)
            if (id != null) {
                val known = resolvedInfo[id]
                Playback.stream.value = known?.let { NowStream(id, it.info, failovers = it.failures, fallbackFrom = it.fallbackFrom) }
                mediaItem.track()?.let { t -> Graph.library.recordPlay(t, known?.info?.source?.label) }
            } else Playback.stream.value = null
            if (Playback.sleep.mode == SleepTimer.Mode.END_OF_TRACK && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { player.pause(); Playback.sleep.cancel() }
            loadSponsorSegments(mediaItem?.track())
            saveQueue()
            scheduleTidy() // 1.0.2: drop played songs and duplicates, then top up Infinite play
            // 1.0.10: a new queue (setMediaItems / restore) whose current song has the same id as the previous seed
            // must still get its radio; the per-seed guard only dedupes within one queue.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) autoplayFor = null
            maybeAutoplay()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            tickerPlaying.value = isPlaying // 1.0.10: wakes the idle ticker
            val id = player.currentMediaItem?.mediaId
            if (isPlaying) { errorSkips = 0; pausedByMute = false }
            // 1.0.6: playback restarted by a path that doesn't go through the session (error recovery, unmute resume)
            // after a ramped pause: bring the ramp gain back up instead of playing silently.
            if (isPlaying && rampGain < 1f && rampJob?.isActive != true) fader.play { }
            if (isPlaying && id != null && id == ttfaId) {
                val ms = System.currentTimeMillis() - ttfaStart
                com.ravi.musicspot.core.PlaybackLog.add("⏱ Time to first audio: $ms ms (${resolvedInfo[id]?.info?.source?.label ?: "?"}${if (urlPrefetched.remove(id)) ", pre-resolved" else ""})",
                    player.currentMediaItem?.mediaMetadata?.title?.toString()?.take(24))
                ttfaId = null
                prefetchUpcoming()
                maybeUpgradeToLossless(id) // 1.0.7
            }
        }

        /** 1.0.5: resuming after a long pause: refresh an expiring URL before the buffer runs out. */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) runCatching { refreshStaleUrlOnResume() }
                .onFailure { com.ravi.musicspot.core.PlaybackLog.add("Resume URL refresh failed: ${it.javaClass.simpleName}: ${it.message}") }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            saveQueue()
            // 1.0.2: songs added (Play next, Add to queue, a new list) are de-duplicated right away.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scheduleTidy()
        }
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
            // 1.0.2: the fresh stream is another source/rendition: switch at the same time position (already resolved, so
            // it starts in about a second) and fade in, once per song before the normal replay policy applies.
            if (id != null && generateSequence(error as Throwable?) { it.cause?.takeIf { c -> c !== it } }.any { it is StreamSwitchException } && switchedFor != id) {
                switchedFor = id
                streamKey.remove(id)
                com.ravi.musicspot.core.PlaybackLog.add("Switching source at the same position")
                recoverAtSamePosition(); return
            }
            if (id != null) {
                urlCache.remove(id); resolvedInfo.remove(id)
                if (refusedUrl != null) runCatching { onRefused(refusedUrl) }
                    .onFailure { com.ravi.musicspot.core.PlaybackLog.add("Refused-URL handling failed: ${it.javaClass.simpleName}: ${it.message}") }
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
                    com.ravi.musicspot.logic.ReplayPolicy.Decision.STOP -> {
                        // 1.0.2 Playback › Skip songs that fail: go on to the next one (at most 5 in a row, so a dead
                        // network doesn't race through the whole queue).
                        if (Graph.settings.value.skipOnError && player.hasNextMediaItem() && errorSkips < MAX_ERROR_SKIPS) {
                            errorSkips++
                            com.ravi.musicspot.core.PlaybackLog.add("Skipping to the next song ($errorSkips/$MAX_ERROR_SKIPS in a row)")
                            Playback.error.value = null
                            player.seekToNextMediaItem(); player.prepare(); player.play()
                            return
                        }
                        com.ravi.musicspot.core.PlaybackLog.add("No more automatic replays for this track: showing the error")
                    }
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
        fadeInFrom = 0L // 1.0.2: the ticker ramps volume up over FADE_IN_MS once audio is back
        player.prepare(); player.seekTo(pos); player.play()
    }

    /** 1.0.7: lossless upgrades follow the quality setting: off for High (and for mobile data, whose default is High). */
    private fun upgradeWanted(): Boolean = Graph.settings.value.effectiveQuality(Graph.metered) != com.ravi.musicspot.model.Quality.HIGH

    private val upgradeTried: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private var upgradeJob: kotlinx.coroutines.Job? = null

    /**
     * 1.0.7 automatic quality upgrade (main thread). Once [id] is audibly playing from a lossy stream, the enabled
     * lossless sources are searched in the background. When the same recording is found as a real lossless stream,
     * it becomes the stream for this song and playback switches at the same position with the usual fade-in
     * (same path as a source switch). Tried once per song per session; never for live radio, downloads or offline.
     */
    private fun maybeUpgradeToLossless(id: String) {
        val t = player.currentMediaItem?.track()?.takeIf { it.id == id } ?: return
        val info = resolvedInfo[id]?.info ?: return
        if (t.source.isLive || !Graph.online.value || !upgradeWanted()) return
        if (com.ravi.musicspot.source.QualityEstimate.isLosslessStream(info)) return
        if (runCatching { Graph.downloads.resolvedFor(id) }.getOrNull() != null) return
        if (!upgradeTried.add(id)) return
        if (upgradeTried.size > 500) upgradeTried.clear()
        upgradeJob?.cancel()
        upgradeJob = io.launch {
            com.ravi.musicspot.core.PlaybackLog.add("Upgrade: playing ${info.label}, looking for lossless", t.title.take(24))
            val r = try { Graph.sources.losslessUpgrade(t, info, Graph.metered) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                ?: run { com.ravi.musicspot.core.PlaybackLog.add("Upgrade: no lossless copy found"); return@launch }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (player.currentMediaItem?.mediaId != id) return@withContext // the song changed meanwhile
                val known = Known(r.info, emptyList(), null)
                urlCache[id] = r to System.currentTimeMillis()
                resolvedInfo[id] = known
                streamKey.remove(id)
                publishStream(id, known)
                com.ravi.musicspot.core.PlaybackLog.add("Upgrade: switching to ${r.info.label} at the same position")
                // A READY player ignores prepare(): stop (keeps the queue and the position), then reload there.
                val pos = player.currentPosition
                val playing = player.playWhenReady
                player.stop()
                ttfaId = id; ttfaStart = System.currentTimeMillis()
                fadeInFrom = 0L
                player.prepare(); player.seekTo(pos); if (playing) player.play()
                runCatching { Graph.cached.record(t, r) } // the cached copy of this song is now the lossless one
            }
        }
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
        // 1.0.2: a Listen together listener follows the host's queue; a radio station is its own endless stream.
        if (com.ravi.musicspot.together.ListenTogether.isListener || player.currentMediaItem?.track()?.source?.isLive == true) return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining > AUTOPLAY_WHEN_LEFT) return
        val seed = player.currentMediaItem?.track() ?: return
        if (autoplayFor == seed.id) return
        autoplayFor = seed.id
        val queue = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).track() }
        // 1.0.2: fill up to AUTOPLAY_TARGET (20) songs ahead, and top up again whenever ≤ AUTOPLAY_WHEN_LEFT are left.
        val need = (AUTOPLAY_TARGET - remaining).coerceAtLeast(1)
        scope.launch {
            val add = withContext(Dispatchers.IO) {
                try {
                    val lib = Graph.library.data.value
                    val now = System.currentTimeMillis()
                    val statsData = Graph.playStats.data.value
                    val stats = statsData.stats.values
                    val weights = com.ravi.musicspot.logic.Taste.artistWeights(stats, lib.liked, now)
                    // 1.0.6 on-device time-of-day mix: artists the user favours at this hour get a small extra lift.
                    val tod = com.ravi.musicspot.logic.TimeOfDay.artistAffinity(stats, statsData.dayparts, com.ravi.musicspot.logic.TimeOfDay.partAt(now))
                    val likedIds = lib.liked.mapTo(HashSet()) { it.id }
                    val recent = lib.history.take(60).map { it.track }
                    val candidates = radioProvider(seed)
                    var picked = com.ravi.musicspot.logic.Taste.autoplayMix(candidates, queue, recent, weights, likedIds, need, tod)
                    // One radio rarely has 20 fresh songs: continue from the last pick's radio until full (max 2 more).
                    var hops = 0
                    while (picked.size < need && picked.isNotEmpty() && hops < 2) {
                        hops++
                        val more = radioProvider(picked.last())
                        val extra = com.ravi.musicspot.logic.Taste.autoplayMix(more, queue + picked, recent, weights, likedIds, need - picked.size, tod)
                        if (extra.isEmpty()) break
                        picked = picked + extra
                    }
                    picked.also { com.ravi.musicspot.core.PlaybackLog.add("Autoplay: ${candidates.size} recommendations for \"${seed.title.take(30)}\", appended ${it.size} of $need") }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; CrashGuard.nonFatal(this@PlaybackService, "autoplay", e); emptyList() }
            }
            if (!Graph.settings.value.autoplay) return@launch
            // Back on the main thread (scope = Dispatchers.Main.immediate): the player's application thread.
            val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val items = add.filter { it.id !in existing }.map { it.toMediaItem(autoplay = true) }
            if (items.isNotEmpty()) player.addMediaItems(items)
        }
    }

    private val tidyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var tidyPosted = false

    /** 1.0.2: runs [tidyQueue] once after the current player callback returns (never mutates inside a callback). */
    private fun scheduleTidy() {
        if (tidyPosted) return
        tidyPosted = true
        tidyHandler.post { tidyPosted = false; runCatching { tidyQueue() }.onFailure { CrashGuard.nonFatal(this, "tidyQueue", it) } }
    }

    /**
     * 1.0.2 smart queue, always on (no setting):
     * 1. Duplicates: the playing song wins, then the nearest upcoming copy; later copies of the same song are removed.
     *    "Same" = same id, or the same recording on another source (Taste.key: cleaned title + first artist).
     * 2. Played songs: kept above the playing song (1.0.7; they used to be removed). Only the oldest beyond
     *    [MAX_PLAYED_KEPT] are dropped, never while shuffle or Repeat all is on.
     * Removing other items never interrupts the playing song. Then Infinite play tops up (see [maybeAutoplay]).
     */
    private val MAX_PLAYED_KEPT = 150

    private fun tidyQueue() {
        val n = player.mediaItemCount
        val cur = player.currentMediaItemIndex
        if (n <= 1 || cur == C.INDEX_UNSET || cur >= n) return
        val seenIds = HashSet<String>(); val seenKeys = HashSet<String>()
        fun keyOf(i: Int): Pair<String, String?> {
            val mi = player.getMediaItemAt(i)
            return mi.mediaId to mi.track()?.takeIf { it.title.isNotBlank() }?.let { com.ravi.musicspot.logic.Taste.key(it) }
        }
        keyOf(cur).let { (id, k) -> seenIds += id; k?.let(seenKeys::add) }
        val dupes = ArrayList<Int>()
        // 1.0.7 (Raj): only upcoming copies are duplicates; played songs (above the playing one) are never removed.
        for (i in (cur + 1) until n) {
            val (id, k) = keyOf(i)
            val dup = !seenIds.add(id) or (k != null && !seenKeys.add(k))
            if (dup) dupes += i
        }
        dupes.sortedDescending().forEach { player.removeMediaItem(it) }
        if (dupes.isNotEmpty()) com.ravi.musicspot.core.PlaybackLog.add("Queue: removed ${dupes.size} duplicate song(s)")
        val now = player.currentMediaItemIndex
        // 1.0.7 (Raj): played songs stay in the queue, above the playing song (the queue scrolls so the playing song is
        // at the top and highlighted). Only a very long history is trimmed (oldest first, past MAX_PLAYED_KEPT), so
        // Infinite play can't grow the queue without limit on 2 GB phones.
        if (now > MAX_PLAYED_KEPT && !player.shuffleModeEnabled && player.repeatMode != Player.REPEAT_MODE_ALL)
            player.removeMediaItems(0, now - MAX_PLAYED_KEPT)
        maybeAutoplay()
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
        val next = upcoming().filter { t -> urlCache[t.id]?.let { urlUsable(it.first, it.second) } != true && t.id !in inflight &&
            !t.source.isLive && runCatching { Graph.cached.offline(t) }.getOrNull() == null }
        if (next.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = io.launch {
            for (t in next) {
                com.ravi.musicspot.core.PlaybackLog.add("Pre-resolving upcoming \"${t.title.take(30)}\"")
                val failures = Collections.synchronizedList(mutableListOf<String>())
                // 1.0.5: same check-then-act lock as resolveSpec (was an unlocked getOrPut: two resolves could start).
                val job = synchronized(inflight) { inflight.getOrPut(t.id) { io.async { resolver(t, failures) } } }
                val r = try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { inflight.remove(t.id); continue }
                inflight.remove(t.id)
                resolvedInfo[t.id] = Known(r.info, failures.toList(), r.fallbackFrom)
                urlCache[t.id] = r to System.currentTimeMillis()
                urlPrefetched += t.id
            }
        }
    }

    /**
     * 1.0.5: called on the player thread when playback is (re)started. After a long pause the current song's URL may
     * be past (or close to) its `expire` time; resolving now, while the buffer still plays, means the refresh path
     * (ChunkedDataSource → StreamUrlExpiredException / HTTP 403 → SeamlessRetryPolicy → resolveSpec) finds a fresh URL
     * in [urlCache] at once and continues at the exact byte offset; the queue and the position are never touched.
     */
    private fun refreshStaleUrlOnResume() {
        val t = player.currentMediaItem?.track() ?: return
        if (t.source.isLive || !Graph.online.value) return
        val (r, at) = urlCache[t.id] ?: return // never resolved / offline copy: the next load resolves anyway
        if (!urlNearExpiry(r, at)) return
        if (synchronized(inflight) { t.id in inflight }) return
        com.ravi.musicspot.core.PlaybackLog.add("Resuming after a pause: the stream URL is expiring, getting a fresh one ahead of time")
        io.launch {
            val failures = Collections.synchronizedList(mutableListOf<String>())
            val job = synchronized(inflight) { inflight.getOrPut(t.id) { io.async { resolver(t, failures) } } }
            val fresh = try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { inflight.remove(t.id); return@launch }
            inflight.remove(t.id)
            resolvedInfo[t.id] = Known(fresh.info, failures.toList(), fresh.fallbackFrom)
            urlCache[t.id] = fresh to System.currentTimeMillis()
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
        val snap = Graph.queueStore.load() ?: return // 1.0.5: suspending read on Dispatchers.IO
        if (player.mediaItemCount > 0) return // 1.0.5: something started playing while the file was read
        if (snap.tracks.isEmpty()) return
        player.setMediaItems(snap.tracks.map { it.toMediaItem() }, snap.index.coerceIn(0, snap.tracks.lastIndex), snap.positionMs)
        player.shuffleModeEnabled = snap.shuffle
        player.repeatMode = snap.repeat
        player.prepare()
    }

    /** Sleep timer + fade-out/fade-in crossfade + periodic queue position save. */
    /** 1.0.2 Playback › Pause at zero volume: pause when media volume hits 0, resume when it is turned back up. */
    private fun onVolumeChanged() {
        if (!::player.isInitialized || !Graph.settings.value.pauseOnMute) return
        val am = getSystemService(AudioManager::class.java) ?: return
        val vol = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(1)
        if (vol == 0 && player.isPlaying) { player.pause(); pausedByMute = true }
        else if (vol > 0 && pausedByMute) { pausedByMute = false; player.play() }
    }

    private fun loadSponsorSegments(t: Track?) {
        val s = Graph.settings.value
        if (t == null || !s.sponsorBlock || t.source != com.ravi.musicspot.model.SourceKind.YOUTUBE) { sbFor = null; return }
        SponsorBlock.cached(t.nativeId, s.sponsorCategories)?.let { sbFor = t.id to it; return }
        sbFor = null
        io.launch {
            val segs = runCatching { SponsorBlock.segments(t.nativeId, s.sponsorCategories) }.getOrNull() ?: return@launch
            if (segs.isNotEmpty()) com.ravi.musicspot.core.PlaybackLog.add("SponsorBlock: ${segs.size} segment(s) to skip")
            sbFor = t.id to segs
        }
    }

    /** 1.0.6: the player's volume is the ticker's gain times the play/pause ramp's gain. Main thread. */
    private fun applyVolume() {
        val v = (baseVolume * rampGain).coerceIn(0f, 1f)
        if (player.volume != v) player.volume = v
    }

    /**
     * 1.0.6 smooth play / pause: a [com.ravi.musicspot.logic.Fades.RAMP_MS] equal-power volume ramp, so audio never
     * clicks or cuts off mid-waveform. Pause fades out, then pauses; play starts at silence and fades in once audio
     * flows. A play during a pause ramp (or the other way round) reverses from the current gain. Every play/pause from
     * the session (app, notification, headset, Bluetooth, car), the widget and Listen together goes through here.
     * Main thread (the player's application looper).
     */
    internal val fader = object : SafePositionPlayer.Fader {
        override fun pause(doPause: () -> Unit) {
            rampJob?.cancel()
            if (!::player.isInitialized || !player.isPlaying || pure) { rampJob = null; doPause(); rampGain = 1f; return }
            val from = rampGain
            rampJob = scope.launch {
                val t0 = android.os.SystemClock.uptimeMillis()
                while (true) {
                    val e = android.os.SystemClock.uptimeMillis() - t0
                    if (e >= com.ravi.musicspot.logic.Fades.RAMP_MS) break
                    rampGain = com.ravi.musicspot.logic.Fades.rampDown(from, e); applyVolume()
                    delay(com.ravi.musicspot.logic.Fades.RAMP_STEP_MS)
                }
                rampGain = 0f; applyVolume()
                doPause()
                // Paused: the next play() ramps up from silence (the AudioTrack keeps its tail; it resumes quiet).
            }
        }

        override fun play(doPlay: () -> Unit) {
            rampJob?.cancel()
            if (!::player.isInitialized || pure) { rampJob = null; rampGain = 1f; doPlay(); return }
            if (!player.isPlaying && rampGain >= 1f) { rampGain = 0f; applyVolume() }
            doPlay()
            val from = rampGain
            if (from >= 1f) { rampJob = null; return }
            rampJob = scope.launch {
                // Ramp once audio flows (a resume can buffer first); give up waiting after a few seconds.
                kotlinx.coroutines.withTimeoutOrNull(8_000) { while (!player.isPlaying) delay(16) }
                val t0 = android.os.SystemClock.uptimeMillis()
                while (true) {
                    val e = android.os.SystemClock.uptimeMillis() - t0
                    if (e >= com.ravi.musicspot.logic.Fades.RAMP_MS) break
                    rampGain = com.ravi.musicspot.logic.Fades.rampUp(from, e); applyVolume()
                    delay(com.ravi.musicspot.logic.Fades.RAMP_STEP_MS)
                }
                rampGain = 1f; applyVolume()
            }
        }
    }

    /**
     * 1.0.6 zero-wait next track: called at 80 % of the song (or [PREBUFFER_BEFORE_END_MS] before its end). Resolves the
     * next song's stream URL (if it isn't fresh already) and writes its first [PREBUFFER_BYTES] into the media cache,
     * keyed exactly as the player will ask for them, so the switch starts from local bytes with no network wait.
     * Skipped offline, for live radio, for songs already downloaded/fully cached, and when the cache is unavailable
     * (the URL is still pre-resolved then).
     */
    private fun prebufferNext() {
        if (!Graph.online.value) return
        val next = upcoming().firstOrNull() ?: return
        if (next.source.isLive || runCatching { Graph.cached.offline(next) }.getOrNull() != null) return
        if (Graph.downloads.isDownloaded(next.id)) return
        val factory = cacheFactory
        prebufferJob?.cancel()
        prebufferJob = io.launch {
            val fresh = urlCache[next.id]?.takeIf { urlUsable(it.first, it.second) }?.first
            val r = fresh ?: run {
                com.ravi.musicspot.core.PlaybackLog.add("Pre-buffering next \"${next.title.take(30)}\" (resolving its stream)")
                val failures = Collections.synchronizedList(mutableListOf<String>())
                val job = synchronized(inflight) { inflight.getOrPut(next.id) { io.async { resolver(next, failures) } } }
                val got = try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { inflight.remove(next.id); return@launch }
                inflight.remove(next.id)
                resolvedInfo[next.id] = Known(got.info, failures.toList(), got.fallbackFrom)
                urlCache[next.id] = got to System.currentTimeMillis()
                urlPrefetched += next.id
                got
            }
            if (factory == null || !r.uri.startsWith("http")) return@launch
            val spec = specFor(DataSpec.Builder().setUri(TrackUri.of(next)).setPosition(0).setLength(PREBUFFER_BYTES).build(), r)
            val writer = androidx.media3.datasource.cache.CacheWriter(factory.createDataSource(), spec, null, null)
            val cancelHook = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { writer.cancel() }
            try {
                // Same first chunk the player reads: the next song's bytes are a local hit when it starts.
                runCatching { kotlinx.coroutines.runInterruptible { writer.cache() } }
                    .onSuccess { com.ravi.musicspot.core.PlaybackLog.add("Pre-buffered the start of \"${next.title.take(30)}\"") }
            } finally { cancelHook?.dispose() }
        }
    }

    /** 1.0.10: mirrors player.isPlaying for the ticker's idle wait. */
    private val tickerPlaying = MutableStateFlow(false)

    private suspend fun ticker() {
        var lastSaveAt = System.currentTimeMillis()
        var fine = false
        while (scope.isActive) {
            // 1.0.10: the 200 ms (40 ms while fading) cadence runs only while playing or while a sleep timer is set (its
            // countdown label and fade). Paused/idle: wait for playback to start or a sleep timer to be set, with a
            // 30 s heartbeat; the body then runs straight away, so nothing lags on resume.
            if (player.isPlaying || Playback.sleep.mode != SleepTimer.Mode.OFF) delay(if (fine) 40 else 200)
            else {
                val sleepAt = Playback.sleepTick.value
                kotlinx.coroutines.withTimeoutOrNull(IDLE_HEARTBEAT_MS) {
                    kotlinx.coroutines.flow.merge(tickerPlaying.filter { it }, Playback.sleepTick.filter { it != sleepAt }).first()
                }
                fine = false
            }
            val s = Graph.settings.value
            val now = System.currentTimeMillis()
            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            val pos = player.currentPosition
            val remaining = if (dur > 0) dur - pos else Long.MAX_VALUE
            // 1.0.2 SponsorBlock: jump over a marked segment.
            if (s.sponsorBlock && player.isPlaying) sbFor?.let { (id, segs) ->
                if (id == player.currentMediaItem?.mediaId) SponsorBlock.skipAt(segs, pos, dur)?.let { seg ->
                    player.seekTo(seg.endMs)
                    com.ravi.musicspot.core.PlaybackLog.add("SponsorBlock: skipped ${seg.category} ${seg.startMs / 1000}-${seg.endMs / 1000} s")
                    continue
                }
            }
            val sleep = Playback.sleep
            if (sleep.mode != SleepTimer.Mode.OFF) {
                Playback.sleepTick.value = now
                val last = player.currentMediaItemIndex >= player.mediaItemCount - 1
                if (player.isPlaying && sleep.shouldPause(now, remaining, last, s.sleepFinishSong)) { player.pause(); sleep.cancel(); baseVolume = 1f; applyVolume(); continue }
            }
            var vol = 1f
            if (s.sleepFade && sleep.mode == SleepTimer.Mode.DURATION) vol *= sleep.fadeFactor(now, 30_000)
            if (fadeInFrom == 0L && player.isPlaying) fadeInFrom = now
            if (fadeInFrom > 0L) { val f = (now - fadeInFrom).toFloat() / FADE_IN_MS; if (f >= 1f) fadeInFrom = -1L else vol *= f.coerceIn(0.05f, 1f) }
            val cf = if (s.gapless) 0L else s.crossfadeSec * 1000L
            // 1.0.6: equal-power curves (sin/cos) instead of linear ramps: the loudness no longer dips mid-fade.
            if (dur > 0) vol *= com.ravi.musicspot.logic.Fades.crossfadeGain(pos, remaining, dur, cf)
            fine = player.isPlaying && (fadeInFrom > 0L || (dur > 0 && com.ravi.musicspot.logic.Fades.inCrossfade(pos, remaining, dur, cf)))
            baseVolume = vol
            applyVolume()
            // 1.0.6 zero-wait next track: at 80 % (or 20 s before the end) resolve the next song and cache its start.
            val curId = player.currentMediaItem?.mediaId
            if (curId != null && dur > 0 && player.isPlaying && prebufferedFor != curId && (pos >= dur * 8 / 10 || remaining <= PREBUFFER_BEFORE_END_MS)) {
                prebufferedFor = curId
                prebufferNext()
            }
            // v2.0.5 play counting: after ~30 s (half of a short song); a consecutive replay counts as a repeat.
            val curItem = player.currentMediaItem
            if (curItem != null && player.isPlaying && countedFor != curItem.mediaId && pos >= com.ravi.musicspot.logic.Taste.countThresholdMs(dur)) {
                countedFor = curItem.mediaId
                curItem.track()?.let { t -> runCatching { Graph.playStats.count(t, repeatPending) } }
                lastCountedId = curItem.mediaId; repeatPending = false
            }
            // Queue position save every ~5 s (time-based since 1.0.6, as the tick length now varies).
            if (now - lastSaveAt >= 5_000) { lastSaveAt = now; if (player.isPlaying) saveQueue() }
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
        runCatching { com.ravi.musicspot.core.JsonFile.flushAll() } // 1.0.5: write the debounced queue/library now (on IO)
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
        runCatching { contentResolver.unregisterContentObserver(volumeObserver) }
        runCatching {
            com.ravi.musicspot.widget.NowPlayingWidget.Controls.handle = null
            val md = if (::player.isInitialized) player.currentMediaItem?.mediaMetadata else null // v2.0.14: guard lateinit
            com.ravi.musicspot.widget.NowPlayingWidget.update(this, com.ravi.musicspot.widget.NowPlayingWidget.State(md?.title?.toString(), md?.artist?.toString(), md?.artworkUri?.toString(), false))
        }
        if (::player.isInitialized) runCatching { saveQueue() } // v2.0.14: never let a save failure skip release
        runCatching { com.ravi.musicspot.core.JsonFile.flushAll() } // 1.0.5: don't lose the last debounced writes (runs on IO)
        runCatching { com.ravi.musicspot.together.ListenTogether.onServiceDestroyed() } // 1.0.5: close the relay stream + its coroutines
        if (::player.isInitialized) runCatching { tidyHandler.removeCallbacksAndMessages(null); playerHandler.removeCallbacksAndMessages(null) } // 1.0.5
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
        /** 1.0.5: a cached URL this close to its `expire` time is not handed to a new load. */
        internal const val URL_EXPIRY_MARGIN_MS = 60_000L
        /** 1.0.5: on resume, a URL this close to its `expire` time is refreshed ahead of time. */
        internal const val RESUME_REFRESH_MARGIN_MS = 5 * 60_000L

        /** 1.0.5: inside the TTL and not within [marginMs] of its own `expire` (googlevideo). */
        internal fun urlUsable(r: Resolved, resolvedAtMs: Long, nowMs: Long = System.currentTimeMillis(), marginMs: Long = URL_EXPIRY_MARGIN_MS): Boolean =
            nowMs - resolvedAtMs < URL_TTL_MS && !com.ravi.musicspot.yt.YtUrlExpiry.isExpired(r.uri, resolvedAtMs, nowMs, marginMs)

        /**
         * 1.0.5: should a resume refresh this URL? Yes when it carries a plausible `expire` within [marginMs]; for a URL
         * without one (other hosts), when it is older than the TTL.
         */
        internal fun urlNearExpiry(r: Resolved, resolvedAtMs: Long, nowMs: Long = System.currentTimeMillis(), marginMs: Long = RESUME_REFRESH_MARGIN_MS): Boolean {
            if (r.info.source == com.ravi.musicspot.model.SourceKind.RADIO) return false
            val exp = com.ravi.musicspot.yt.YtUrlExpiry.expiresAtMs(r.uri)
            val life = exp?.minus(resolvedAtMs)
            return if (exp != null && life != null && life > 0 && life <= com.ravi.musicspot.yt.YtUrlExpiry.MAX_LIFETIME_MS) nowMs >= exp - marginMs
            else nowMs - resolvedAtMs >= URL_TTL_MS && isYouTubeMedia(r.uri)
        }
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
        @Volatile internal var resolver: suspend (Track, MutableList<String>) -> Resolved = resolve@{ track, failures ->
            Graph.awaitReady()
            if (track.source == com.ravi.musicspot.model.SourceKind.RADIO) return@resolve com.ravi.musicspot.source.Radio.resolve(track)
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
        /** 1.0.2: Infinite play tops up whenever this many songs or fewer are left after the playing one… */
        internal const val AUTOPLAY_WHEN_LEFT = 10
        /** …to this many songs ahead. */
        internal const val AUTOPLAY_TARGET = 20
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
        internal const val MAX_ERROR_SKIPS = 5
        internal const val FADE_IN_MS = 600L
        /** 1.0.10: ticker heartbeat while paused/idle with no sleep timer (was a 200 ms loop at all times). */
        internal const val IDLE_HEARTBEAT_MS = 30_000L
        /** 1.0.6: pre-buffer the next song this long before the current one ends (or at 80 %, whichever is first). */
        internal const val PREBUFFER_BEFORE_END_MS = 20_000L
        /** 1.0.6: the first chunk of the next song written to the media cache ahead of time (~16-30 s of audio). */
        internal const val PREBUFFER_BYTES = 512L * 1024
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
internal class SafePositionPlayer(p: androidx.media3.common.Player, private val fader: Fader? = null) : androidx.media3.common.ForwardingPlayer(p) {
    /** 1.0.6: smooth volume ramps around play/pause (see PlaybackService.fader). */
    interface Fader {
        fun pause(doPause: () -> Unit)
        fun play(doPlay: () -> Unit)
    }

    override fun play() { val f = fader; if (f == null) super.play() else f.play { super.play() } }
    override fun pause() { val f = fader; if (f == null) super.pause() else f.pause { super.pause() } }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        val f = fader
        when {
            f == null -> super.setPlayWhenReady(playWhenReady)
            playWhenReady -> f.play { super.setPlayWhenReady(true) }
            else -> f.pause { super.setPlayWhenReady(false) }
        }
    }

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
