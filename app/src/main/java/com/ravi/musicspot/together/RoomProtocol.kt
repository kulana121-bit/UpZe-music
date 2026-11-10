package com.ravi.musicspot.together

import com.ravi.musicspot.model.Track
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.random.Random

/** 1.0.2 Listen together: the messages a room exchanges (pure, unit-tested). */
@Serializable
data class RoomMsg(
    /** "state" (host → all), "hello" / "bye" (listener → host), "add" (listener suggests a song), "end" (host closed the room). */
    val t: String,
    /** Random id of the sending phone for this session; a phone ignores its own messages. */
    val from: String,
    val name: String = "",
    val track: Track? = null,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    /** Sender's wall clock when [positionMs] was read. */
    val atMs: Long = 0,
    val listeners: Int = 0,
    /** Increases with every state the host sends, so a late/out-of-order one is ignored. */
    val seq: Long = 0,
)

object RoomProtocol {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no 0/O, 1/I/L
    const val CODE_LEN = 6

    fun newCode(rnd: Random = Random.Default) = (1..CODE_LEN).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")

    /** What the user typed → a room code, or null ("abc 234" → "ABC234"). Look-alikes are not in the alphabet, so a typed 0/O/1/I/L is rejected. */
    fun normalize(input: String): String? {
        val code = input.uppercase().filter { it.isLetterOrDigit() }
        return code.takeIf { it.length == CODE_LEN && it.all { ch -> ch in ALPHABET } }
    }

    /** The relay topic for a code: hashed and salted so a code never appears on the relay and can't clash with other apps. */
    fun topic(code: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("upze-listen-together:$code".toByteArray())
        return "upze_" + d.take(12).joinToString("") { "%02x".format(it) }
    }

    /** Where the listener should be now, given the host's last state. */
    fun expectedPosition(m: RoomMsg, nowMs: Long): Long =
        if (!m.playing) m.positionMs else (m.positionMs + (nowMs - m.atMs).coerceIn(0, 6 * 60 * 60 * 1000L))

    /** Seek only when the listener drifted more than this (seeking for every few ms of jitter stutters). */
    const val DRIFT_MS = 2_500L

    // 1.0.6 heartbeat + reconnect (ListenTogether.listen).

    /** How often the relay stream's liveness is checked. */
    const val HEARTBEAT_MS = 15_000L
    /**
     * A stream with no event for this long is dead. ntfy sends a keepalive event every 45 s by default, so this is one
     * keepalive plus margin; a live but idle room is never torn down.
     */
    const val STALE_AFTER_MS = 60_000L
    const val BACKOFF_START_MS = 2_000L
    const val BACKOFF_MAX_MS = 30_000L

    fun isStale(lastEventAtMs: Long, nowMs: Long): Boolean = nowMs - lastEventAtMs > STALE_AFTER_MS

    /** Doubles the reconnect wait, capped at [BACKOFF_MAX_MS]. */
    fun nextBackoff(currentMs: Long): Long = (currentMs * 2).coerceIn(BACKOFF_START_MS, BACKOFF_MAX_MS)

    /** [baseMs] plus up to 25 % jitter ([random01] in 0..1), so phones that lost the relay together don't retry in lockstep. */
    fun backoffWithJitter(baseMs: Long, random01: Double): Long = baseMs + (baseMs * 0.25 * random01.coerceIn(0.0, 1.0)).toLong()
}
