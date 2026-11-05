@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravi.musicspot.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.account.Account
import com.ravi.musicspot.data.DownloadEntry
import com.ravi.musicspot.data.FeedShelf
import com.ravi.musicspot.data.HomeFeedRepo
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Shelf
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.Tab
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.components.OfflineBanner
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.motion.SkeletonBox
import com.ravi.musicspot.ui.motion.chipColors
import com.ravi.musicspot.ui.motion.pressable
import com.ravi.musicspot.ui.motion.sharedCover
import com.ravi.musicspot.ui.motion.shimmer
import com.ravi.musicspot.ui.motion.staggerIn
import com.ravi.musicspot.ui.openCollection
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.launch
import java.util.Calendar

/** Responsive sizes from the window width (phone → tablet landscape). */
internal data class HomeDims(val width: Dp, val card: Dp, val quickCol: Dp, val gutter: Dp, val wide: Boolean)

internal fun dims(w: Dp): HomeDims {
    val wide = w >= 600.dp
    val card = when { w >= 1100.dp -> 168.dp; wide -> 152.dp; else -> 132.dp }
    val quickCols = when { w >= 1100.dp -> 3; wide -> 2; else -> 1 }
    val quick = if (quickCols == 1) w * 0.86f else (w - 32.dp - 12.dp * (quickCols - 1)) / quickCols
    return HomeDims(w, card, quick, if (wide) 20.dp else 16.dp, wide)
}

/**
 * v2.0.2 Home: dense, YT Music / OpenTune-style, all real data. Cached feed paints instantly (also offline), then
 * refreshes in the background; pull to refresh; skeleton shimmer only on a cold first load.
 */
@Composable
fun HomeScreen() {
    // v2.0.12: the Aura dashboard is a separate design with the same data and sections; Classic stays below.
    val homeStyle = Graph.settings.state.collectAsState().value.homeStyle
    if (homeStyle == com.ravi.musicspot.data.HomeStyle.AURA) { AuraHomeScreen(); return }
    if (homeStyle == com.ravi.musicspot.data.HomeStyle.BENTO) { BentoHomeScreen(); return }
    if (homeStyle == com.ravi.musicspot.data.HomeStyle.SPOTLIGHT) { SpotlightHomeScreen(); return }
    ClassicHomeScreen()
}

