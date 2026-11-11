package com.ravi.musicspot.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /**
     * 1.0.10: playback speed (0.5x–2x) and whether the voice keeps its natural pitch. Held here for the whole session,
     * so it survives track changes and queue replacement, and is re-applied (in [publish]) if the player or the
     * controller is rebuilt. keepPitch = pitch 1.0 (Media3's Sonic time-stretch); otherwise pitch follows the speed.
     */
    data class Speed(val speed: Float = 1f, val keepPitch: Boolean = true) {
        val pitch: Float get() = if (keepPitch) 1f else speed
    }
    private val _speed = MutableStateFlow(Speed())
    val speed: StateFlow<Speed> = _speed

    fun setSpeed(speed: Float, keepPitch: Boolean = _speed.value.keepPitch) {
        _speed.value = Speed(clampSpeed(speed), keepPitch)
        run { c -> applySpeed(c) }
    }

    private fun applySpeed(c: MediaController) {
        val s = _speed.value
        // Pack 6 Part 2: DJ Mode's tempo match multiplies the user's speed; the user's pitch choice is kept as is.
        val (speed, pitch) = com.ravi.musicspot.logic.DjMode.parameters(s.speed, s.keepPitch, Playback.djStretch.value)
        val want = PlaybackParameters(speed, pitch)
        if (c.playbackParameters != want && c.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) c.playbackParameters = want
    }
    /** 1.0.10 test hook (UI smoke tests only): publishes a player state without a MediaController. */
    internal fun publishForTest(s: State) { _state.value = s }
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
            // 1.0.5: run each queued op guarded, and clear the queue first so an op that throws can't replay the rest twice.
            val ops = pendingOps.toList(); pendingOps.clear()
            ops.forEach { op -> runCatching { op(c) }.onFailure { com.ravi.musicspot.core.CrashGuard.nonFatal(ctx, "MediaController.pendingOp", it) } }
            pollJob?.cancel() // v2.0.14: one poll loop only
            // 1.0.10: the 250 ms poll runs only while (playing) OR (a position consumer is on screen: something collects
            // [position] and the app is in the foreground). Otherwise it stops; each (re)open samples the position at
            // once, and publish() also refreshes it on every player event (seek, pause, track change), so it's never stale.
            pollJob = scope.launch {
                kotlinx.coroutines.flow.combine(
                    _state.map { it.playing },
                    _position.subscriptionCount.map { it > 0 },
                    androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) },
                ) { playing, watched, foreground -> playing || (watched && foreground) }
                    .distinctUntilChanged()
                    .collectLatest { on ->
                        samplePosition()
                        if (on) while (isActive) { delay(250); samplePosition() }
                    }
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
    }

    private fun samplePosition() { controller?.let { _position.value = it.currentPosition.coerceAtLeast(0) } }

    private fun publish() {
        val c = controller ?: return
        _position.value = c.currentPosition.coerceAtLeast(0) // 1.0.10: fresh on every event, even with the poll stopped
        val q = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).track() }
        _state.value = State(q, c.currentMediaItemIndex.takeIf { q.isNotEmpty() } ?: -1, c.isPlaying, c.playbackState == Player.STATE_BUFFERING,
            c.shuffleModeEnabled, c.repeatMode, c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (q.getOrNull(c.currentMediaItemIndex)?.durationMs ?: 0),
            (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).isAutoplay } ?: -1)
        // 1.0.10: keep the session's speed if the player was rebuilt (no-op when it already matches).
        // Pack 6 Part 2: not while a DJ tempo match is easing (the service owns the speed then; re-applying a lagging
        // controller value would fight the ramp).
        if (Playback.djStretch.value == 1.0) applySpeed(c)
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
        // 1.0.5: the controller's own application looper is the authority (it is the main looper today because
        // connect() builds it there, but an op must never run on another thread even if that changes).
        if (c != null && c.applicationLooper != android.os.Looper.myLooper()) { android.os.Handler(c.applicationLooper).post { run(op) }; return }
        if (c != null) op(c) else {
            // 1.0.5: bounded: a controller that keeps failing to connect no longer piles up commands without limit.
            if (pendingOps.size >= MAX_PENDING) pendingOps.removeAt(0)
            pendingOps += op; connect()
        }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun play(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false) = run { c ->
        if (tracks.isEmpty()) return@run
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(tracks.map { it.toMediaItem() }, if (shuffle) (tracks.indices).random() else start.coerceIn(0, tracks.lastIndex), 0)
        c.prepare(); c.play()
    }
    fun playNext(t: Track) = run { c -> if (c.mediaItemCount == 0) play(listOf(t)) else c.addMediaItem((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), t.toMediaItem()) }
    /** 1.0.2: a whole list right after the playing song (playlist ⋮ › Play next). */
    fun playNextAll(tracks: List<Track>) = run { c ->
        if (tracks.isEmpty()) return@run
        if (c.mediaItemCount == 0) play(tracks) else c.addMediaItems((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), tracks.map(Track::toMediaItem))
    }
    /** 1.0.2 Listen together: replace the queue and start [tracks][start] at [positionMs]. */
    fun playAt(tracks: List<Track>, start: Int, positionMs: Long, playing: Boolean) = run { c ->
        if (tracks.isEmpty()) return@run
        c.shuffleModeEnabled = false
        c.setMediaItems(tracks.map { it.toMediaItem() }, start.coerceIn(0, tracks.lastIndex), positionMs.coerceAtLeast(0))
        c.prepare(); c.playWhenReady = playing
        _position.value = positionMs
    }
    /** 1.0.2: play or pause explicitly (toggle() flips whatever the state is). */
    fun setPlaying(on: Boolean) = run { c -> if (on) { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() } else c.pause() }
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
    // ---- Pack 6 Part 1: multiple queues ----

    /** Every saved queue and which one is active. */
    val queues: StateFlow<com.ravi.musicspot.data.QueueSet> get() = com.ravi.musicspot.Graph.queueStore.state

    /** Saves the current queue, then loads [id] into the player (PlaybackService.switchQueue, on the main thread). */
    fun switchQueue(id: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { main.post { switchQueue(id) }; return }
        val svc = PlaybackService.current
        if (svc != null) runCatching { svc.switchQueue(id) }.onFailure { com.ravi.musicspot.core.CrashGuard.nonFatal(ctx, "switchQueue", it) }
        else com.ravi.musicspot.Graph.queueStore.setActive(id) // no player yet: it restores the active queue when it starts
    }

    /** A new empty queue (not switched to). Null when 10 queues exist. */
    fun newQueue(): String? = com.ravi.musicspot.Graph.queueStore.create()

    /** A copy of the current queue (songs, position, shuffle, repeat) as a new queue, not switched to. Null at the cap. */
    fun copyCurrentQueue(): String? {
        val s = _state.value
        val store = com.ravi.musicspot.Graph.queueStore
        val name = store.state.value.activeQueue?.name?.let { "Copy of $it" }
        return store.create(name, com.ravi.musicspot.data.QueueSnapshot(s.queue.take(500), s.index.coerceAtLeast(0), _position.value, s.shuffle, s.repeat))
    }

    fun renameQueue(id: String, name: String) = com.ravi.musicspot.Graph.queueStore.rename(id, name)

    /** Never the last queue; the active one switches to the next (else previous) queue first. */
    fun deleteQueue(id: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { main.post { deleteQueue(id) }; return }
        val store = com.ravi.musicspot.Graph.queueStore
        val svc = PlaybackService.current
        if (svc != null) { runCatching { svc.deleteQueue(id) }.onFailure { com.ravi.musicspot.core.CrashGuard.nonFatal(ctx, "deleteQueue", it) }; return }
        val set = store.state.value
        if (set.active == id) com.ravi.musicspot.data.QueueSets.fallbackFor(set, id)?.let { store.setActive(it) }
        store.delete(id)
    }

    fun sleepEndOfQueue() { Playback.sleep.startEndOfQueue(); Playback.sleepTick.value = System.currentTimeMillis() }
    fun sleepAdjust(minutes: Int) { Playback.sleep.adjust(minutes * 60_000L, System.currentTimeMillis()); Playback.sleepTick.value = System.currentTimeMillis() }
    fun sleepCancel() { Playback.sleep.cancel(); Playback.sleepTick.value = System.currentTimeMillis(); run { it.volume = 1f } }
    val sleepActive: Boolean get() = Playback.sleep.mode != SleepTimer.Mode.OFF

    private companion object { const val MAX_PENDING = 32 }

    @Suppress("unused") private fun items(c: MediaController): List<MediaItem> = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
}

/** 1.0.10: speed range of the player's speed picker. */
const val MIN_SPEED = 0.5f
const val MAX_SPEED = 2f
fun clampSpeed(s: Float): Float = if (s.isNaN()) 1f else s.coerceIn(MIN_SPEED, MAX_SPEED)
/** "1x", "1.25x", "0.5x". */
fun speedLabel(s: Float): String = "%.2f".format(java.util.Locale.ROOT, s).trimEnd('0').trimEnd('.') + "x"
