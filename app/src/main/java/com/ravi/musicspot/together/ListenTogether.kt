package com.ravi.musicspot.together

import android.os.Build
import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.ui.Pills
import com.ravi.musicspot.core.Http.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * 1.0.2 Listen together. One phone hosts a room with a 6-character code; any number of friends join with the code and
 * hear what the host plays, in sync (song, play/pause, seeks). Listeners can suggest songs, which join the host's queue.
 *
 * Phones talk through a public ntfy relay (no account, nothing to install). Only small "now playing" messages travel;
 * each phone streams the audio itself from its own sources. Anyone who knows the code can join, so share it only with
 * the people you want. Messages are sent only on changes (song, play/pause, seek), which stays well inside the relay's
 * free limits.
 */
object ListenTogether {
    enum class Role { HOST, LISTENER }
    data class Room(
        val code: String,
        val role: Role,
        val connected: Boolean = false,
        val listeners: Int = 0,
        val hostName: String = "",
        val nowPlaying: Track? = null,
        val note: String? = null,
    )

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room
    val isListener: Boolean get() = _room.value?.role == Role.LISTENER

    private val me = "p" + Random.nextLong().toULong().toString(36)
    private val myName = (Build.MODEL ?: "Phone").take(24)
    /**
     * 1.0.5: one scope per room (main thread: every Graph.player call and all room bookkeeping happen there). Leaving,
     * the host ending the room and the playback service going away cancel it, which also closes the relay stream.
     * Was a list of jobs in the process-wide IoScope, where the blocking stream read ignored cancellation (the
     * `IoScope.isActive` loop check was always true) and a call opened just after leave() was never closed.
     */
    private var roomScope: CoroutineScope? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else main.post(block)
    }
    private fun newRoomScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { roomScope = it }
    private var seq = 0L
    private var lastApplied: RoomMsg? = null
    private val members = mutableMapOf<String, String>()

    @Volatile private var openCall: okhttp3.Call? = null
    private val stream by lazy { Http.client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
    private fun server() = Graph.settings.value.togetherServer.trimEnd('/').ifBlank { "https://ntfy.sh" }

    /** Main thread (UI). */
    fun host(): String {
        leaveNow(silent = true)
        val code = RoomProtocol.newCode()
        _room.value = Room(code, Role.HOST, hostName = myName)
        members.clear(); seq = 0; lastSent = null; lastSentAt = 0L
        val sc = newRoomScope()
        sc.launch { listen(code) }
        sc.launch { watchHost(code) }
        return code
    }

    /** Join with a typed code; false when the code isn't valid. */
    fun join(input: String): Boolean {
        val code = RoomProtocol.normalize(input) ?: return false
        leaveNow(silent = true)
        _room.value = Room(code, Role.LISTENER, note = "Waiting for the host…")
        lastApplied = null
        val sc = newRoomScope()
        sc.launch { listen(code) }
        sc.launch {
            publish(code, RoomMsg("hello", me, myName))
            delay(12_000)
            if (_room.value?.code == code && lastApplied == null) update(code) { it.copy(note = "No host answered yet. Check the code, or ask the host to open the room.") }
        }
        sc.launch { keepInSync(code) }
        return true
    }

    fun leave(silent: Boolean = false) {
        // 1.0.5: room bookkeeping is main-thread only; a call from another thread is posted there.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { onMain { leaveNow(silent) }; return }
        leaveNow(silent)
    }

    private fun leaveNow(silent: Boolean) {
        val r = _room.value
        if (r != null) {
            val code = r.code
            // The goodbye outlives the room's scope on purpose (fire and forget on the process scope).
            IoScope.launch { publish(code, RoomMsg(if (r.role == Role.HOST) "end" else "bye", me, myName)) }
        }
        closeRoom()
        if (r != null && !silent) Pills.show(if (r.role == Role.HOST) "Room closed" else "Left the room")
    }

    /** 1.0.5: cancels the room's coroutines and closes the relay stream; leaves no listener/collector behind. */
    private fun closeRoom() {
        roomScope?.cancel(); roomScope = null
        openCall?.cancel(); openCall = null
        _room.value = null
        members.clear(); lastApplied = null; lastSent = null
    }

    /** 1.0.5: the playback service is going away (app swiped from Recents): leave the room quietly. */
    fun onServiceDestroyed() = leave(silent = true)

    /** Listener: send a song to the host's queue. */
    fun suggest(t: Track) {
        val r = _room.value ?: return
        IoScope.launch { if (publish(r.code, RoomMsg("add", me, myName, track = t.forRoom()))) Pills.show("Sent to the host") }
    }

    // ---- relay ------------------------------------------------------------------------------------------------

    private suspend fun publish(code: String, m: RoomMsg): Boolean = withContext(Dispatchers.IO) {
        val body = Http.json.encodeToString(RoomMsg.serializer(), m)
        val req = Request.Builder().url("${server()}/${RoomProtocol.topic(code)}").post(body.toRequestBody("text/plain".toMediaType())).build()
        runCatching { Http.client.newCall(req).execute().use { r ->
            if (r.code == 429) update(code) { it.copy(note = "The relay is busy; syncing slows down for a minute.") }
            r.isSuccessful
        } }.getOrDefault(false)
    }

    /**
     * The relay stream for [code], kept alive for as long as the room exists.
     *
     * 1.0.6 heartbeat: every [RoomProtocol.HEARTBEAT_MS] (15 s) a watchdog checks when the stream last delivered
     * anything (a message, ntfy's `open` event, or its keepalive events). A stream that went quiet for longer than
     * [RoomProtocol.STALE_AFTER_MS] is a dead socket (Wi-Fi ↔ mobile handover, NAT timeout) that would otherwise block
     * forever: it is closed and reopened. Liveness rides on the relay's own keepalives, so no extra messages are
     * published (the free relay limits messages per day; a 15 s ping per phone would exhaust it).
     *
     * 1.0.6 reconnect: exponential backoff (2 s → 30 s) with jitter, and the stream resumes from the last message id
     * it saw (`since=<id>`), so a state change sent while reconnecting is not lost.
     */
    private suspend fun listen(code: String) {
        var backoff = RoomProtocol.BACKOFF_START_MS
        var lastId: String? = null
        // 1.0.5: was `IoScope.isActive` (the process scope: always true); now this room's coroutine.
        while (currentCoroutineContext().isActive && _room.value?.code == code) {
            try {
                val since = lastId ?: (System.currentTimeMillis() / 1000).toString()
                val req = Request.Builder().url("${server()}/${RoomProtocol.topic(code)}/json?since=$since").build()
                val call = stream.newCall(req)
                openCall = call
                // 1.0.5: cancellable connect (cancelling the coroutine cancels the call) …
                call.await().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("Relay answered ${r.code}")
                    update(code) { it.copy(connected = true) }; backoff = RoomProtocol.BACKOFF_START_MS
                    val lastEventAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
                    coroutineScope {
                        // … and a cancellable read: the blocking readUtf8Line() can't see cancellation, so a watcher
                        // cancels the call when the room's scope is cancelled, which unblocks the read at once.
                        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
                        // 1.0.6 heartbeat watchdog (see above).
                        val heartbeat = launch {
                            while (isActive) {
                                delay(RoomProtocol.HEARTBEAT_MS)
                                if (RoomProtocol.isStale(lastEventAt.get(), System.currentTimeMillis())) {
                                    update(code) { it.copy(connected = false) }
                                    call.cancel(); break
                                }
                            }
                        }
                        try {
                            withContext(Dispatchers.IO) {
                                val src = r.body!!.source()
                                while (isActive && _room.value?.code == code) {
                                    val line = src.readUtf8Line() ?: break
                                    lastEventAt.set(System.currentTimeMillis())
                                    val o = runCatching { Http.json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                                    if (o["event"]?.jsonPrimitive?.content != "message") continue
                                    o["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { lastId = it }
                                    val text = o["message"]?.jsonPrimitive?.content ?: continue
                                    val m = runCatching { Http.json.decodeFromString(RoomMsg.serializer(), text) }.getOrNull() ?: continue
                                    // Main thread: onMessage drives Graph.player and the room bookkeeping.
                                    if (m.from != me) withContext(Dispatchers.Main.immediate) { onMessage(code, m) }
                                }
                            }
                        } finally { heartbeat.cancel(); watcher.cancel() }
                    }
                }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e // the room was left; a watchdog cancel just reconnects
            } catch (_: Exception) { }
            if (_room.value?.code != code) return
            update(code) { it.copy(connected = false) }
            delay(RoomProtocol.backoffWithJitter(backoff, Random.nextDouble())); backoff = RoomProtocol.nextBackoff(backoff)
        }
    }

    private fun onMessage(code: String, m: RoomMsg) {
        val r = _room.value ?: return
        if (r.code != code) return // 1.0.5: a message from a room we already left
        when (r.role) {
            Role.HOST -> when (m.t) {
                "hello" -> { members[m.from] = m.name; update(code) { it.copy(listeners = members.size) }; Pills.show("${m.name.ifBlank { "Someone" }} joined"); sendState(code, force = true) }
                "bye" -> { members.remove(m.from); update(code) { it.copy(listeners = members.size) } }
                "add" -> m.track?.let { t -> Graph.player.addToQueue(listOf(t)); Pills.show("${m.name.ifBlank { "A listener" }} added ${t.title}") }
            }
            Role.LISTENER -> when (m.t) {
                "state" -> if (m.seq >= (lastApplied?.seq ?: -1) || m.seq < 3) apply(m)
                "end" -> { closeRoom(); Pills.show("The host closed the room") } // 1.0.5: same clean-up as leave()
            }
        }
    }

    // ---- host ---------------------------------------------------------------------------------------------------

    private var lastSent: RoomMsg? = null
    private var lastSentAt = 0L

    private suspend fun watchHost(code: String) {
        // Song or play/pause changed → send at once.
        // 1.0.5: a child of this room's coroutine (was a separate IoScope job that could outlive the room).
        coroutineScope {
        val changes = launch {
            Graph.player.state.map { it.current?.id to it.playing }.distinctUntilChanged().collect { sendState(code) }
        }
        try {
            // Seeks: the position jumped away from where the last message said it would be.
            while (_room.value?.code == code) {
                delay(1_000)
                val sent = lastSent ?: continue
                val st = Graph.player.state.value
                if (st.current?.id != sent.track?.id || st.current?.source?.isLive == true) continue
                val drift = abs(Graph.player.position.value - RoomProtocol.expectedPosition(sent, System.currentTimeMillis()))
                if (drift > 3_000) sendState(code)
            }
        } finally { changes.cancel() }
        }
    }

    private fun sendState(code: String, force: Boolean = false) {
        if (_room.value?.code != code) return
        val st = Graph.player.state.value
        val m = RoomMsg("state", me, myName, track = st.current?.forRoom(), positionMs = Graph.player.position.value, playing = st.playing,
            atMs = System.currentTimeMillis(), listeners = members.size, seq = ++seq)
        val now = System.currentTimeMillis()
        if (!force && lastSent?.let { it.track?.id == m.track?.id && it.playing == m.playing } == true && now - lastSentAt < 1_500) return
        lastSent = m; lastSentAt = now
        update(code) { it.copy(nowPlaying = st.current) }
        (roomScope ?: return).launch { publish(code, m) } // 1.0.5: cancelled with the room
    }

    // ---- listener -----------------------------------------------------------------------------------------------

    private fun apply(m: RoomMsg) {
        lastApplied = m
        val t = m.track
        update { it.copy(hostName = m.name, listeners = m.listeners, nowPlaying = t, note = null) }
        if (t == null) { Graph.player.setPlaying(false); return }
        if (t.source == SourceKind.LOCAL || t.source == SourceKind.SUBSONIC || t.source == SourceKind.JELLYFIN) {
            update { it.copy(note = "The host is playing a song from their own phone or server, which can't stream to you.") }
            return
        }
        val st = Graph.player.state.value
        val target = if (t.source.isLive) 0L else RoomProtocol.expectedPosition(m, System.currentTimeMillis())
        if (st.current?.id != t.id) Graph.player.playAt(listOf(t), 0, target, m.playing)
        else {
            if (!t.source.isLive && abs(Graph.player.position.value - target) > RoomProtocol.DRIFT_MS) Graph.player.seek(target)
            if (st.playing != m.playing) Graph.player.setPlaying(m.playing)
        }
    }

    /** Between messages, stay on the host's clock (no network needed). */
    private suspend fun keepInSync(code: String) {
        while (_room.value?.code == code) {
            delay(5_000)
            val m = lastApplied ?: continue
            val t = m.track ?: continue
            val st = Graph.player.state.value
            if (st.current?.id != t.id || t.source.isLive || !m.playing || st.buffering) continue
            val target = RoomProtocol.expectedPosition(m, System.currentTimeMillis())
            if (t.durationMs > 0 && target > t.durationMs) continue // the host's next song is on its way
            if (abs(Graph.player.position.value - target) > RoomProtocol.DRIFT_MS) Graph.player.seek(target)
        }
    }

    /**
     * 1.0.5: atomic (MutableStateFlow.update) and scoped to [code]: a late answer from a room we already left (the
     * relay's 429 note, a reconnect) can no longer write into the next room. Was a read-then-write of `.value`.
     */
    private fun update(code: String? = null, f: (Room) -> Room) {
        _room.update { r -> if (r == null || (code != null && r.code != code)) r else f(r) }
    }
    /** Keep the message small (the relay caps one at 4 KB). */
    private fun Track.forRoom() = copy(album = album?.take(80), title = title.take(160), artist = artist.take(120))
}
