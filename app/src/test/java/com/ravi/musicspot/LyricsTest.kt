package com.ravi.musicspot

import com.ravi.musicspot.lyrics.LrcParser
import com.ravi.musicspot.lyrics.Lyrics
import com.ravi.musicspot.lyrics.LyricLine
import com.ravi.musicspot.lyrics.LyricsChain
import com.ravi.musicspot.lyrics.LyricsProvider
import com.ravi.musicspot.lyrics.LyricsQuery
import com.ravi.musicspot.lyrics.activeIndex
import com.ravi.musicspot.lyrics.instrumentalGaps
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProvider(override val id: String, private val delayMs: Long = 0, private val result: () -> Lyrics?) : LyricsProvider {
    override val label = id
    var calls = 0
    override suspend fun lookup(q: LyricsQuery): Lyrics? { calls++; if (delayMs > 0) delay(delayMs); return result() }
}

class LyricsChainTest {
    private val q = LyricsQuery("Song", "Artist", null, 200_000)
    private val synced = Lyrics.Synced(LrcParser.parse("[00:01.00]hi"), "S")
    private val plain = Lyrics.Plain("hi", "P")

    // 1.0.7: word-by-word > line-by-line > plain, whatever the provider order; ties go to the user's order.
    @Test fun wordSyncedBeatsEarlierLineSynced() = runTest {
        val word = Lyrics.Synced(listOf(LyricLine(1000, 2000, "hi there", emptyList(), wordSynced = true)), "W")
        val a = FakeProvider("a") { synced }; val b = FakeProvider("b") { plain }; val c = FakeProvider("c") { word }
        assertSame(word, LyricsChain.find(listOf(a, b, c), q))
    }

    @Test fun firstLineSyncedWinsOverPlainWithoutWord() = runTest {
        val synced2 = Lyrics.Synced(LrcParser.parse("[00:02.00]yo"), "S2")
        assertSame(synced, LyricsChain.find(listOf(FakeProvider("p") { plain }, FakeProvider("a") { synced }, FakeProvider("b") { synced2 }), q))
    }

    @Test fun plainIsFallbackWhileLaterSyncedIsPreferred() = runTest {
        val a = FakeProvider("a") { plain }; val b = FakeProvider("b") { null }; val c = FakeProvider("c") { synced }
        assertSame(synced, LyricsChain.find(listOf(a, b, c), q))
        assertEquals(1, c.calls)
    }

    @Test fun firstPlainKeptWhenNoSynced() = runTest {
        val p2 = Lyrics.Plain("other", "P2")
        assertSame(plain, LyricsChain.find(listOf(FakeProvider("a") { null }, FakeProvider("b") { plain }, FakeProvider("c") { p2 }), q))
    }

    @Test fun errorsAndTimeoutsDoNotStopChain() = runTest {
        val tried = mutableListOf<Pair<String, String>>()
        val boom = FakeProvider("boom") { throw java.io.IOException("x") }
        val slow = FakeProvider("slow", delayMs = 60_000) { synced }
        val ok = FakeProvider("ok") { synced }
        val r = LyricsChain.find(listOf(boom, slow, ok), q, perProviderMs = 1_000) { id, s -> tried += id to s }
        assertSame(synced, r)
        assertEquals(listOf("boom", "slow", "ok"), tried.map { it.first })
        assertTrue(tried[0].second.startsWith("error"))
        assertEquals("none", tried[1].second)
        assertEquals("synced", tried[2].second)
    }

    @Test fun orderIsRespected() = runTest {
        val tried = mutableListOf<String>() // 1.0.7: providers run together; results are read in the user's order
        val ps = listOf("kugou", "lrclib", "netease").map { id -> FakeProvider(id) { null } }
        assertNull(LyricsChain.find(ps, q) { id, _ -> tried += id })
        assertEquals(listOf("kugou", "lrclib", "netease"), tried)
    }
}

class LrcParserTest {
    @Test fun basicLinesSortedWithEnds() {
        val l = LrcParser.parse("[ar:Someone]\n[ti:Song]\n[00:12.34]Second\n[00:01.5]First\n[01:02.345]Third", 70_000)
        assertEquals(listOf("First", "Second", "Third"), l.map { it.text })
        assertEquals(listOf(1_500L, 12_340L, 62_345L), l.map { it.startMs })
        assertEquals(12_340L, l[0].endMs)
        assertEquals(62_345L, l[1].endMs)
        assertEquals(70_000L, l[2].endMs) // last line clipped to track duration
        assertFalse(l[0].wordSynced)
    }

    @Test fun lastLineWithoutDuration() {
        val l = LrcParser.parse("[00:10.00]Only")
        assertEquals(15_000L, l.single().endMs)
    }

    @Test fun repeatedTimestampsExpand() {
        val l = LrcParser.parse("[00:10.00][00:30.00]Chorus\n[00:20.00]Verse")
        assertEquals(listOf("Chorus", "Verse", "Chorus"), l.map { it.text })
        assertEquals(listOf(10_000L, 20_000L, 30_000L), l.map { it.startMs })
    }

    @Test fun emptyLineIsGapMarker() {
        val l = LrcParser.parse("[00:01.00]A\n[00:05.00]\n[00:20.00]B")
        assertEquals(listOf("A", "B"), l.map { it.text })
        assertEquals(5_000L, l[0].endMs)
        assertEquals(1, l.instrumentalGaps().size)
    }

    @Test fun offsetTagApplied() {
        val l = LrcParser.parse("[offset:+500]\n[00:10.00]A")
        assertEquals(9_500L, l.single().startMs)
        val m = LrcParser.parse("[offset:-250]\n[00:00.10]A")
        assertEquals(350L, m.single().startMs)
    }

    @Test fun fractionsOfAnyLength() {
        val l = LrcParser.parse("[00:01.1]a\n[00:02.12]b\n[00:03.123]c\n[00:04]d\n[00:05:50]e")
        assertEquals(listOf(1_100L, 2_120L, 3_123L, 4_000L, 5_500L), l.map { it.startMs })
    }

    @Test fun enhancedWordTags() {
        val l = LrcParser.parse("[00:10.00]<00:10.00>Hello <00:10.50>big <00:11.20>world\n[00:13.00]Next")
        val line = l.first()
        assertTrue(line.wordSynced)
        assertEquals("Hello big world", line.text)
        assertEquals(listOf("Hello ", "big ", "world"), line.words.map { it.text })
        assertEquals(listOf(10_000L, 10_500L, 11_200L), line.words.map { it.startMs })
        assertEquals(listOf(10_500L, 11_200L, 13_000L), line.words.map { it.endMs })
    }

    @Test fun spreadWordsMonotonicAndInsideLine() {
        val l = LrcParser.parse("[00:10.00]one two three four\n[00:14.00]x").first()
        assertEquals(4, l.words.size)
        assertTrue(l.words.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        assertTrue(l.words.first().startMs == 10_000L && l.words.last().endMs <= 14_000L)
    }

    @Test fun garbageIgnored() {
        assertTrue(LrcParser.parse("no tags here\nfoo [00:01.00] not at start").isEmpty())
    }

    @Test fun activeIndexLookup() {
        val l = LrcParser.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        assertEquals(-1, l.activeIndex(500))
        assertEquals(0, l.activeIndex(1_000))
        assertEquals(1, l.activeIndex(8_999))
        assertEquals(2, l.activeIndex(100_000))
    }
}
