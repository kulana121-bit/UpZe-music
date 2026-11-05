@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravi.musicspot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.Tab
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.components.OfflineBanner
import com.ravi.musicspot.ui.motion.PlayPauseIcon
import com.ravi.musicspot.ui.motion.SkeletonBox
import com.ravi.musicspot.ui.motion.chipColors
import com.ravi.musicspot.ui.motion.pressable
import com.ravi.musicspot.ui.motion.staggerIn
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.launch
import java.util.Calendar

private val BentoShape = RoundedCornerShape(22.dp)
private val BentoSmallShape = RoundedCornerShape(16.dp)
private val BentoGap = 10.dp

/**
 * "Bento (grid tiles)" Home dashboard (Settings → Appearance → Home design). Same data, sections and behaviour as
 * [AuraHomeScreen] (cached feed, pull to refresh, endless YT Music pages, offline fallbacks); only the look differs:
 *  - compact greeting row with avatar and a search button;
 *  - a bento block: big "Continue listening" tile beside Liked / Downloads, then History / On device;
 *  - mood chips; "On repeat" as a 2-column bento of song tiles (first one big); quick picks;
 *  - recently played as a 3-column grid of square tiles; shelves with bold headers and round "→" buttons.
 */
@Composable
fun BentoHomeScreen() {
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

    PullToRefreshBox(isRefreshing = refreshing && feed != null, onRefresh = { scope.launch { HomeFeedRepo.refresh() } }, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val d = dims(maxWidth)
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = pagePadding()) {
                item(key = "offline") { OfflineBanner() }
                item(key = "b-header") { BentoTopBar(ytAccount, online, d) }
                val f = feed
                // Continue listening: recent songs first, else quick picks (featured).
                val heroTracks = (if (recent.isNotEmpty()) recent else f?.quickPicks.orEmpty()).take(5)
                item(key = "b-grid") {
                    BentoGrid(heroTracks, if (recent.isNotEmpty()) "Continue listening" else "Featured for you", d, lib.liked.size, downloaded.size, recent.size)
                }
                if (f != null && f.moods.isNotEmpty()) item(key = "b-moods") {
                    BentoMoods(f.moods.map { it.title }, d) { i -> f.moods[i].let { m -> nav.push(Route.Mood(m.title, m.browseId, m.params)) } }
                }

                if (f == null && online && favourites.size < 2) {
                    // v2.0.14: a failed first load (no cache) used to leave the shimmer up forever; show the error with Retry.
                    if (error == null || refreshing) item(key = "b-skeleton") { HomeSkeleton(d) }
                    else item(key = "b-load-error") { EmptyState(Icons.Rounded.CloudOff, "Couldn't load Home", error ?: "Pull down to try again.", "Retry") { scope.launch { HomeFeedRepo.refresh() } } }
                    return@LazyColumn
                }

                var sec = 0
                fun nextSec() = sec++
                if (favourites.size >= 2) {
                    val i = nextSec()
                    item(key = "b-fav-h") { BentoOnRepeatHeader(favourites, i, d) }
                    item(key = "b-fav") { BentoOnRepeat(favourites.take(7), d, i) }
                }
                f?.forYou?.let { bentoShelf("b-foryou", it, d, nextSec()) } // v2.0.15: seed-artist shelf on top
                if (f != null && f.quickPicks.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "b-qp-h") { BentoHeader("Quick picks", "Start radio from a song", i, d) { nav.push(Route.ShelfAll(Shelf("Quick picks", f.quickPicks))) } }
                    item(key = "b-qp") { QuickPicks(f.quickPicks, d, i, rows = 3) }
                }
                if (recent.size > 1) {
                    val i = nextSec()
                    item(key = "b-kl-h") { BentoHeader("Recently played", null, i, d) { nav.push(Route.History) } }
                    item(key = "b-kl") { BentoRecentGrid(recent.drop(1), d, i) }
                }
                if (mixes.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "b-mix-h") { BentoHeader("Your mixes & playlists", null, i, d) { nav.select(Tab.LIBRARY) } }
                    item(key = "b-mix") { CollectionShelf(mixes.distinctBy { it.id }.take(20), d.card * 1.12f, d, i) }
                }
                if (!online && downloaded.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "b-dl-h") { BentoHeader("Your downloads", "Plays offline", i, d) { nav.push(Route.Downloads) } }
                    item(key = "b-dl") { TrackShelf(downloaded.take(20), d.card, d, i) }
                }
                if (f != null) {
                    f.ytShelves.take(3).forEachIndexed { n, s -> bentoShelf("b-yt$n", s, d, nextSec()) }
                    f.charts.firstOrNull()?.let { bentoShelf("b-chart0", it.copy(title = if (it.title.contains("India", true)) it.title else "Trending in India · ${it.title}"), d, nextSec()) }
                    f.jsTrending?.let { bentoShelf("b-jst", it, d, nextSec()) }
                    f.regional.forEachIndexed { n, s -> bentoShelf("b-reg$n", s, d, nextSec()) }
                    f.newReleases.forEachIndexed { n, s -> bentoShelf("b-nr$n", s.copy(title = if (n == 0 && !s.title.contains("new", true)) "New releases" else s.title), d, nextSec()) }
                    f.charts.drop(1).forEachIndexed { n, s -> bentoShelf("b-chart${n + 1}", s, d, nextSec()) }
                    if (artists.isNotEmpty()) {
                        val i = nextSec()
                        item(key = "b-art-h") { BentoHeader("Artists you like", null, i, d) }
                        item(key = "b-art") { ArtistRow(artists, d, i) }
                    }
                    f.jsPlaylists?.let { bentoShelf("b-jsp", it, d, nextSec()) }
                    f.ytShelves.drop(3).forEachIndexed { n, s -> bentoShelf("b-ytm$n", s, d, nextSec()) }
                    if (loadingMore) item(key = "b-more") { Row(Modifier.fillMaxWidth().padding(16.dp)) { SkeletonBox(d.card, d.card) } }
                    if (f.isEmpty) item(key = "b-empty") {
                        if (SourceKind.YOUTUBE !in settings.enabledSources && SourceKind.JIOSAAVN !in settings.enabledSources)
                            EmptyState(Icons.Rounded.PhoneAndroid, "Online sources are off", "Home comes from YouTube Music and JioSaavn. Turn one on in Settings → Sources.", "Open Sources") { nav.select(Tab.SETTINGS); nav.push(Route.SettingsPage("sources")) }
                        else EmptyState(Icons.Rounded.Download, "Couldn't load Home", error ?: "Pull down to try again.", "Retry") { scope.launch { HomeFeedRepo.refresh() } }
                    }
                }
                if (!online && f == null) item(key = "b-offline-empty") {
                    EmptyState(Icons.Rounded.CloudOff, "You're offline", "Downloads and music on this device still play.", "Downloads") { nav.push(Route.Downloads) }
                }
                item(key = "b-end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun LazyListScope.bentoShelf(key: String, s: FeedShelf, d: HomeDims, index: Int) {
    if (s.isEmpty) return
    item(key = "$key-h") {
        val nav = LocalNav.current
        BentoHeader(s.title, s.subtitle, index, d) { nav.push(Route.ShelfAll(s.toShelf())) }
    }
    if (s.tracks.isNotEmpty()) item(key = "$key-t") {
        if (s.tracks.size >= 8 && s.collections.isEmpty()) QuickPicks(s.tracks, d, index, rows = 3) else TrackShelf(s.tracks, d.card, d, index)
    }
    if (s.collections.isNotEmpty()) item(key = "$key-c") { CollectionShelf(s.collections, d.card, d, index) }
}

