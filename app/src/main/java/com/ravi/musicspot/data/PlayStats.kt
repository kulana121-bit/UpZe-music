package com.ravi.musicspot.data

import com.ravi.musicspot.core.JsonFile
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * v2.0.5 play-count store (filesDir/play-stats.json): a play is counted by PlaybackService after ~30 s (or half a
 * short song) of real listening; a consecutive replay of the same song (repeat-one or replaying it) also counts as a
 * repeat. Feeds Home's "On repeat" and the autoplay taste re-rank. Bounded to [MAX] songs (least recent dropped).
 */
class PlayStatsRepo(dir: File) {
    // 1.0.5: writes held until load() read the file (an early count could otherwise overwrite play-stats.json).
    private val file = JsonFile(File(dir, "play-stats.json"), PlayStatsData.serializer(), debounceMs = 1500, holdWritesUntilRead = true)
    private val _data = MutableStateFlow(PlayStatsData())
    val data: StateFlow<PlayStatsData> = _data
    private val lock = Any()
    @Volatile private var loaded = false
    /** 1.0.5: changes made before [load] finished, replayed on top of the data read from disk. */
    private val beforeLoad = mutableListOf<(PlayStatsData) -> PlayStatsData>()

    /** Background thread only (Graph.init runs it on Dispatchers.IO). */
    fun load() {
        val disk = file.read()
        // 1.0.5: was `_data.value = it`, which dropped plays counted while the file was still loading.
        synchronized(lock) {
            if (disk != null) _data.update { _ -> beforeLoad.fold(disk) { d, f -> f(d) } }
            beforeLoad.clear(); loaded = true
        }
        // Pending (held) saves are written now by JsonFile itself; nothing new to write otherwise.
    }

    /** 1.0.5: suspending variant; never reads the disk on the caller's thread. */
    suspend fun loadAsync() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load() }

    private fun edit(f: (PlayStatsData) -> PlayStatsData) {
        synchronized(lock) { _data.update(f); if (!loaded) beforeLoad += f }
        file.save { _data.value }
    }

    /** 1.0.2: older installs have no per-day log; seed it once from the listening history (see [PlayStatsOps.seed]). */
    fun seedFrom(history: List<HistoryEntry>) {
        if (_data.value.seeded) return
        edit { d -> PlayStatsOps.seed(d, history) } // 1.0.5: re-checked inside the atomic update
    }

    /** One counted play (PlaybackService, after ~30 s of real listening). Every repeat play counts. */
    fun count(t: Track, repeat: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (com.ravi.musicspot.Graph.incognito) return
        edit { d -> PlayStatsOps.count(d, t, repeat, nowMs) }
    }

    /** 1.0.6: songs the user tends to play at this time of day (Home shelf, autoplay re-rank). */
    fun timeOfDayPicks(nowMs: Long = System.currentTimeMillis(), limit: Int = 20): List<Track> {
        val d = _data.value
        return com.ravi.musicspot.logic.TimeOfDay.picksFor(d.stats.values, d.dayparts, com.ravi.musicspot.logic.TimeOfDay.partAt(nowMs), nowMs, limit = limit)
    }

    /** 1.0.2: Backup restore. */
    fun replace(d: PlayStatsData) = edit { d } // 1.0.5: atomic update (was a plain .value write)

    fun clear() = edit { PlayStatsData() }

    companion object { const val MAX = PlayStatsOps.MAX; const val MAX_DAYS = PlayStatsOps.MAX_DAYS }
}
