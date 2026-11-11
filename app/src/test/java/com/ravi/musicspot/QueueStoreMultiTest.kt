package com.ravi.musicspot

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.JsonFile
import com.ravi.musicspot.data.NamedQueue
import com.ravi.musicspot.data.QueueSet
import com.ravi.musicspot.data.QueueSets
import com.ravi.musicspot.data.QueueSnapshot
import com.ravi.musicspot.data.QueueStore
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Pack 6: up to 10 named queues, file-based like the other store tests. */
class QueueStoreMultiTest {
    private fun t(id: String) = Track(id, "Song $id", "Artist")
    private val snapA = QueueSnapshot(listOf(t("a1"), t("a2"), t("a3")), index = 2, positionMs = 61_000, shuffle = true, repeat = 1)
    private val snapB = QueueSnapshot(listOf(t("b1"), t("b2")), index = 1, positionMs = 4_000, shuffle = false, repeat = 0)

    private fun <T> withDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("upze-queues").toFile()
        try { return block(dir) } finally { dir.deleteRecursively() }
    }
    private fun flush() = runBlocking { JsonFile.flushAll().join() }

    @Test fun legacySingleQueueMigratesLosslesslyIntoMyQueue() = withDir { dir ->
        File(dir, "queue.json").writeText(Http.json.encodeToString(QueueSnapshot.serializer(), snapA))
        val store = QueueStore(dir)
        assertEquals(snapA, store.read())                     // restored exactly as before
        val set = store.readAll()
        assertEquals(1, set.queues.size)
        assertEquals(QueueSets.DEFAULT_NAME, set.activeQueue!!.name)
        assertEquals("My queue", set.activeQueue!!.name)
        flush()
        assertEquals(snapA, QueueStore(dir).read())           // persisted in queues.json on the next start too
    }

    @Test fun eachQueueKeepsItsOwnIndexPositionAndShuffle() = withDir { dir ->
        val store = QueueStore(dir)
        store.readAll()
        store.save(snapA)
        val gym = store.create("Gym")!!
        store.setActive(gym)
        assertNull("a new queue starts empty", store.read())
        store.save(snapB)
        flush()
        val again = QueueStore(dir)
        assertEquals(gym, again.readAll().active)
        assertEquals(snapB, again.read())
        again.setActive(QueueSets.DEFAULT_ID)
        assertEquals(snapA, again.read())                     // index 2, 61 s, shuffle on: untouched by the other queue
        assertEquals(snapB, again.queue(gym)!!.snapshot)
    }

    @Test fun activeQueueIsMirroredToQueueJsonForOlderVersions() = withDir { dir ->
        val store = QueueStore(dir)
        store.readAll(); store.save(snapA)
        flush()
        assertEquals(snapA, Http.json.decodeFromString(QueueSnapshot.serializer(), File(dir, "queue.json").readText()))
    }

    @Test fun editsBeforeTheFirstReadKeepTheOtherQueues() = withDir { dir ->
        val set = QueueSet("q2", listOf(NamedQueue(QueueSets.DEFAULT_ID, "My queue", snapA), NamedQueue("q2", "Gym", snapB)))
        File(dir, "queues.json").writeText(Http.json.encodeToString(QueueSet.serializer(), set))
        val store = QueueStore(dir)
        val x = snapB.copy(positionMs = 9_000)
        store.save(x)                                         // may land before the background read
        val all = store.readAll()
        assertEquals(2, all.queues.size)
        assertEquals(snapA, all.queues.first { it.id == QueueSets.DEFAULT_ID }.snapshot)
        assertEquals(x, store.read())
    }

    @Test fun tenQueueCap() = withDir { dir ->
        val store = QueueStore(dir)
        store.readAll()
        repeat(9) { assertNotNull(store.create()) }
        assertEquals(10, store.readAll().queues.size)
        assertNull(store.create())
        assertEquals(10, store.readAll().queues.size)
        assertEquals((2..10).map { "Queue $it" }, store.readAll().queues.drop(1).map { it.name })
    }

    @Test fun deleteRules() = withDir { dir ->
        val store = QueueStore(dir)
        store.readAll()
        assertFalse("the last queue can't be deleted", store.delete(QueueSets.DEFAULT_ID))
        val q2 = store.create("Two")!!; val q3 = store.create("Three")!!
        assertFalse("the active queue can't be deleted directly", store.delete(QueueSets.DEFAULT_ID))
        // Deleting the active queue: switch to the next one (else the previous) first.
        val s = store.readAll()
        assertEquals(q2, QueueSets.fallbackFor(s, QueueSets.DEFAULT_ID))
        assertEquals(q2, QueueSets.fallbackFor(s, q3))
        store.setActive(q3)
        assertTrue(store.delete(QueueSets.DEFAULT_ID))
        assertEquals(listOf(q2, q3), store.readAll().queues.map { it.id })
        store.rename(q2, "  Workout  ")
        assertEquals("Workout", store.queue(q2)!!.name)
        store.rename(q2, "   ")
        assertEquals("Workout", store.queue(q2)!!.name)
    }

    @Test fun repairsABrokenFile() {
        val broken = QueueSet("missing", List(12) { NamedQueue("q$it", "Q$it") } + NamedQueue("q0", "dup"))
        val s = QueueSets.sane(broken)
        assertEquals(10, s.queues.size)
        assertEquals("q0", s.active)
        assertEquals(1, QueueSets.sane(QueueSet("x", emptyList())).queues.size)
        assertEquals(QueueSets.migrate(null).activeQueue!!.snapshot, QueueSnapshot())
    }
}
