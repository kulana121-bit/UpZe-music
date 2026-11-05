@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravi.musicspot.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.ravi.musicspot.ui.glass.liquidGlass
import com.ravi.musicspot.ui.motion.PlayPauseIcon
import com.ravi.musicspot.ui.motion.SkeletonBox
import com.ravi.musicspot.ui.motion.pressable
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.staggerIn
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * "Spotlight (big artwork)" Home dashboard (Settings → Appearance → Home design). Same data, sections and behaviour as
 * [AuraHomeScreen] (cached feed, pull to refresh, endless YT Music pages, offline fallbacks); only the look differs:
 *  - a full-bleed spotlight with the current / recent / featured song's artwork edge to edge, fading into the page,
 *    greeting + big title + artist + Play and Shuffle over it (blur only on API 31+);
 *  - round shortcut bubbles (Liked, Downloads, History, On device, Search); outlined mood chips;
 *  - "On repeat" as a numbered top-5 list with Play all; recently played as wide cards;
 *  - shelves with large headers and a subtitle line.
 */
@Composable
fun SpotlightHomeScreen() {
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
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } == true } }
    var loadingMore by remember { mutableStateOf(false) }
    LaunchedEffect(nearEnd, feed?.ytContinuation) {
        if (nearEnd && !loadingMore && feed?.ytContinuation != null && online) { loadingMore = true; runCatching { HomeFeedRepo.more() }; loadingMore = false }
    }

    // Full bleed: the spotlight draws under the status bar, so the list keeps every page inset except the top one.
    val pp = pagePadding()
    val dir = LocalLayoutDirection.current
    val padding = PaddingValues(start = pp.calculateStartPadding(dir), end = pp.calculateEndPadding(dir), top = 0.dp, bottom = pp.calculateBottomPadding())

    PullToRefreshBox(isRefreshing = refreshing && feed != null, onRefresh = { scope.launch { HomeFeedRepo.refresh() } }, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val d = dims(maxWidth)
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = padding) {
                item(key = "offline") { if (!online) Box(Modifier.statusBarsPadding()) { OfflineBanner() } }
                val f = feed
                // Spotlight: recent songs first (continue listening), else quick picks (featured); the playing song wins.
                val heroTracks = (if (recent.isNotEmpty()) recent else f?.quickPicks.orEmpty()).take(5)
                item(key = "s-spotlight") {
                    SpotlightTop(ytAccount, online, heroTracks, if (recent.isNotEmpty()) "Continue listening" else "Featured for you", d)
                }
                item(key = "s-bubbles") { SpotlightBubbles(d, lib.liked.size, downloaded.size, recent.size) }
                if (f != null && f.moods.isNotEmpty()) item(key = "s-moods") {
                    SpotlightMoods(f.moods.map { it.title }, d) { i -> f.moods[i].let { m -> nav.push(Route.Mood(m.title, m.browseId, m.params)) } }
                }

                if (f == null && online && favourites.size < 2) {
                    // v2.0.14: a failed first load (no cache) used to leave the shimmer up forever; show the error with Retry.
                    if (error == null || refreshing) item(key = "s-skeleton") { HomeSkeleton(d) }
                    else item(key = "s-load-error") { EmptyState(Icons.Rounded.CloudOff, "Couldn't load Home", error ?: "Pull down to try again.", "Retry") { scope.launch { HomeFeedRepo.refresh() } } }
                    return@LazyColumn
                }

                var sec = 0
                fun nextSec() = sec++
                if (favourites.size >= 2) {
                    val i = nextSec()
                    item(key = "s-fav-h") { SpotlightOnRepeatHeader(favourites, i, d) }
                    item(key = "s-fav") { SpotlightTopList(favourites.take(5), d, i) }
                }
                f?.forYou?.let { spotlightShelf("s-foryou", it, d, nextSec()) } // v2.0.15: seed-artist shelf on top
                if (f != null && f.quickPicks.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "s-qp-h") { SpotlightHeader("Quick picks", "Start radio from a song", i, d) { nav.push(Route.ShelfAll(Shelf("Quick picks", f.quickPicks))) } }
                    item(key = "s-qp") { QuickPicks(f.quickPicks, d, i, rows = 3) }
                }
                if (recent.size > 1) {
                    val i = nextSec()
                    item(key = "s-kl-h") { SpotlightHeader("Recently played", "Pick up where you left off", i, d) { nav.push(Route.History) } }
                    item(key = "s-kl") { SpotlightRecentCards(recent.drop(1), d, i) }
                }
                if (mixes.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "s-mix-h") { SpotlightHeader("Your mixes & playlists", "From your library", i, d) { nav.select(Tab.LIBRARY) } }
                    item(key = "s-mix") { CollectionShelf(mixes.distinctBy { it.id }.take(20), d.card * 1.12f, d, i) }
                }
                if (!online && downloaded.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "s-dl-h") { SpotlightHeader("Your downloads", "Plays offline", i, d) { nav.push(Route.Downloads) } }
                    item(key = "s-dl") { TrackShelf(downloaded.take(20), d.card, d, i) }
                }
                if (f != null) {
                    f.ytShelves.take(3).forEachIndexed { n, s -> spotlightShelf("s-yt$n", s, d, nextSec()) }
                    f.charts.firstOrNull()?.let { spotlightShelf("s-chart0", it.copy(title = if (it.title.contains("India", true)) it.title else "Trending in India · ${it.title}"), d, nextSec()) }
                    f.jsTrending?.let { spotlightShelf("s-jst", it, d, nextSec()) }
                    f.regional.forEachIndexed { n, s -> spotlightShelf("s-reg$n", s, d, nextSec()) }
                    f.newReleases.forEachIndexed { n, s -> spotlightShelf("s-nr$n", s.copy(title = if (n == 0 && !s.title.contains("new", true)) "New releases" else s.title), d, nextSec()) }
                    f.charts.drop(1).forEachIndexed { n, s -> spotlightShelf("s-chart${n + 1}", s, d, nextSec()) }
                    if (artists.isNotEmpty()) {
                        val i = nextSec()
                        item(key = "s-art-h") { SpotlightHeader("Artists you like", "Based on what you play", i, d) }
                        item(key = "s-art") { ArtistRow(artists, d, i) }
                    }
                    f.jsPlaylists?.let { spotlightShelf("s-jsp", it, d, nextSec()) }
                    f.ytShelves.drop(3).forEachIndexed { n, s -> spotlightShelf("s-ytm$n", s, d, nextSec()) }
                    if (loadingMore) item(key = "s-more") { Row(Modifier.fillMaxWidth().padding(16.dp)) { SkeletonBox(d.card, d.card) } }
                    if (f.isEmpty) item(key = "s-empty") {
                        if (SourceKind.YOUTUBE !in settings.enabledSources && SourceKind.JIOSAAVN !in settings.enabledSources)
                            EmptyState(Icons.Rounded.PhoneAndroid, "Online sources are off", "Home comes from YouTube Music and JioSaavn. Turn one on in Settings → Sources.", "Open Sources") { nav.select(Tab.SETTINGS); nav.push(Route.SettingsPage("sources")) }
                        else EmptyState(Icons.Rounded.Download, "Couldn't load Home", error ?: "Pull down to try again.", "Retry") { scope.launch { HomeFeedRepo.refresh() } }
                    }
                }
                if (!online && f == null) item(key = "s-offline-empty") {
                    EmptyState(Icons.Rounded.CloudOff, "You're offline", "Downloads and music on this device still play.", "Downloads") { nav.push(Route.Downloads) }
                }
                item(key = "s-end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun LazyListScope.spotlightShelf(key: String, s: FeedShelf, d: HomeDims, index: Int) {
    if (s.isEmpty) return
    item(key = "$key-h") {
        val nav = LocalNav.current
        val fallback = if (s.tracks.isNotEmpty()) "${s.tracks.size} songs" else "${s.collections.size} albums & playlists"
        SpotlightHeader(s.title, s.subtitle?.takeIf { it.isNotBlank() } ?: fallback, index, d) { nav.push(Route.ShelfAll(s.toShelf())) }
    }
    if (s.tracks.isNotEmpty()) item(key = "$key-t") {
        if (s.tracks.size >= 8 && s.collections.isEmpty()) QuickPicks(s.tracks, d, index, rows = 3) else TrackShelf(s.tracks, d.card, d, index)
    }
    if (s.collections.isNotEmpty()) item(key = "$key-c") { CollectionShelf(s.collections, d.card, d, index) }
}