// ---- pieces -------------------------------------------------------------------------------------------------------

@Composable
private fun BentoTopBar(acc: Account?, online: Boolean, d: HomeDims) {
    val nav = LocalNav.current
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late night vibes" }
    val first = acc?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = d.gutter, end = d.gutter, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(BentoSmallShape).background(c.primaryContainer)
            .pressable(onClickLabel = "Accounts", pressedScale = 0.9f) { nav.select(Tab.SETTINGS); nav.push(Route.SettingsPage("accounts")) },
            contentAlignment = Alignment.Center) {
            if (acc?.avatarUrl != null) Artwork(acc.avatarUrl, Modifier.fillMaxSize(), BentoSmallShape, "Your account")
            else Text((first ?: "M").take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = c.onPrimaryContainer, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(greeting + (first?.let { ", $it" } ?: ""), style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.3).sp),
                fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (online) "Your music, at a glance" else "Offline · showing what's saved", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(44.dp).clip(BentoSmallShape).background(c.surfaceContainerHigh)
            .pressable(onClickLabel = "Search", pressedScale = 0.9f) { nav.select(Tab.SEARCH) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Search, "Search", Modifier.size(22.dp), tint = c.onSurface)
        }
    }
}

/** The bento block: a big Continue-listening tile beside Liked + Downloads, then History + On device. */
@Composable
private fun BentoGrid(heroTracks: List<Track>, label: String, d: HomeDims, liked: Int, downloads: Int, history: Int) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxWidth().staggerIn(0).padding(horizontal = d.gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(BentoGap)) {
        Row(Modifier.fillMaxWidth().aspectRatio(if (d.wide) 2.6f else 2f), horizontalArrangement = Arrangement.spacedBy(BentoGap)) {
            BentoContinueTile(heroTracks, label, Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(BentoGap)) {
                BentoStatTile(Icons.Rounded.Favorite, "Liked songs", "$liked songs", Color(0xFF7B5CFF), Color(0xFFE05CB0), Modifier.weight(1f).fillMaxWidth()) { nav.push(Route.Liked) }
                BentoStatTile(Icons.Rounded.Download, "Downloads", "$downloads songs", Color(0xFF14A37F), Color(0xFF2E7DD7), Modifier.weight(1f).fillMaxWidth()) { nav.push(Route.Downloads) }
            }
        }
        Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(BentoGap)) {
            BentoStatTile(Icons.Rounded.History, "History", "$history songs", Color(0xFFE5872E), Color(0xFFD9483B), Modifier.weight(1f).fillMaxHeight()) { nav.push(Route.History) }
            BentoStatTile(Icons.Rounded.PhoneAndroid, "On device", "Local files", Color(0xFF3A6BD6), Color(0xFF26A6C9), Modifier.weight(1f).fillMaxHeight()) { nav.push(Route.DeviceMusic) }
        }
    }
}

