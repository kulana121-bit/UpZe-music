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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.ravi.musicspot.ui.motion.SkeletonBox
import com.ravi.musicspot.ui.motion.pressable
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.staggerIn
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.launch
import java.util.Calendar

private val CARD = RoundedCornerShape(26.dp)

/**
 * v2.0.12 "Aura" Home dashboard (Settings → Appearance → Home design). Same data and sections as the classic Home
 * (cached feed, pull to refresh, endless YT Music pages, offline fallbacks), in a new layout:
 *  - big greeting with a liquid-glass search pill;
 *  - a swipeable hero carousel (continue listening / featured) on a blurred artwork backdrop;
 *  - glass mood chips; four colour tiles (Liked, Downloads, History, On device) with live counts;
 *  - "On repeat" as a Top 10 row with big rank numbers; quick picks; recently played as square cards;
 *  - every feed shelf with an accent-bar header.
 */
@Composable
fun AuraHomeScreen() {
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
                item(key = "a-header") { AuraHomeHeader(ytAccount, online, d) }
                val f = feed
                // Hero carousel: recent songs first (continue listening), else quick picks (featured).
                val heroTracks = (if (recent.isNotEmpty()) recent else f?.quickPicks.orEmpty()).take(5)
                if (heroTracks.isNotEmpty()) item(key = "a-hero") {
                    AuraHero(heroTracks, if (recent.isNotEmpty()) "Continue listening" else "Featured for you", d)
                }
                if (f != null && f.moods.isNotEmpty()) item(key = "a-moods") {
                    AuraMoods(f.moods.map { it.title }, d) { i -> f.moods[i].let { m -> nav.push(Route.Mood(m.title, m.browseId, m.params)) } }
                }
                item(key = "a-tiles") { AuraTiles(d, lib.liked.size, downloaded.size, recent.size) }

                if (f == null && online && favourites.size < 2) {
                    // v2.0.14: a failed first load (no cache) used to leave the shimmer up forever; show the error with Retry.
                    if (error == null || refreshing) item(key = "a-skeleton") { HomeSkeleton(d) }
                    else item(key = "a-load-error") { EmptyState(Icons.Rounded.CloudOff, "Couldn't load Home", error ?: "Pull down to try again.", "Retry") { scope.launch { HomeFeedRepo.refresh() } } }
                    return@LazyColumn
                }

                var sec = 0
                fun nextSec() = sec++
                if (favourites.size >= 2) {
                    val i = nextSec()
                    item(key = "a-fav-h") { AuraOnRepeatHeader(favourites, i, d) }
                    item(key = "a-fav") { AuraTopTen(favourites.take(10), d, i) }
                }
                f?.forYou?.let { auraShelf("a-foryou", it, d, nextSec()) } // v2.0.15: seed-artist shelf on top
                if (f != null && f.quickPicks.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "a-qp-h") { AuraHeader("Quick picks", "Start radio from a song", i, d) { nav.push(Route.ShelfAll(Shelf("Quick picks", f.quickPicks))) } }
                    item(key = "a-qp") { QuickPicks(f.quickPicks, d, i, rows = 3) }
                }
                if (recent.size > 1) {
                    val i = nextSec()
                    item(key = "a-kl-h") { AuraHeader("Recently played", null, i, d) { nav.push(Route.History) } }
                    item(key = "a-kl") { AuraRecentRow(recent.drop(1), d, i) }
                }
                if (mixes.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "a-mix-h") { AuraHeader("Your mixes & playlists", null, i, d) { nav.select(Tab.LIBRARY) } }
                    item(key = "a-mix") { CollectionShelf(mixes.distinctBy { it.id }.take(20), d.card * 1.12f, d, i) }
                }
                if (!online && downloaded.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "a-dl-h") { AuraHeader("Your downloads", "Plays offline", i, d) { nav.push(Route.Downloads) } }
                    item(key = "a-dl") { TrackShelf(downloaded.take(20), d.card, d, i) }
                }
                if (f != null) {
                    f.ytShelves.take(3).forEachIndexed { n, s -> auraShelf("a-yt$n", s, d, nextSec()) }
                    f.charts.firstOrNull()?.let { auraShelf("a-chart0", it.copy(title = if (it.title.contains("India", true)) it.title else "Trending in India · ${it.title}"), d, nextSec()) }
                    f.jsTrending?.let { auraShelf("a-jst", it, d, nextSec()) }
                    f.regional.forEachIndexed { n, s -> auraShelf("a-reg$n", s, d, nextSec()) }
                    f.newReleases.forEachIndexed { n, s -> auraShelf("a-nr$n", s.copy(title = if (n == 0 && !s.title.contains("new", true)) "New releases" else s.title), d, nextSec()) }
                    f.charts.drop(1).forEachIndexed { n, s -> auraShelf("a-chart${n + 1}", s, d, nextSec()) }
                    if (artists.isNotEmpty()) {
                        val i = nextSec()
                        item(key = "a-art-h") { AuraHeader("Artists you like", null, i, d) }
                        item(key = "a-art") { ArtistRow(artists, d, i) }
                    }
                    f.jsPlaylists?.let { auraShelf("a-jsp", it, d, nextSec()) }
                    f.ytShelves.drop(3).forEachIndexed { n, s -> auraShelf("a-ytm$n", s, d, nextSec()) }
                    if (loadingMore) item(key = "a-more") { Row(Modifier.fillMaxWidth().padding(16.dp)) { SkeletonBox(d.card, d.card) } }
                    if (f.isEmpty) item(key = "a-empty") {
                        if (SourceKind.YOUTUBE !in settings.enabledSources && SourceKind.JIOSAAVN !in settings.enabledSources)
                            EmptyState(Icons.Rounded.PhoneAndroid, "Online sources are off", "Home comes from YouTube Music and JioSaavn. Turn one on in Settings → Sources.", "Open Sources") { nav.select(Tab.SETTINGS); nav.push(Route.SettingsPage("sources")) }
                        else EmptyState(Icons.Rounded.Download, "Couldn't load Home", error ?: "Pull down to try again.", "Retry") { scope.launch { HomeFeedRepo.refresh() } }
                    }
                }
                if (!online && f == null) item(key = "a-offline-empty") {
                    EmptyState(Icons.Rounded.CloudOff, "You're offline", "Downloads and music on this device still play.", "Downloads") { nav.push(Route.Downloads) }
                }
                item(key = "a-end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun LazyListScope.auraShelf(key: String, s: FeedShelf, d: HomeDims, index: Int) {
    if (s.isEmpty) return
    item(key = "$key-h") {
        val nav = LocalNav.current
        AuraHeader(s.title, s.subtitle, index, d) { nav.push(Route.ShelfAll(s.toShelf())) }
    }
    if (s.tracks.isNotEmpty()) item(key = "$key-t") {
        if (s.tracks.size >= 8 && s.collections.isEmpty()) QuickPicks(s.tracks, d, index, rows = 3) else TrackShelf(s.tracks, d.card, d, index)
    }
    if (s.collections.isNotEmpty()) item(key = "$key-c") { CollectionShelf(s.collections, d.card, d, index) }
}

