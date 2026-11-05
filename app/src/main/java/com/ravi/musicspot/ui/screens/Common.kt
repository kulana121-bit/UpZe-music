package com.ravi.musicspot.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.ui.Navigator
import kotlinx.coroutines.CancellationException

/**
 * Bottom space pages leave for the floating chrome (nav pill + mini player). Dynamic (not static) since v2.0.3:
 * it animates while the nav bar hides on scroll, and only the pages that read it should recompose.
 */
val LocalBottomInset = compositionLocalOf { 0.dp }
val LocalNav = staticCompositionLocalOf<Navigator> { error("no navigator") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val data: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val retry: () -> Unit)

/** Loads [block] once per [key]; re-runs on retry and when the connection comes back after a failure. */
@Composable
fun <T> rememberLoad(vararg key: Any?, block: suspend () -> T): Loader<T> {
    var attempt by remember { mutableIntStateOf(0) }
    // v2.0.5: results are kept in a small in-memory cache (per call site + keys), so a page you come back to (Back,
    // dock tab switch) renders at once and its restored scroll position lands on real items; refreshed after 10 min.
    val cacheKey = remember(*key) { block.javaClass.name + "|" + key.contentDeepToString() }
    @Suppress("UNCHECKED_CAST")
    var state by remember(*key) { mutableStateOf((LoadCache.get(cacheKey) as? Load<T>) ?: Load.Loading) }
    val online by Graph.online.collectAsState()
    LaunchedEffect(*key, attempt) {
        if (attempt == 0 && state is Load.Ok && LoadCache.fresh(cacheKey)) return@LaunchedEffect
        if (state !is Load.Ok) state = Load.Loading
        state = try { Load.Ok(block()).also { LoadCache.put(cacheKey, it) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (state is Load.Ok) state else Load.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    LaunchedEffect(online) { if (online && state is Load.Failed) attempt++ }
    return Loader(state) { attempt++ }
}

@Composable
fun pagePadding(top: Dp = 0.dp, header: Dp = 0.dp): PaddingValues {
    val sb = WindowInsets.statusBars.asPaddingValues()
    val dir = LocalLayoutDirection.current
    // v2.0.17: [header] is the floating GlassTopBar's measured height (status bar included); the list never starts under it.
    val t = maxOf(sb.calculateTopPadding() + top, if (header > 0.dp) header + 8.dp else 0.dp)
    return PaddingValues(start = sb.calculateStartPadding(dir), end = sb.calculateEndPadding(dir), top = t, bottom = LocalBottomInset.current)
}

/** v2.0.17: holds a floating header's measured height; pass to GlassTopBar(height = …) and pagePadding(header = …). */
@Composable
fun rememberHeaderHeight() = remember { mutableStateOf(0.dp) }

@Suppress("unused") @Composable private fun Unused() = Box {}

/** Bounded LRU of page results for [rememberLoad] (process lifetime). */
object LoadCache {
    private const val MAX = 48
    private const val FRESH_MS = 10 * 60_000L
    private val map = object : LinkedHashMap<String, Pair<Load<*>, Long>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Load<*>, Long>>?) = size > MAX
    }
    @Synchronized fun get(k: String): Load<*>? = map[k]?.first
    @Synchronized fun fresh(k: String): Boolean = map[k]?.let { System.currentTimeMillis() - it.second < FRESH_MS } == true
    @Synchronized fun put(k: String, v: Load<*>) { map[k] = v to System.currentTimeMillis() }
}