/** Big tile: artwork fills it, a dark gradient keeps the text readable, round play button bottom-right. */
@Composable
private fun BentoContinueTile(tracks: List<Track>, label: String, modifier: Modifier) {
    val nav = LocalNav.current
    val c = MaterialTheme.colorScheme
    val t = tracks.firstOrNull()
    if (t == null) {
        // Nothing played yet and no feed: invite a search instead of an empty tile.
        Box(modifier.clip(BentoShape).background(Brush.linearGradient(listOf(c.primaryContainer, c.tertiaryContainer)))
            .pressable(onClickLabel = "Search for music", pressedScale = 0.97f) { nav.select(Tab.SEARCH) }.padding(14.dp)) {
            Text("START LISTENING", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp), color = c.onPrimaryContainer, fontWeight = FontWeight.Bold)
            Column(Modifier.align(Alignment.BottomStart)) {
                Icon(Icons.Rounded.Search, null, Modifier.size(28.dp), tint = c.onPrimaryContainer)
                Spacer(Modifier.height(6.dp))
                Text("Find a song", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = c.onPrimaryContainer, maxLines = 1)
            }
        }
        return
    }
    val st by Graph.player.state.collectAsState()
    val isCurrent = st.current?.id == t.id
    Box(modifier.clip(BentoShape).background(c.surfaceContainerHigh)
        .pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.97f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { if (isCurrent) Graph.player.toggle() else Graph.player.play(tracks, 0) }) {
        Artwork(t.artUrl, Modifier.fillMaxSize(), RectangleShape, t.title)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp), color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.TopStart).padding(14.dp))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium.copy(letterSpacing = (-0.2).sp), fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White)
                .pressable(onClickLabel = if (isCurrent && st.playing) "Pause" else "Play ${t.title}", pressedScale = 0.88f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { if (isCurrent) Graph.player.toggle() else Graph.player.play(tracks, 0) },
                contentAlignment = Alignment.Center) {
                PlayPauseIcon(isCurrent && st.playing, Modifier.size(22.dp), tint = Color.Black)
            }
        }
    }
}

@Composable
private fun BentoStatTile(icon: ImageVector, label: String, detail: String, a: Color, b: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(BentoShape).background(Brush.linearGradient(listOf(a, b))).pressable(onClickLabel = label, pressedScale = 0.96f, onClick = onClick)) {
        Icon(icon, null, Modifier.size(70.dp).align(Alignment.BottomEnd).offset(x = 14.dp, y = 16.dp).graphicsLayer { rotationZ = -14f }, tint = Color.White.copy(alpha = 0.2f))
        Box(Modifier.align(Alignment.TopStart).padding(12.dp).size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(16.dp), tint = Color.White)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
        }
    }
}

@Composable
private fun BentoMoods(titles: List<String>, d: HomeDims, onSelect: (Int) -> Unit) {
    var selected by remember { mutableStateOf(-1) }
    LazyRow(contentPadding = PaddingValues(horizontal = d.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(titles) { i, t ->
            val (bg, fg) = chipColors(selected == i)
            Box(Modifier.height(40.dp).clip(Ms.r12).background(bg).pressable(onClickLabel = t, pressedScale = 0.92f) { selected = i; onSelect(i) }
                .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(t, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
            }
        }
    }
}

/** Bold section title with a round "→" button. */
@Composable
private fun BentoHeader(title: String, subtitle: String?, index: Int, d: HomeDims, onAll: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().staggerIn(index).padding(start = d.gutter, end = d.gutter, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, letterSpacing = (-0.4).sp), fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (onAll != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.surfaceContainerHigh).pressable(onClickLabel = "See all $title", pressedScale = 0.9f, onClick = onAll),
                contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "See all $title", Modifier.size(18.dp), tint = c.onSurface) }
        }
    }
}