// ---- pieces -------------------------------------------------------------------------------------------------------

@Composable
private fun AuraHomeHeader(acc: Account?, online: Boolean, d: HomeDims) {
    val nav = LocalNav.current
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late night vibes" }
    val first = acc?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = d.gutter + 4.dp, end = d.gutter, top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (online) "UPZE" else "OFFLINE · SAVED MUSIC", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = c.primary, fontWeight = FontWeight.Bold)
                Text(greeting, style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.6).sp), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (first != null) Text(first, style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant, maxLines = 1)
            }
            Box(Modifier.size(46.dp).clip(CircleShape).border(2.dp, Brush.linearGradient(listOf(c.primary, c.tertiary)), CircleShape).padding(3.dp).clip(CircleShape)
                .background(c.primaryContainer)
                .pressable(onClickLabel = "Accounts", pressedScale = 0.9f) { nav.select(Tab.SETTINGS); nav.push(Route.SettingsPage("accounts")) },
                contentAlignment = Alignment.Center) {
                if (acc?.avatarUrl != null) Artwork(acc.avatarUrl, Modifier.fillMaxSize(), CircleShape, "Your account")
                else Text((first ?: "M").take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = c.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(48.dp).liquidGlass(Ms.pill).pressable(onClickLabel = "Search", pressedScale = 0.98f) { nav.select(Tab.SEARCH) }
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = c.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text("Songs, artists, albums…", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Swipeable hero cards; each page tints itself from its own artwork and sits on a blurred copy of it. */
@Composable
private fun AuraHero(tracks: List<Track>, label: String, d: HomeDims) {
    // v2.0.14: quick picks can repeat a song id; HorizontalPager keys must be unique or it throws.
    val tracks = remember(tracks) { tracks.distinctBy { it.id } }
    val pager = rememberPagerState { tracks.size }
    val h = if (d.wide) 220.dp else 196.dp
    Column(Modifier.fillMaxWidth().staggerIn(0).padding(top = 12.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(h), contentPadding = PaddingValues(horizontal = d.gutter), pageSpacing = 10.dp, key = { tracks[it].id }) { page ->
            AuraHeroCard(tracks[page], label, tracks, page, d)
        }
        if (tracks.size > 1) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            val still = reducedMotion()
            repeat(tracks.size) { i ->
                val on = pager.currentPage == i
                val w by androidx.compose.animation.core.animateDpAsState(if (on) 20.dp else 6.dp, if (still) snap() else tween(260), label = "heroDot")
                Box(Modifier.padding(horizontal = 3.dp).height(6.dp).width(w).clip(Ms.pill)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)))
            }
        }
    }
}

