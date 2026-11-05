package com.ravi.musicspot.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ravi.musicspot.logic.SleepTimer
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI-side handle to the playback service: StateFlows for Compose plus every command the screens need. */
class PlayerConnection(private val ctx: Context) {
    data class State(
        val queue: List<Track> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_OFF,
        val durationMs: Long = 0,
        /** Index of the first item Infinite play appended (Up next shows an "Autoplay" divider there), or -1. */
        val autoplayFrom: Int = -1,
    ) {
        val current: Track? get() = queue.getOrNull(index)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private val pendingOps = mutableListOf<(MediaController) -> Unit>()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position
    private var connecting = false
    private var pollJob: kotlinx.coroutines.Job? = null // v2.0.14: single position-poll loop

    fun connect() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { main.post { connect() }; return }
        // v2.0.14: drop a controller whose session went away so we rebuild instead of holding a dead handle.
        controller?.let { if (!it.isConnected) { runCatching { it.release() }; controller = null } }
        if (controller != null || connecting) return
        connecting = true
        val f = try {
            MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java)))
                .setListener(object : MediaController.Listener { // v2.0.14: forget the controller on disconnect
                    override fun onDisconnected(controller: MediaController) {
                        if (this@PlayerConnection.controller === controller) { this@PlayerConnection.controller = null; pollJob?.cancel(); pollJob = null }
                    }
                })
                .buildAsync()
        } catch (e: Exception) {
            connecting = false
            com.ravi.musicspot.core.CrashGuard.nonFatal(ctx, "MediaController.build", e)
            return
        }
        f.addListener({
            connecting = false
            val c = runCatching { f.get() }.onFailure { com.ravi.musicspot.core.CrashGuard.nonFatal(ctx, "MediaController.connect", it) }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) = publish() })
            publish()
            pendingOps.toList().forEach { it(c) }; pendingOps.clear()
            pollJob?.cancel() // v2.0.14: one poll loop only
            pollJob = scope.launch { while (isActive) { controller?.let { _position.value = it.currentPosition.coerceAtLeast(0) }; delay(250) } }
        }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
    }

    private fun publish() {
        val c = controller ?: return
        val q = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).track() }
        _state.value = State(q, c.currentMediaItemIndex.takeIf { q.isNotEmpty() } ?: -1, c.isPlaying, c.playbackState == Player.STATE_BUFFERING,
            c.shuffleModeEnabled, c.repeatMode, c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (q.getOrNull(c.currentMediaItemIndex)?.durationMs ?: 0),
            (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).isAutoplay } ?: -1)
    }

    /**
     * Every MediaController call happens on the main thread (the controller's application looper). A call from any
     * other thread (a coroutine on Dispatchers.IO/Default) is posted to the main thread instead of touching it there.
     */
    private fun run(op: (MediaController) -> Unit) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { main.post { run(op) }; return }
        // v2.0.2: was `controller?.let(op) ?: run { … }`, where `run { }` resolved to THIS member (the lambda fits
        // (MediaController) -> Unit), so any command issued before the controller connected recursed until
        // StackOverflowError. Now an explicit branch.
        var c = controller
        if (c != null && !c.isConnected) { runCatching { c.release() }; controller = null; c = null } // v2.0.14: stale controller -> reconnect
        if (c != null) op(c) else { pendingOps += op; connect() }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun play(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false) = run { c ->
        if (tracks.isEmpty()) return@run
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(tracks.map { it.toMediaItem() }, if (shuffle) (tracks.indices).random() else start.coerceIn(0, tracks.lastIndex), 0)
        c.prepare(); c.play()
    }
    fun playNext(t: Track) = run { c -> if (c.mediaItemCount == 0) play(listOf(t)) else c.addMediaItem((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), t.toMediaItem()) }
    fun addToQueue(tracks: List<Track>) = run { c -> if (c.mediaItemCount == 0) play(tracks) else c.addMediaItems(tracks.map(Track::toMediaItem)) }
    fun toggle() = run { c -> if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() } }
    fun next() = run { it.seekToNext() }
    fun previous() = run { it.seekToPrevious() }
    fun seek(ms: Long) = run { it.seekTo(ms); _position.value = ms }
    fun skipTo(i: Int) = run { if (i !in 0 until it.mediaItemCount) return@run; it.seekTo(i, 0); it.play() } // v2.0.14: bounds
    fun move(from: Int, to: Int) = run { val n = it.mediaItemCount; if (from !in 0 until n || to !in 0 until n) return@run; it.moveMediaItem(from, to) } // v2.0.14: bounds
    fun remove(i: Int) = run { if (i !in 0 until it.mediaItemCount) return@run; it.removeMediaItem(i) } // v2.0.14: bounds
    fun clearUpcoming() = run { c -> val n = c.mediaItemCount; val cur = c.currentMediaItemIndex; if (cur + 1 < n) c.removeMediaItems(cur + 1, n) }
    fun setShuffle(on: Boolean) = run { it.shuffleModeEnabled = on }
    fun cycleRepeat() = run { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }
    fun retry() = run { c -> Playback.error.value = null; c.prepare(); c.play() }
    fun stop() = run { c -> c.stop(); c.clearMediaItems() }

    // ---- sleep timer ------------------------------------------------------------------------------------------
    fun sleepFor(minutes: Int) { Playback.sleep.startDuration(minutes * 60_000L, System.currentTimeMillis()); Playback.sleepTick.value = System.currentTimeMillis() }
    fun sleepEndOfTrack() { Playback.sleep.startEndOfTrack(); Playback.sleepTick.value = System.currentTimeMillis() }
    fun sleepEndOfQueue() { Playback.sleep.startEndOfQueue(); Playback.sleepTick.value = System.currentTimeMillis() }
    fun sleepAdjust(minutes: Int) { Playback.sleep.adjust(minutes * 60_000L, System.currentTimeMillis()); Playback.sleepTick.value = System.currentTimeMillis() }
    fun sleepCancel() { Playback.sleep.cancel(); Playback.sleepTick.value = System.currentTimeMillis(); run { it.volume = 1f } }
    val sleepActive: Boolean get() = Playback.sleep.mode != SleepTimer.Mode.OFF

    @Suppress("unused") private fun items(c: MediaController): List<MediaItem> = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
}