@Composable
private fun BentoOnRepeatHeader(tracks: List<Track>, index: Int, d: HomeDims) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().staggerIn(index).padding(start = d.gutter, end = d.gutter, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("On repeat", style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, letterSpacing = (-0.4).sp), fontWeight = FontWeight.Black, maxLines = 1)
            Text("Your top songs right now", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
        }
        Box(Modifier.size(38.dp).clip(CircleShape).background(c.surfaceContainerHigh).pressable(onClickLabel = "Shuffle On repeat", pressedScale = 0.9f) { Graph.player.play(tracks, 0, shuffle = true) },
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Shuffle, "Shuffle On repeat", Modifier.size(18.dp), tint = c.onSurface) }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(42.dp).clip(CircleShape).background(c.primary).pressable(onClickLabel = "Play all On repeat", pressedScale = 0.9f) { Graph.player.play(tracks, 0) },
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "Play all", tint = c.onPrimary) }
    }
}

/** 2-column bento of song tiles: #1 is a big tile beside #2/#3 stacked, the rest follow in pairs. */
@Composable
private fun BentoOnRepeat(tracks: List<Track>, d: HomeDims, index: Int) {
    val bigH: Dp = if (d.wide) 300.dp else 240.dp
    val smallH: Dp = (bigH - BentoGap) / 2
    if (tracks.isEmpty()) return // v2.0.14: BentoSongTile(tracks, 0) below would throw on an empty list
    Column(Modifier.fillMaxWidth().staggerIn(index).padding(horizontal = d.gutter), verticalArrangement = Arrangement.spacedBy(BentoGap)) {
        Row(Modifier.fillMaxWidth().height(bigH), horizontalArrangement = Arrangement.spacedBy(BentoGap)) {
            BentoSongTile(tracks, 0, Modifier.weight(1f).fillMaxHeight(), big = true)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(BentoGap)) {
                if (tracks.size > 1) BentoSongTile(tracks, 1, Modifier.weight(1f).fillMaxWidth()) else Spacer(Modifier.weight(1f))
                if (tracks.size > 2) BentoSongTile(tracks, 2, Modifier.weight(1f).fillMaxWidth()) else Spacer(Modifier.weight(1f))
            }
        }
        tracks.drop(3).chunked(2).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().height(smallH), horizontalArrangement = Arrangement.spacedBy(BentoGap)) {
                row.forEachIndexed { j, _ -> BentoSongTile(tracks, 3 + r * 2 + j, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BentoSongTile(tracks: List<Track>, i: Int, modifier: Modifier, big: Boolean = false) {
    val t = tracks[i]
    val c = MaterialTheme.colorScheme
    Box(modifier.clip(if (big) BentoShape else BentoSmallShape).background(c.surfaceContainerHigh)
        .pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.97f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { Graph.player.play(tracks, i) }) {
        Artwork(t.artUrl, Modifier.fillMaxSize(), RectangleShape, t.title)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.82f)))))
        Box(Modifier.align(Alignment.TopStart).padding(10.dp).clip(Ms.pill).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text("#${i + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(if (big) 14.dp else 10.dp)) {
            Text(t.title, style = if (big) MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.3).sp) else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = if (big) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (big) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(38.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(22.dp), tint = Color.Black)
        }
    }
}

/** Recently played as a 3-column (5 on wide screens) grid of small square tiles. */
@Composable
private fun BentoRecentGrid(tracks: List<Track>, d: HomeDims, index: Int) {
    val cols = if (d.wide) 5 else 3
    val shown = tracks.take(cols * 3)
    Column(Modifier.fillMaxWidth().padding(horizontal = d.gutter), verticalArrangement = Arrangement.spacedBy(BentoGap)) {
        shown.chunked(cols).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().staggerIn(index + r), horizontalArrangement = Arrangement.spacedBy(BentoGap)) {
                row.forEachIndexed { j, t ->
                    val i = r * cols + j
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(BentoSmallShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.95f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { Graph.player.play(tracks, i) }) {
                        Artwork(t.artUrl, Modifier.fillMaxSize(), RectangleShape, t.title)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))))
                        Text(t.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 7.dp))
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