@Composable
private fun AuraHeroCard(t: Track, label: String, all: List<Track>, index: Int, d: HomeDims) {
    val ctx = LocalContext.current
    var seed by remember(t.artUrl) { mutableStateOf<Color?>(null) }
    LaunchedEffect(t.artUrl) { seed = runCatching { com.ravi.musicspot.ui.theme.artSeed(ctx, t.artUrl) }.getOrNull() }
    val still = reducedMotion()
    val tint by animateColorAsState(seed ?: MaterialTheme.colorScheme.primary, if (still) snap() else tween(700), label = "auraHeroTint")
    val st by Graph.player.state.collectAsState()
    val isCurrent = st.current?.id == t.id
    val blurOk = android.os.Build.VERSION.SDK_INT >= 31
    Box(Modifier.fillMaxSize().softShadow(CARD).clip(CARD).background(lerp(tint, Color.Black, 0.55f))
        .pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.98f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { if (isCurrent) Graph.player.toggle() else Graph.player.play(all, index) }) {
        Artwork(t.artUrl, Modifier.fillMaxSize().then(if (blurOk) Modifier.blur(40.dp) else Modifier).graphicsLayer { alpha = if (blurOk) 0.55f else 0.25f; scaleX = 1.3f; scaleY = 1.3f }, RectangleShape)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(lerp(tint, Color.Black, 0.65f).copy(alpha = 0.92f), Color.Transparent))))
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp), color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold, maxLines = 1)
                Column {
                    Text(t.title, style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.4).sp), fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.clip(Ms.pill).background(Color.White).padding(start = 10.dp, end = 16.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.ravi.musicspot.ui.motion.PlayPauseIcon(isCurrent && st.playing, Modifier.size(20.dp), tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isCurrent && st.playing) "Pause" else if (isCurrent) "Resume" else "Play", style = MaterialTheme.typography.labelLarge, color = Color.Black, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(12.dp))
            val art = if (d.wide) 168.dp else 132.dp
            Artwork(t.artUrl, Modifier.size(art).graphicsLayer { rotationZ = 3f }.softShadow(Ms.r20), Ms.r20, t.title)
        }
    }
}