@Composable
private fun ClassicHomeScreen() {
    val nav = LocalNav.current
    val online by Graph.online.collectAsState()
    val lib by Graph.library.data.collectAsState()
    val dls by Graph.downloads.items.collectAsState()
    val settings by Graph.settings.state.collectAsState()
    val accounts by Graph.accounts.accounts.collectAsState()
    val feed by HomeFeedRepo.feed.collectAsState()
    val refreshing by HomeFeedRepo.refreshing.collectAsState()
    val error by HomeFeedRepo.error.collectAsState()
    val scope = rememberCoroutineScope()
    val ytAccount = accounts.firstOrNull { it.service == Account.Service.YOUTUBE }
    val sourcesKey = settings.enabledSources.filter { it == SourceKind.YOUTUBE || it == SourceKind.JIOSAAVN }.toSet()

    LaunchedEffect(sourcesKey, ytAccount?.id) { HomeFeedRepo.load(force = false) }
    LaunchedEffect(online) { if (online && HomeFeedRepo.feed.value?.isEmpty != false) HomeFeedRepo.load(force = true) }

    val stats by Graph.playStats.data.collectAsState()
    // v2.0.5 "On repeat": most played / repeated / liked songs, recency-decayed (Taste.favourites).
    val favourites = remember(stats, lib.liked) { com.ravi.musicspot.logic.Taste.favourites(stats.stats.values, lib.liked, System.currentTimeMillis()) }
    val recent = remember(lib.history) { lib.history.map { it.track }.distinctBy { it.id }.take(20) }
    val downloaded = remember(dls) { dls.values.filter { it.state == DownloadEntry.State.DONE }.sortedByDescending { it.atMs }.map { it.track } }
    val artists = remember(lib.history, lib.liked) {
        (lib.history.map { it.track } + lib.liked).filter { (it.artistId?.startsWith("UC") == true || it.artistId?.startsWith("yt:") == true) && it.artist.isNotBlank() }
            .groupBy { it.artistId!! }.entries.sortedByDescending { it.value.size }.take(14)
            .map { (id, ts) -> Collection(id, Collection.Kind.ARTIST, ts.first().artist.substringBefore(',').trim(), "${ts.size} plays", ts.first().artUrl) }
    }
    val mixes = remember(lib.playlists, lib.saved, feed) {
        lib.playlists.map { Collection(it.id, Collection.Kind.LOCAL_PLAYLIST, it.name, "${it.tracks.size} songs", it.artUrl ?: it.tracks.firstOrNull()?.artUrl) } +
            lib.saved + feed?.ytShelves.orEmpty().filter { it.title.contains("mix", true) }.flatMap { it.collections }
    }
    val list = rememberLazyListState()
    // Endless YT Music home: load the next page when the end comes into view.
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } == true } }
    var loadingMore by remember { mutableStateOf(false) }
    LaunchedEffect(nearEnd, feed?.ytContinuation) {
        if (nearEnd && !loadingMore && feed?.ytContinuation != null && online) { loadingMore = true; runCatching { HomeFeedRepo.more() }; loadingMore = false }
    }

    PullToRefreshBox(isRefreshing = refreshing && feed != null, onRefresh = { scope.launch { HomeFeedRepo.refresh() } }, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val d = dims(maxWidth)
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = pagePadding()) {
                item(key = "offline") { OfflineBanner() }
                item(key = "header") { HomeHeader(ytAccount, online, d) }
                val heroTrack = recent.firstOrNull() ?: feed?.quickPicks?.firstOrNull()
                if (heroTrack != null) item(key = "hero") {
                    HomeHero(heroTrack, if (recent.isNotEmpty()) "Continue listening" else "Featured for you", d) {
                        if (recent.isNotEmpty()) Graph.player.play(recent, 0) else feed?.quickPicks?.let { Graph.player.play(it, 0) }
                    }
                }
                val f = feed
                if (f != null && f.moods.isNotEmpty()) item(key = "moods") {
                    MoodChips(f.moods.map { it.title }, d) { i -> f.moods[i].let { m -> nav.push(Route.Mood(m.title, m.browseId, m.params)) } }
                }
                item(key = "shortcuts") { Shortcuts(d, lib.liked.size, downloaded.size, recent.size) }

                            if (f == null && online && favourites.size < 2) {
                    // v2.0.14: a failed first load (no cache) used to leave the shimmer up forever; show the error with Retry.
                    if (error == null || refreshing) item(key = "skeleton") { HomeSkeleton(d) }
                    else item(key = "load-error") { EmptyState(Icons.Rounded.CloudOff, "Couldn't load Home", error ?: "Pull down to try again.", "Retry") { scope.launch { HomeFeedRepo.refresh() } } }
                    return@LazyColumn
                }

                var sec = 0
                fun nextSec() = sec++
                if (favourites.size >= 2) {
                    val i = nextSec()
                    item(key = "fav-h") { OnRepeatHeader(favourites, i) }
                    item(key = "fav") { QuickPicks(favourites, d, i, rows = 2) }
                }
                f?.forYou?.let { shelfItems("foryou", it, d, nextSec()) } // v2.0.15: seed-artist shelf on top
                if (f != null && f.quickPicks.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "qp-h") { Header("Quick picks", "Start radio from a song", i) { nav.push(Route.ShelfAll(Shelf("Quick picks", f.quickPicks))) } }
                    item(key = "qp") { QuickPicks(f.quickPicks, d, i) }
                }
                if (recent.size > 1) {
                    val i = nextSec()
                    item(key = "kl-h") { Header("Recently played", null, i) { nav.push(Route.History) } }
                    item(key = "kl") { RecentGrid(recent.drop(1).take(if (d.wide) 9 else 6), d, i) }
                }
                if (mixes.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "mix-h") { Header("Your mixes & playlists", null, i) { nav.select(Tab.LIBRARY) } }
                    item(key = "mix") { CollectionShelf(mixes.distinctBy { it.id }.take(20), d.card, d, i) }
                }
                if (!online && downloaded.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "dl-h") { Header("Your downloads", "Plays offline", i) { nav.push(Route.Downloads) } }
                    item(key = "dl") { TrackShelf(downloaded.take(20), d.card, d, i) }
                }
                if (f != null) {
                    f.ytShelves.take(3).forEachIndexed { n, s -> shelfItems("yt$n", s, d, nextSec()) }
                    f.charts.firstOrNull()?.let { shelfItems("chart0", it.copy(title = if (it.title.contains("India", true)) it.title else "Trending in India · ${it.title}"), d, nextSec()) }
                    f.jsTrending?.let { shelfItems("jst", it, d, nextSec()) }
                    f.regional.forEachIndexed { n, s -> shelfItems("reg$n", s, d, nextSec()) }
                    f.newReleases.forEachIndexed { n, s -> shelfItems("nr$n", s.copy(title = if (n == 0 && !s.title.contains("new", true)) "New releases" else s.title), d, nextSec()) }
                    f.charts.drop(1).forEachIndexed { n, s -> shelfItems("chart${n + 1}", s, d, nextSec()) }
                    if (artists.isNotEmpty()) {
                        val i = nextSec()
                        item(key = "art-h") { Header("Artists you like", null, i) }
                        item(key = "art") { ArtistRow(artists, d, i) }
                    }
                    f.jsPlaylists?.let { shelfItems("jsp", it, d, nextSec()) }
                    f.ytShelves.drop(3).forEachIndexed { n, s -> shelfItems("ytm$n", s, d, nextSec()) }
                    if (loadingMore) item(key = "more") { Row(Modifier.fillMaxWidth().padding(16.dp)) { SkeletonBox(d.card, d.card) } }
                    if (f.isEmpty) item(key = "empty") {
                        if (SourceKind.YOUTUBE !in settings.enabledSources && SourceKind.JIOSAAVN !in settings.enabledSources)
                            EmptyState(Icons.Rounded.PhoneAndroid, "Online sources are off", "Home comes from YouTube Music and JioSaavn. Turn one on in Settings → Sources.", "Open Sources") { nav.select(Tab.SETTINGS); nav.push(Route.SettingsPage("sources")) }
                        else EmptyState(Icons.Rounded.Download, "Couldn't load Home", error ?: "Pull down to try again.", "Retry") { scope.launch { HomeFeedRepo.refresh() } }
                    }
                }
                if (!online && f == null) item(key = "offline-empty") {
                    EmptyState(Icons.Rounded.CloudOff, "You're offline", "Downloads and music on this device still play.", "Downloads") { nav.push(Route.Downloads) }
                }
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shelfItems(key: String, s: FeedShelf, d: HomeDims, index: Int) {
    if (s.isEmpty) return
    item(key = "$key-h") {
        val nav = LocalNav.current
        Header(s.title, s.subtitle, index) { nav.push(Route.ShelfAll(s.toShelf())) }
    }
    if (s.tracks.isNotEmpty()) item(key = "$key-t") {
        // Song-heavy shelves render as a compact paged grid (denser), the rest as covers.
        if (s.tracks.size >= 8 && s.collections.isEmpty()) QuickPicks(s.tracks, d, index, rows = 3) else TrackShelf(s.tracks, d.card, d, index)
    }
    if (s.collections.isNotEmpty()) item(key = "$key-c") { CollectionShelf(s.collections, d.card, d, index) }
}

