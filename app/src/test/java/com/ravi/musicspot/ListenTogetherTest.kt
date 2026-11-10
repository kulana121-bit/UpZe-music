package com.ravi.musicspot

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.Radio
import com.ravi.musicspot.together.RoomMsg
import com.ravi.musicspot.together.RoomProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ListenTogetherTest {
    @Test fun codes() {
        val c = RoomProtocol.newCode(Random(7))
        assertEquals(6, c.length)
        assertEquals(c, RoomProtocol.normalize(c.lowercase().chunked(3).joinToString(" ")))
        assertNull(RoomProtocol.normalize("ABC12"))     // too short
        assertNull(RoomProtocol.normalize("ABCDE0"))    // 0 is not in the alphabet
        assertTrue(RoomProtocol.topic(c).startsWith("upze_"))
        assertNotEquals(RoomProtocol.topic("ABCDEF"), RoomProtocol.topic("ABCDEG"))
        assertTrue(!RoomProtocol.topic(c).contains(c))
    }

    @Test fun positionFollowsHostClock() {
        val m = RoomMsg("state", "h", positionMs = 10_000, playing = true, atMs = 1_000)
        assertEquals(14_000, RoomProtocol.expectedPosition(m, 5_000))
        assertEquals(10_000, RoomProtocol.expectedPosition(m.copy(playing = false), 50_000))
        assertEquals(10_000, RoomProtocol.expectedPosition(m, 500)) // clock behind: never go backwards past the sent position
    }

    @Test fun messageRoundTripsAndStaysSmall() {
        val t = Track("yt:abc", "Kesariya", "Arijit Singh", durationMs = 268_000, artUrl = "https://i.ytimg.com/vi/abc/hq.jpg")
        val m = RoomMsg("state", "p1", "Pixel", track = t, positionMs = 5, playing = true, atMs = 9, listeners = 3, seq = 4)
        val s = Http.json.encodeToString(RoomMsg.serializer(), m)
        assertEquals(m, Http.json.decodeFromString(RoomMsg.serializer(), s))
        assertTrue(s.length < 4096)
    }

    @Test fun stationBecomesLiveTrack() {
        val st = Radio.Station(stationuuid = "u1", name = " Radio Mirchi ", url_resolved = "https://x/stream", state = "Delhi", country = "India", tags = "bollywood,hindi", codec = "MP3", bitrate = 128)
        val t = st.toTrack()
        assertEquals("rb:u1", t.id)
        assertTrue(t.source.isLive)
        assertEquals("Radio Mirchi", t.title)
        assertEquals("Delhi, India · bollywood", t.artist)
    }
}

/** 1.0.6 Listen together heartbeat and reconnect backoff. */
class ListenTogetherHeartbeatTest {
    @Test fun staleAfterAMissedKeepalive() {
        assertTrue(!RoomProtocol.isStale(0, RoomProtocol.STALE_AFTER_MS))
        assertTrue(RoomProtocol.isStale(0, RoomProtocol.STALE_AFTER_MS + 1))
        assertTrue(RoomProtocol.HEARTBEAT_MS == 15_000L)
    }

    @Test fun backoffDoublesCapsAndJitters() {
        assertEquals(4_000L, RoomProtocol.nextBackoff(2_000))
        assertEquals(RoomProtocol.BACKOFF_MAX_MS, RoomProtocol.nextBackoff(20_000))
        assertEquals(RoomProtocol.BACKOFF_MAX_MS, RoomProtocol.nextBackoff(RoomProtocol.BACKOFF_MAX_MS))
        assertEquals(2_000L, RoomProtocol.backoffWithJitter(2_000, 0.0))
        assertEquals(2_500L, RoomProtocol.backoffWithJitter(2_000, 1.0))
    }
}