@Composable
private fun AuraMoods(titles: List<String>, d: HomeDims, onSelect: (Int) -> Unit) {
    var selected by remember { mutableStateOf(-1) }
    val primary = MaterialTheme.colorScheme.primary
    LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = d.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(titles) { i, t ->
            val on = selected == i
            Box(Modifier.height(38.dp).liquidGlass(Ms.pill).background(if (on) primary.copy(alpha = 0.28f) else Color.Transparent, Ms.pill)
                .pressable(onClickLabel = t, pressedScale = 0.92f) { selected = i; onSelect(i) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(t, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) primary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

/** Four colour tiles in a 2×2 grid (one row of four on wide screens), each with its live count. */
@Composable
private fun AuraTiles(d: HomeDims, liked: Int, downloads: Int, history: Int) {
    val nav = LocalNav.current
    data class T(val icon: ImageVector, val label: String, val count: Int, val a: Color, val b: Color, val go: () -> Unit)
    val tiles = listOf(
        T(Icons.Rounded.Favorite, "Liked songs", liked, Color(0xFF7B5CFF), Color(0xFFE05CB0)) { nav.push(Route.Liked) },
        T(Icons.Rounded.Download, "Downloads", downloads, Color(0xFF14A37F), Color(0xFF2E7DD7)) { nav.push(Route.Downloads) },
        T(Icons.Rounded.History, "History", history, Color(0xFFE5872E), Color(0xFFD9483B)) { nav.push(Route.History) },
        T(Icons.Rounded.PhoneAndroid, "On device", -1, Color(0xFF3A6BD6), Color(0xFF26A6C9)) { nav.push(Route.DeviceMusic) },
    )
    val rows = if (d.wide) listOf(tiles) else tiles.chunked(2)
    Column(Modifier.fillMaxWidth().padding(horizontal = d.gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    Box(Modifier.weight(1f).height(68.dp).clip(Ms.r20).background(Brush.linearGradient(listOf(t.a, t.b)))
                        .pressable(onClickLabel = t.label, pressedScale = 0.96f, onClick = t.go)) {
                        Icon(t.icon, null, Modifier.size(64.dp).align(Alignment.BottomEnd).offset(x = 12.dp, y = 14.dp).graphicsLayer { rotationZ = -14f }, tint = Color.White.copy(alpha = 0.22f))
                        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp)) {
                            Text(t.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (t.count >= 0) "${t.count} songs" else "Local files", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Section header with a short accent bar. */
@Composable
private fun AuraHeader(title: String, subtitle: String?, index: Int, d: HomeDims, onAll: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().staggerIn(index).padding(start = d.gutter, end = d.gutter, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(if (subtitle.isNullOrBlank()) 22.dp else 34.dp).clip(Ms.pill).background(Brush.verticalGradient(listOf(c.primary, c.tertiary))))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, letterSpacing = (-0.3).sp), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1) }
        }
        if (onAll != null) Box(Modifier.height(32.dp).liquidGlass(Ms.pill).pressable(onClickLabel = "See all $title", pressedScale = 0.92f, onClick = onAll).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center) { Text("See all", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun AuraOnRepeatHeader(tracks: List<Track>, index: Int, d: HomeDims) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().staggerIn(index).padding(start = d.gutter, end = d.gutter, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(34.dp).clip(Ms.pill).background(Brush.verticalGradient(listOf(c.primary, c.tertiary))))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("On repeat", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, letterSpacing = (-0.3).sp), fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text("Your top songs right now", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
        }
        Box(Modifier.size(38.dp).liquidGlass(CircleShape).pressable(onClickLabel = "Shuffle On repeat", pressedScale = 0.9f) { Graph.player.play(tracks, 0, shuffle = true) },
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Shuffle, "Shuffle On repeat", Modifier.size(18.dp)) }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(42.dp).clip(CircleShape).background(c.primary).pressable(onClickLabel = "Play all On repeat", pressedScale = 0.9f) { Graph.player.play(tracks, 0) },
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "Play all", tint = c.onPrimary) }
    }
}

/** Top 10 row: a big outlined rank number behind each cover. */
@Composable
private fun AuraTopTen(tracks: List<Track>, d: HomeDims, index: Int) {
    val art = if (d.wide) 132.dp else 112.dp
    val c = MaterialTheme.colorScheme
    LazyRow(Modifier.staggerIn(index), contentPadding = PaddingValues(horizontal = d.gutter), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(tracks, key = { _, t -> "top" + t.id }) { i, t ->
            Row(Modifier.pressable(onClickLabel = "Play ${t.title}", onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { Graph.player.play(tracks, i) }, verticalAlignment = Alignment.Bottom) {
                Text("${i + 1}", style = MaterialTheme.typography.displayLarge.copy(fontSize = 92.sp, letterSpacing = (-6).sp),
                    fontWeight = FontWeight.Black, color = c.onSurface.copy(alpha = 0.16f), modifier = Modifier.offset(y = 18.dp, x = 10.dp))
                Column(Modifier.width(art)) {
                    Artwork(t.artUrl, Modifier.size(art).softShadow(Ms.r16), Ms.r16, t.title)
                    Spacer(Modifier.height(6.dp))
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Recently played as square cards with a small glass play badge. */
@Composable
private fun AuraRecentRow(tracks: List<Track>, d: HomeDims, index: Int) {
    val w = d.card
    val c = MaterialTheme.colorScheme
    LazyRow(Modifier.staggerIn(index), contentPadding = PaddingValues(horizontal = d.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(tracks.take(20), key = { _, t -> "rp" + t.id }) { i, t ->
            Column(Modifier.width(w)) {
                Box {
                    Artwork(t.artUrl, Modifier.size(w).softShadow(CARD).clip(CARD)
                        .pressable(onClickLabel = "Play ${t.title}", onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { Graph.player.play(tracks, i) }, CARD, t.title)
                    Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp).liquidGlass(CircleShape)
                        .pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.88f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { Graph.player.play(tracks, i) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp), tint = c.onSurface)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
