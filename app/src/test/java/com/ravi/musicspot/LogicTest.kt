package com.ravi.musicspot

import com.ravi.musicspot.logic.QueueOps
import com.ravi.musicspot.logic.RepeatMode
import com.ravi.musicspot.logic.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueOpsTest {
    private val q = listOf("a", "b", "c", "d", "e")
    private val id = q.indices.toList()

    @Test fun move() {
        assertEquals(listOf("b", "c", "a", "d", "e"), QueueOps.move(q, 0, 2))
        assertEquals(listOf("e", "a", "b", "c", "d"), QueueOps.move(q, 4, 0))
        assertEquals(q, QueueOps.move(q, 1, 1))
        assertEquals(q, QueueOps.move(q, -1, 2))
        assertEquals(q, QueueOps.move(q, 1, 9))
    }

    @Test fun currentFollowsMove() {
        // Check against the real list for every (current, from, to).
        for (cur in q.indices) for (from in q.indices) for (to in q.indices) {
            val moved = QueueOps.move(q, from, to)
            assertEquals("cur=$cur from=$from to=$to", q[cur], moved[QueueOps.currentAfterMove(cur, from, to)])
        }
    }

    @Test fun remove() {
        assertEquals(listOf("a", "c", "d", "e") to 1, QueueOps.remove(q, 2, 1))
        assertEquals(listOf("a", "b", "d", "e") to 2, QueueOps.remove(q, 2, 2)) // removing current → next takes its place
        assertEquals(listOf("a", "b", "c", "d") to 3, QueueOps.remove(q, 4, 4)) // removing last current → new last
        assertEquals(listOf("a", "b", "c", "e") to 2, QueueOps.remove(q, 2, 3))
        assertEquals(emptyList<String>() to -1, QueueOps.remove(listOf("x"), 0, 0))
        assertEquals(q to 2, QueueOps.remove(q, 2, 7))
    }

    @Test fun insertNext() {
        assertEquals(listOf("a", "b", "x", "y", "c", "d", "e"), QueueOps.insertNext(q, 1, listOf("x", "y")))
        assertEquals(listOf("x"), QueueOps.insertNext(emptyList(), -1, listOf("x")))
        assertEquals(q + "x", QueueOps.insertNext(q, 4, listOf("x")))
    }

    @Test fun nextIndex() {
        assertEquals(1, QueueOps.nextIndex(0, id, RepeatMode.OFF, false))
        assertNull(QueueOps.nextIndex(4, id, RepeatMode.OFF, false))
        assertEquals(0, QueueOps.nextIndex(4, id, RepeatMode.ALL, false))
        assertEquals(2, QueueOps.nextIndex(2, id, RepeatMode.ONE, false))
        assertEquals(3, QueueOps.nextIndex(2, id, RepeatMode.ONE, true)) // skip button leaves the repeated song
        assertEquals(0, QueueOps.nextIndex(4, id, RepeatMode.ONE, true))
        assertNull(QueueOps.nextIndex(0, emptyList(), RepeatMode.ALL, false))
        val shuffled = listOf(3, 0, 4, 1, 2)
        assertEquals(4, QueueOps.nextIndex(0, shuffled, RepeatMode.OFF, false))
        assertNull(QueueOps.nextIndex(2, shuffled, RepeatMode.OFF, false))
    }

    @Test fun previousIndex() {
        assertEquals(2, QueueOps.previousIndex(2, id, RepeatMode.OFF, 3_500)) // restart
        assertEquals(1, QueueOps.previousIndex(2, id, RepeatMode.OFF, 1_000))
        assertEquals(0, QueueOps.previousIndex(0, id, RepeatMode.OFF, 0))
        assertEquals(4, QueueOps.previousIndex(0, id, RepeatMode.ALL, 0))
    }

    @Test fun shuffleKeepsCurrentFirstAndIsPermutation() {
        repeat(50) { seed ->
            val o = QueueOps.shuffledOrder(10, 6, java.util.Random(seed.toLong()))
            assertEquals(6, o.first())
            assertEquals((0 until 10).toList(), o.sorted())
        }
        assertEquals(emptyList<Int>(), QueueOps.shuffledOrder(0, 0, java.util.Random(1)))
        assertEquals((0 until 4).toList(), QueueOps.shuffledOrder(4, -1, java.util.Random(1)).sorted())
    }

    @Test fun totalDuration() {
        assertEquals(9L, QueueOps.totalDurationMs(listOf(1, 2, 3, 4), 1))
        assertEquals(10L, QueueOps.totalDurationMs(listOf(1, 2, 3, 4), -3))
    }
}