// ---- pieces -------------------------------------------------------------------------------------------------------

/**
 * Edge-to-edge artwork of the playing song (else the first recent / featured one) fading into the page background.
 * API 31+: a blurred copy sits underneath and the sharp art fades into it; older devices get a plain tinted fade.
 */
@Composable
private fun SpotlightTop(acc: Account?, online: Boolean, heroTracks: List<Track>, label: String, d: HomeDims) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val st by Graph.player.state.collectAsState()
    val current = st.current
    val t = current ?: heroTracks.firstOrNull()
    val isCurrent = t != null && current?.id == t.id
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late night vibes" }
    val first = acc?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
    val tag = when { isCurrent && st.playing -> "Now playing"; isCurrent -> "Paused"; else -> label }

    var seed by remember(t?.artUrl) { mutableStateOf<Color?>(null) }
    LaunchedEffect(t?.artUrl) { seed = runCatching { com.ravi.musicspot.ui.theme.artSeed(ctx, t?.artUrl) }.getOrNull() }
    val still = reducedMotion()
    val tint by animateColorAsState(seed ?: c.primary, if (still) snap() else tween(700), label = "spotlightTint")
    val bg = c.background
    val blurOk = android.os.Build.VERSION.SDK_INT >= 31
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val h = (if (d.wide) 400.dp else 360.dp) + top

    fun playMain() {
        when {
            isCurrent -> Graph.player.toggle()
            heroTracks.isNotEmpty() -> Graph.player.play(heroTracks, 0)
        }
    }

    Box(Modifier.fillMaxWidth().height(h).background(lerp(tint, bg, 0.55f))) {
        if (t != null) {
            if (blurOk) Artwork(t.artUrl, Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.7f; scaleX = 1.25f; scaleY = 1.25f }, RectangleShape)
            Artwork(
                t.artUrl,
                Modifier.fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    },
                RectangleShape, t.title,
            )
        }
        // Dark top for the status bar + greeting, fading into the page background at the bottom.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.6f), 0.28f to Color.Black.copy(alpha = 0.12f), 0.55f to bg.copy(alpha = 0.35f), 0.82f to bg.copy(alpha = 0.88f), 1f to bg,
        )))

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = d.gutter + 4.dp, end = d.gutter, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (online) "UPZE" else "OFFLINE · SAVED MUSIC", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                    color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold, maxLines = 1)
                Text(greeting + (first?.let { ", $it" } ?: ""), style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.3).sp),
                    fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(42.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape).padding(3.dp).clip(CircleShape).background(c.primaryContainer)
                .pressable(onClickLabel = "Accounts", pressedScale = 0.9f) { nav.select(Tab.SETTINGS); nav.push(Route.SettingsPage("accounts")) },
                contentAlignment = Alignment.Center) {
                if (acc?.avatarUrl != null) Artwork(acc.avatarUrl, Modifier.fillMaxSize(), CircleShape, "Your account")
                else Text((first ?: "M").take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = c.onPrimaryContainer)
            }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = d.gutter + 4.dp, end = d.gutter, bottom = 8.dp)) {
            if (t != null) {
                Text(tag.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp), color = c.primary, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(t.title, style = MaterialTheme.typography.displaySmall.copy(fontSize = if (d.wide) 40.sp else 32.sp, letterSpacing = (-1).sp, lineHeight = if (d.wide) 44.sp else 36.sp),
                    fontWeight = FontWeight.Black, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.height(48.dp).clip(Ms.pill).background(c.primary)
                        .pressable(onClickLabel = if (isCurrent && st.playing) "Pause" else "Play ${t.title}", pressedScale = 0.94f) { playMain() }
                        .padding(start = 16.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayPauseIcon(isCurrent && st.playing, Modifier.size(24.dp), tint = c.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isCurrent && st.playing) "Pause" else if (isCurrent) "Resume" else "Play", style = MaterialTheme.typography.titleSmall, color = c.onPrimary, fontWeight = FontWeight.Bold)
                    }
                    if (heroTracks.size > 1) {
                        Spacer(Modifier.width(10.dp))
                        Row(Modifier.height(48.dp).liquidGlass(Ms.pill)
                            .pressable(onClickLabel = "Shuffle ${label.lowercase()}", pressedScale = 0.94f) { Graph.player.play(heroTracks, 0, shuffle = true) }
                            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Shuffle, null, Modifier.size(20.dp), tint = c.onSurface)
                            Spacer(Modifier.width(8.dp))
                            Text("Shuffle", style = MaterialTheme.typography.titleSmall, color = c.onSurface, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                Text("Find something to play", style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp, letterSpacing = (-1).sp),
                    fontWeight = FontWeight.Black, color = c.onSurface, maxLines = 2)
                Text("Search songs, artists and albums", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Round shortcut bubbles with a label and count underneath. */
@Composable
private fun SpotlightBubbles(d: HomeDims, liked: Int, downloads: Int, history: Int) {
    val nav = LocalNav.current
    data class B(val icon: ImageVector, val label: String, val detail: String, val a: Color, val b: Color, val go: () -> Unit)
    val bubbles = listOf(
        B(Icons.Rounded.Favorite, "Liked", "$liked songs", Color(0xFF7B5CFF), Color(0xFFE05CB0)) { nav.push(Route.Liked) },
        B(Icons.Rounded.Download, "Downloads", "$downloads songs", Color(0xFF14A37F), Color(0xFF2E7DD7)) { nav.push(Route.Downloads) },
        B(Icons.Rounded.History, "History", "$history songs", Color(0xFFE5872E), Color(0xFFD9483B)) { nav.push(Route.History) },
        B(Icons.Rounded.PhoneAndroid, "On device", "Local files", Color(0xFF3A6BD6), Color(0xFF26A6C9)) { nav.push(Route.DeviceMusic) },
        B(Icons.Rounded.Search, "Search", "Find music", Color(0xFF5B5F6B), Color(0xFF2E3038)) { nav.select(Tab.SEARCH) },
    )
    val size = if (d.wide) 72.dp else 64.dp
    LazyRow(Modifier.staggerIn(0).padding(top = 8.dp), contentPadding = PaddingValues(horizontal = d.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(bubbles, key = { "bubble-" + it.label }) { b ->
            Column(Modifier.width(size + 12.dp).pressable(onClickLabel = b.label, pressedScale = 0.92f, onClick = b.go), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(b.a, b.b))), contentAlignment = Alignment.Center) {
                    Icon(b.icon, null, Modifier.size(26.dp), tint = Color.White)
                }
                Spacer(Modifier.height(6.dp))
                Text(b.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(b.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SpotlightMoods(titles: List<String>, d: HomeDims, onSelect: (Int) -> Unit) {
    var selected by remember { mutableStateOf(-1) }
    val c = MaterialTheme.colorScheme
    LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = d.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(titles) { i, t ->
            val on = selected == i
            Box(Modifier.height(38.dp).clip(Ms.pill).background(if (on) c.primary else Color.Transparent)
                .border(1.dp, if (on) c.primary else c.outlineVariant, Ms.pill)
                .pressable(onClickLabel = t, pressedScale = 0.92f) { selected = i; onSelect(i) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(t, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) c.onPrimary else c.onSurface, maxLines = 1)
            }
        }
    }
}

/** Large section header with a subtitle line and a "See all" pill. */
@Composable
private fun SpotlightHeader(title: String, subtitle: String?, index: Int, d: HomeDims, onAll: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().staggerIn(index).padding(start = d.gutter + 4.dp, end = d.gutter, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 25.sp, letterSpacing = (-0.6).sp), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (onAll != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.height(32.dp).clip(Ms.pill).background(c.surfaceContainerHigh).pressable(onClickLabel = "See all $title", pressedScale = 0.92f, onClick = onAll)
                .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text("See all", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            }
        }
    }
}

@Composable
private fun SpotlightOnRepeatHeader(tracks: List<Track>, index: Int, d: HomeDims) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().staggerIn(index).padding(start = d.gutter + 4.dp, end = d.gutter, top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("On repeat", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 25.sp, letterSpacing = (-0.6).sp), fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text("Your top songs right now", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = { Graph.player.play(tracks, 0, shuffle = true) }) { Icon(Icons.Rounded.Shuffle, "Shuffle On repeat") }
        Row(Modifier.height(40.dp).clip(Ms.pill).background(c.primary).pressable(onClickLabel = "Play all On repeat", pressedScale = 0.92f) { Graph.player.play(tracks, 0) }
            .padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp), tint = c.onPrimary)
            Spacer(Modifier.width(4.dp))
            Text("Play all", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = c.onPrimary)
        }
    }
}

/** Numbered vertical top list (rank, cover, title / artist, more). The playing song is highlighted. */
@Composable
private fun SpotlightTopList(tracks: List<Track>, d: HomeDims, index: Int) {
    val c = MaterialTheme.colorScheme
    val st by Graph.player.state.collectAsState()
    Column(Modifier.fillMaxWidth().staggerIn(index).padding(horizontal = d.gutter - 4.dp)) {
        tracks.forEachIndexed { i, t ->
            val playing = st.current?.id == t.id
            Row(Modifier.fillMaxWidth().height(66.dp).clip(Ms.r12).background(if (playing) c.primary.copy(alpha = 0.12f) else Color.Transparent)
                .pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.98f) { if (playing) Graph.player.toggle() else Graph.player.play(tracks, i) }
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), fontWeight = FontWeight.Black,
                    color = if (i == 0 || playing) c.primary else c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(30.dp))
                Spacer(Modifier.width(8.dp))
                Artwork(t.artUrl, Modifier.size(50.dp), Ms.r8, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (playing) c.primary else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { Menus.track = t }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreVert, "More options for ${t.title}", Modifier.size(20.dp)) }
            }
        }
    }
}

/** Recently played as wide horizontal cards: cover on the left, text, round play button. */
@Composable
private fun SpotlightRecentCards(tracks: List<Track>, d: HomeDims, index: Int) {
    val c = MaterialTheme.colorScheme
    val w = if (d.wide) 320.dp else 272.dp
    val h = 84.dp
    LazyRow(Modifier.staggerIn(index), contentPadding = PaddingValues(horizontal = d.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(tracks.take(20), key = { _, t -> "srp" + t.id }) { i, t ->
            Row(Modifier.width(w).height(h).clip(Ms.r20).background(c.surfaceContainerHigh)
                .pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.97f) { Graph.player.play(tracks, i) }, verticalAlignment = Alignment.CenterVertically) {
                Artwork(t.artUrl, Modifier.size(h), RectangleShape, t.title)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.padding(end = 12.dp).size(36.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp), tint = c.onPrimary)
                }
            }
        }
    }
}
