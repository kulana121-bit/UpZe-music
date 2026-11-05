package com.ravi.musicspot.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.ravi.musicspot.model.Collection

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    EXPLORE("Explore", Icons.Outlined.Explore, Icons.Rounded.Explore),
    SEARCH("Search", Icons.Outlined.Search, Icons.Rounded.Search),
    LIBRARY("Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

sealed interface Route {
    data class Root(val tab: Tab) : Route
    /** Any remote collection: "yt:<browseId>", "js:pl:<id>", "ia:<identifier>". */
    data class Remote(val collection: Collection) : Route
    data class Artist(val id: String, val name: String) : Route
    data class Mood(val title: String, val browseId: String, val params: String?) : Route
    data class LocalPlaylist(val id: String) : Route
    data object Liked : Route
    data object History : Route
    data object Downloads : Route
    data object DeviceMusic : Route
    data object Saved : Route
    data class SettingsPage(val page: String) : Route
    /** "See all" for a Home shelf (its items are already loaded). */
    data class ShelfAll(val shelf: com.ravi.musicspot.model.Shelf) : Route
    data object GoogleLogin : Route
    data object SpotifyLogin : Route
    /** 1.0.1: the Customize (design setup) flow and the artist picker, opened from Settings; drawn full screen by AppRoot. */
    data object Customize : Route
    data object Artists : Route
}

/** One back stack per tab, so switching tabs keeps each tab's place (like OpenTune's tabs). */
class Navigator { // v2.0.14: stacks[x] ?: root fallback instead of !!
    var tab by mutableStateOf(Tab.HOME); private set
    private val stacks = mutableStateMapOf<Tab, List<Route>>().apply { Tab.entries.forEach { put(it, listOf(Route.Root(it))) } }
    /** +1 push, -1 pop, 0 tab switch — drives the page transition direction. */
    var lastDirection by mutableStateOf(0); private set

    val current: Route get() = (stacks[tab] ?: listOf(Route.Root(tab))).last()
    /** Depth of the current tab's stack (1 = the tab root). */
    val depth: Int get() = (stacks[tab] ?: listOf(Route.Root(tab))).size

    /**
     * v2.0.5 scroll restore: each back-stack entry has its own saved UI state (scroll positions of every list, grid
     * and carousel, query fields) under this key, restored when the user comes back to it by Back or by switching
     * dock tabs. Entries popped off a stack have their state dropped ([dropped]).
     */
    fun pageKey(t: Tab = tab, d: Int = depth, r: Route = current): String = "${t.name}/$d/${r.javaClass.simpleName}/${r.hashCode()}"
    private val droppedKeys = mutableListOf<String>()
    /** Keys of entries that left their stack since the last call. */
    fun dropped(): List<String> = synchronized(droppedKeys) { droppedKeys.toList().also { droppedKeys.clear() } }
    private fun drop(t: Tab, entries: List<Route>, fromDepth: Int) = synchronized(droppedKeys) {
        entries.forEachIndexed { i, r -> if (i + 1 >= fromDepth) droppedKeys += pageKey(t, i + 1, r) }
    }
    val canGoBack: Boolean get() = (stacks[tab] ?: listOf(Route.Root(tab))).size > 1 || tab != Tab.HOME

    fun select(t: Tab) {
        if (t == tab) { drop(t, (stacks[t] ?: listOf(Route.Root(t))), 2); stacks[t] = listOf(Route.Root(t)); lastDirection = -1 } else { tab = t; lastDirection = 0 }
    }
    fun push(r: Route) { stacks[tab] = (stacks[tab] ?: listOf(Route.Root(tab))) + r; lastDirection = 1 }
    fun open(t: Tab, r: Route) { drop(t, (stacks[t] ?: listOf(Route.Root(t))), 2); tab = t; stacks[t] = listOf(Route.Root(t), r); lastDirection = 1 }
    fun back(): Boolean {
        val s = (stacks[tab] ?: listOf(Route.Root(tab)))
        return when {
            s.size > 1 -> { drop(tab, s, s.size); stacks[tab] = s.dropLast(1); lastDirection = -1; true }
            tab != Tab.HOME -> { tab = Tab.HOME; lastDirection = 0; true }
            else -> false
        }
    }
}

/** Opens a collection with the right page for its kind. */
fun Navigator.openCollection(c: Collection) = when (c.kind) {
    Collection.Kind.ARTIST -> push(Route.Artist(c.id, c.title))
    Collection.Kind.LOCAL_PLAYLIST -> push(Route.LocalPlaylist(c.id))
    else -> push(Route.Remote(c))
}