// ---- pieces -------------------------------------------------------------------------------------------------------

@Composable
private fun HomeHeader(acc: Account?, online: Boolean, d: HomeDims) {
    val nav = LocalNav.current
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late night vibes" }
    val first = acc?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
    Row(Modifier.fillMaxWidth().padding(start = d.gutter, end = d.gutter, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClickLabel = "Accounts") { nav.select(Tab.SETTINGS); nav.push(Route.SettingsPage("accounts")) },
            contentAlignment = Alignment.Center) {
            if (acc?.avatarUrl != null) Artwork(acc.avatarUrl, Modifier.fillMaxSize(), CircleShape, "Your account")
            else Text((first ?: "M").take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(greeting + (first?.let { ", $it" } ?: ""), style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.3).sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (online) "Made from YouTube Music & JioSaavn" else "Offline · showing what's saved",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        // v2.0.4: Search / Settings buttons removed here; the bottom dock (or side rail) already has both.
    }
}

@Composable
private fun MoodChips(titles: List<String>, d: HomeDims, onSelect: (Int) -> Unit) {
    var selected by remember { mutableStateOf(-1) }
    LazyRow(contentPadding = PaddingValues(horizontal = d.gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(titles) { i, t ->
            val (bg, fg) = chipColors(selected == i)
            Box(Modifier.clip(Ms.pill).background(bg).pressable(onClickLabel = t, pressedScale = 0.92f) { selected = i; onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(t, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Shortcuts(d: HomeDims, liked: Int, downloads: Int, history: Int) {
    val nav = LocalNav.current
    data class S(val icon: ImageVector, val label: String, val count: Int, val go: () -> Unit)
    val items = listOf(
        S(Icons.Rounded.Favorite, "Liked", liked) { nav.push(Route.Liked) },
        S(Icons.Rounded.Download, "Downloads", downloads) { nav.push(Route.Downloads) },
        S(Icons.Rounded.History, "History", history) { nav.push(Route.History) },
        S(Icons.Rounded.PhoneAndroid, "On device", -1) { nav.push(Route.DeviceMusic) },
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = d.gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { s ->
            Row(Modifier.weight(1f).height(44.dp).glass(Ms.r12).pressable(onClickLabel = s.label, pressedScale = 0.96f, onClick = s.go).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(s.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(if (s.count > 0 && d.wide) "${s.label} · ${s.count}" else s.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?, index: Int, onAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().staggerIn(index).padding(start = 16.dp, end = 4.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, letterSpacing = (-0.2).sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onAll != null) TextButton(onClick = onAll) { Text("See all", style = MaterialTheme.typography.labelLarge) }
    }
}

/** v2.0.5 "On repeat" header: title plus Play all and Shuffle. */
@Composable
private fun OnRepeatHeader(tracks: List<Track>, index: Int) {
    Row(Modifier.fillMaxWidth().staggerIn(index).padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("YOUR FAVOURITES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text("On repeat", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, letterSpacing = (-0.2).sp), fontWeight = FontWeight.Bold, maxLines = 1)
        }
        IconButton(onClick = { Graph.player.play(tracks, 0, shuffle = true) }) { Icon(Icons.Rounded.Shuffle, "Shuffle On repeat") }
        androidx.compose.material3.FilledTonalButton(onClick = { Graph.player.play(tracks, 0) }) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Play all")
        }
    }
}

/** Horizontally paged grid of compact song rows (rows × n columns), snapping per column. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickPicks(tracks: List<Track>, d: HomeDims, index: Int, rows: Int = 4) {
    // v2.0.14: feeds/favourites can repeat a song id; duplicate lazy keys throw IllegalArgumentException.
    val tracks = remember(tracks) { tracks.distinctBy { it.id } }
    val state = rememberLazyGridState()
    val rowH = 60.dp
    LazyHorizontalGrid(
        rows = GridCells.Fixed(rows), state = state,
        modifier = Modifier.fillMaxWidth().height(rowH * rows + 8.dp).staggerIn(index),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        flingBehavior = rememberSnapFlingBehavior(state),
    ) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            Row(Modifier.width(d.quickCol).height(rowH).clip(Ms.r12).pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.97f) { Graph.player.play(tracks, i) }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(t.artUrl, Modifier.size(48.dp), Ms.r8, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { Menus.track = t }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreVert, "More options for ${t.title}", Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
internal fun TrackShelf(tracks: List<Track>, w: Dp, d: HomeDims, index: Int) {
    // v2.0.14: de-duplicate ids (duplicate lazy keys crash) and play by index instead of indexOf.
    val tracks = remember(tracks) { tracks.distinctBy { it.id } }
    LazyRow(Modifier.staggerIn(index), contentPadding = PaddingValues(horizontal = d.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            Column(Modifier.width(w).pressable(onClickLabel = "Play ${t.title}") { Graph.player.play(tracks, i) }) {
                Artwork(t.artUrl, Modifier.size(w).softShadow(Ms.r16), Ms.r16, t.title)
                Spacer(Modifier.height(6.dp))
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun CollectionShelf(cols: List<Collection>, w: Dp, d: HomeDims, index: Int) {
    val nav = LocalNav.current
    // v2.0.14: a shelf can list the same album/playlist twice; duplicate lazy keys crash.
    val cols = remember(cols) { cols.distinctBy { it.id } }
    LazyRow(Modifier.staggerIn(index), contentPadding = PaddingValues(horizontal = d.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cols, key = { it.id }) { c -> CoverCard(c, w) { nav.openCollection(c) } }
    }
}

/** Cover card whose art flies into the album/playlist/artist page header (shared element). */
@Composable
fun CoverCard(c: Collection, w: Dp, onClick: () -> Unit) {
    val round = c.kind == Collection.Kind.ARTIST
    Column(Modifier.width(w).pressable(onClickLabel = "Open ${c.title}", onClick = onClick), horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
        Artwork(c.artUrl, Modifier.size(w).softShadow(if (round) CircleShape else Ms.r16).sharedCover("cover:${c.id}"), if (round) CircleShape else Ms.r16, c.title)
        Spacer(Modifier.height(6.dp))
        Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else TextAlign.Start)
        c.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
internal fun ArtistRow(artists: List<Collection>, d: HomeDims, index: Int) {
    val nav = LocalNav.current
    val size = if (d.wide) 112.dp else 92.dp
    val artists = remember(artists) { artists.distinctBy { it.id } } // v2.0.14: guard duplicate lazy keys
    LazyRow(Modifier.staggerIn(index), contentPadding = PaddingValues(horizontal = d.gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(artists, key = { it.id }) { a ->
            Column(Modifier.width(size).pressable(onClickLabel = "Open ${a.title}") { nav.push(Route.Artist(a.id, a.title)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(a.artUrl, Modifier.size(size).sharedCover("cover:${a.id}"), CircleShape, a.title)
                Spacer(Modifier.height(6.dp))
                Text(a.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun HomeSkeleton(d: HomeDims) {
    Column(Modifier.fillMaxWidth().padding(horizontal = d.gutter)) {
        repeat(3) {
            Spacer(Modifier.height(18.dp))
            Box(Modifier.width(160.dp).height(20.dp).shimmer(Ms.r8))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(if (d.wide) 6 else 3) { Column { SkeletonBox(d.card, d.card, Ms.r16); Spacer(Modifier.height(6.dp)); Box(Modifier.width(d.card * 0.8f).height(12.dp).shimmer(Ms.r8)) } }
            }
        }
    }
}

/** "See all" for any home shelf: the shelf's real items in a responsive grid. */
@Composable
fun ShelfAllScreen(shelf: Shelf) {
    val nav = LocalNav.current
    // v2.0.14: de-duplicate before keying the grid (duplicate keys crash); play by index.
    val tracks = remember(shelf) { shelf.tracks.distinctBy { it.id } }
    val collections = remember(shelf) { shelf.collections.distinctBy { it.id } }
    Column(Modifier.fillMaxSize()) {
        com.ravi.musicspot.ui.components.GlassTopBar(shelf.title, onBack = { nav.back() })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cols = (maxWidth / 168.dp).toInt().coerceIn(2, 8)
            LazyVerticalGrid(GridCells.Fixed(cols), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomInset.current + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(tracks.size, key = { "t" + tracks[it].id }) { i ->
                    val t = tracks[i]
                    Column(Modifier.pressable(onClickLabel = "Play ${t.title}") { Graph.player.play(tracks, i) }) {
                        Artwork(t.artUrl, Modifier.fillMaxWidth().aspectRatio(1f), Ms.r16, t.title)
                        Spacer(Modifier.height(6.dp))
                        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                items(collections, key = { "c" + it.id }) { c ->
                    Column(Modifier.pressable(onClickLabel = "Open ${c.title}") { nav.openCollection(c) }) {
                        Artwork(c.artUrl, Modifier.fillMaxWidth().aspectRatio(1f).sharedCover("cover:${c.id}"), if (c.kind == Collection.Kind.ARTIST) CircleShape else Ms.r16, c.title)
                        Spacer(Modifier.height(6.dp))
                        Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---- v2.0.4 hero + recently played --------------------------------------------------------------------------------

/** Soft, low-contrast drop shadow (elevation shadow: hardware-rendered on every API level, no blur). */
internal fun Modifier.softShadow(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.shadow(10.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))

/** Large "Continue listening" / featured card on a gradient taken from the artwork (Palette, off the main thread). */
@Composable
private fun HomeHero(t: Track, label: String, d: HomeDims, onPlay: () -> Unit) {
    val ctx = LocalContext.current
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    var seed by remember(t.artUrl) { mutableStateOf<Color?>(null) }
    LaunchedEffect(t.artUrl) { seed = runCatching { com.ravi.musicspot.ui.theme.artSeed(ctx, t.artUrl) }.getOrNull() }
    val still = com.ravi.musicspot.ui.motion.reducedMotion()
    val tint by androidx.compose.animation.animateColorAsState(seed ?: MaterialTheme.colorScheme.primary, if (still) androidx.compose.animation.core.snap() else tween(700), label = "heroTint")
    // Darkened so white text stays readable (≥ 4.5:1) whatever the artwork colour.
    val top = androidx.compose.ui.graphics.lerp(tint, Color.Black, 0.45f)
    val bottom = androidx.compose.ui.graphics.lerp(tint, surface, 0.82f).let { androidx.compose.ui.graphics.lerp(it, Color.Black, 0.35f) }
    val st by Graph.player.state.collectAsState()
    val isCurrent = st.current?.id == t.id
    val art = if (d.wide) 148.dp else 116.dp
    Box(
        Modifier.fillMaxWidth().staggerIn(0).padding(horizontal = d.gutter, vertical = 8.dp)
            .softShadow(Ms.r20).clip(Ms.r20)
            .background(Brush.linearGradient(listOf(top, bottom)))
            .pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.98f) { if (isCurrent) Graph.player.toggle() else onPlay() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(t.artUrl, Modifier.size(art).softShadow(Ms.r16), Ms.r16, t.title)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.78f), maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(t.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp), fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.clip(CircleShape).background(Color.White).padding(start = 10.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.ravi.musicspot.ui.motion.PlayPauseIcon(isCurrent && st.playing, Modifier.size(22.dp), tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isCurrent && st.playing) "Pause" else if (isCurrent) "Resume" else "Play", style = MaterialTheme.typography.labelLarge, color = Color.Black)
                }
            }
        }
    }
}

/** Compact 2- or 3-column grid of recently played songs. */
@Composable
private fun RecentGrid(tracks: List<Track>, d: HomeDims, index: Int) {
    val cols = if (d.wide) 3 else 2
    Column(Modifier.fillMaxWidth().padding(horizontal = d.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tracks.chunked(cols).forEachIndexed { r, row ->
            Row(Modifier.staggerIn(index + r), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    Row(Modifier.weight(1f).height(56.dp).clip(Ms.r12).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.97f) { Graph.player.play(tracks, tracks.indexOf(t)) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Artwork(t.artUrl, Modifier.size(56.dp), androidx.compose.ui.graphics.RectangleShape, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
