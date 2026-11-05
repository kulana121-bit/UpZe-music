package com.ravi.musicspot.data

import com.ravi.musicspot.core.JsonFile
import com.ravi.musicspot.logic.PlayStat
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class PlayStatsData(val stats: Map<String, PlayStat> = emptyMap())

/**
 * v2.0.5 play-count store (filesDir/play-stats.json): a play is counted by PlaybackService after ~30 s (or half a
 * short song) of real listening; a consecutive replay of the same song (repeat-one or replaying it) also counts as a
 * repeat. Feeds Home's "On repeat" and the autoplay taste re-rank. Bounded to [MAX] songs (least recent dropped).
 */
class PlayStatsRepo(dir: File) {
    private val file = JsonFile(File(dir, "play-stats.json"), PlayStatsData.serializer(), debounceMs = 1500)
    private val _data = MutableStateFlow(PlayStatsData())
    val data: StateFlow<PlayStatsData> = _data

    fun load() { file.read()?.let { _data.value = it } }

    fun count(t: Track, repeat: Boolean, nowMs: Long = System.currentTimeMillis()) {
        _data.update { d ->
            val cur = d.stats[t.id]
            val next = PlayStat(t, (cur?.plays ?: 0) + 1, (cur?.repeats ?: 0) + if (repeat) 1 else 0, nowMs)
            var map = d.stats + (t.id to next)
            if (map.size > MAX) map = map.entries.sortedByDescending { it.value.lastMs }.take(MAX).associate { it.key to it.value }
            d.copy(stats = map)
        }
        file.save { _data.value }
    }

    fun clear() { _data.value = PlayStatsData(); file.save { _data.value } }

    companion object { const val MAX = 2000 }
}