class SleepTimerTest {
    @Test fun durationCountsDownAndExpires() {
        val s = SleepTimer(); s.startDuration(10 * 60_000, 1_000)
        assertEquals(10 * 60_000L, s.remainingMs(1_000))
        assertEquals(5 * 60_000L, s.remainingMs(1_000 + 5 * 60_000))
        assertEquals(0.5f, s.progress(1_000 + 5 * 60_000), 1e-4f)
        assertFalse(s.isExpired(1_000 + 10 * 60_000 - 1))
        assertTrue(s.isExpired(1_000 + 10 * 60_000))
        assertEquals(0L, s.remainingMs(10_000_000))
        assertTrue(s.shouldPause(1_000 + 10 * 60_000, trackRemainingMs = 120_000, isLastInQueue = false, finishSong = false))
    }

    @Test fun finishSongWaitsForTrackEnd() {
        val s = SleepTimer(); s.startDuration(60_000, 0)
        assertFalse(s.shouldPause(61_000, trackRemainingMs = 30_000, isLastInQueue = false, finishSong = true))
        assertTrue(s.shouldPause(91_000, trackRemainingMs = 400, isLastInQueue = false, finishSong = true))
    }

    @Test fun endOfTrackAndQueue() {
        val s = SleepTimer(); s.startEndOfTrack()
        assertFalse(s.shouldPause(0, 5_000, false, false))
        assertTrue(s.shouldPause(0, SleepTimer.END_GRACE_MS, false, false))
        s.startEndOfQueue()
        assertFalse(s.shouldPause(0, 100, isLastInQueue = false, finishSong = false))
        assertTrue(s.shouldPause(0, 100, isLastInQueue = true, finishSong = false))
    }

    @Test fun adjustNeverBelowOneMinute() {
        val s = SleepTimer(); s.startDuration(5 * 60_000, 0)
        s.adjust(-10 * 60_000, 0)
        assertEquals(60_000L, s.remainingMs(0))
        s.adjust(+15 * 60_000, 0)
        assertEquals(16 * 60_000L, s.remainingMs(0))
        assertEquals(16 * 60_000L, s.totalMs)
        assertEquals(0f, s.progress(0), 1e-6f)
    }

    @Test fun fadeAndCancel() {
        val s = SleepTimer(); s.startDuration(60_000, 0)
        assertEquals(1f, s.fadeFactor(0, 10_000), 0f)
        assertEquals(0.5f, s.fadeFactor(55_000, 10_000), 1e-4f)
        assertEquals(0f, s.fadeFactor(60_000, 10_000), 0f)
        s.cancel()
        assertEquals(SleepTimer.Mode.OFF, s.mode)
        assertFalse(s.shouldPause(1_000_000, 0, true, false))
        assertEquals(1f, s.fadeFactor(0, 10_000), 0f)
    }

    @Test fun adjustIgnoredWhenNotDuration() {
        val s = SleepTimer(); s.startEndOfTrack(); s.adjust(60_000, 0)
        assertEquals(0L, s.remainingMs(0))
    }

    @Test fun format() {
        assertEquals("05:00", SleepTimer.format(300_000))
        assertEquals("00:01", SleepTimer.format(1))
        assertEquals("1:00:00", SleepTimer.format(3_600_000))
    }

    @Test(expected = IllegalArgumentException::class) fun zeroDurationRejected() { SleepTimer().startDuration(0, 0) }
}
