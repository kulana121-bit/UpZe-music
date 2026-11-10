package com.ravi.musicspot.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object Unset

/**
 * 1.0.7: like `remember(keys) { compute() }`, but only the very first value is computed on the main thread (so the
 * first frame shows exactly what it showed before). Every later recompute, when [keys] change, runs on
 * Dispatchers.Default and the screen keeps the previous value until it lands. Used for Home's shelves, which were
 * rebuilt on the main thread (groupBy/sort over thousands of liked songs) on every library edit, so a Spotify or
 * YouTube Music import made Home stutter on 2 GB phones. [compute] must be pure (no Compose state writes).
 */
@Composable
fun <T> rememberOffMain(vararg keys: Any?, compute: () -> T): T {
    val latest by rememberUpdatedState(compute)
    val holder = remember { mutableStateOf<Any?>(Unset) }
    // The keys the held value was computed for; the effect skips a run whose keys the value already reflects.
    val computedFor = remember { arrayOfNulls<Array<out Any?>>(1) }
    if (holder.value === Unset) { holder.value = compute(); computedFor[0] = keys.copyOf() }
    LaunchedEffect(*keys) {
        val now = keys.copyOf()
        if (sameRefs(computedFor[0], now)) return@LaunchedEffect // identity, not equals: no O(n) list compare on main
        val v = withContext(Dispatchers.Default) { latest() }
        holder.value = v
        computedFor[0] = now
    }
    @Suppress("UNCHECKED_CAST")
    return holder.value as T
}

private fun sameRefs(a: Array<out Any?>?, b: Array<out Any?>): Boolean {
    if (a == null || a.size != b.size) return false
    for (i in a.indices) if (a[i] !== b[i]) return false
    return true
}
