@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravi.musicspot.ui.screens

import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
import com.ravi.musicspot.ui.motion.PlayPauseIcon
import com.ravi.musicspot.ui.motion.SkeletonBox
import com.ravi.musicspot.ui.motion.pressable
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.staggerIn
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * "Spotlight (big artwork)" Home dashboard (1.0.6: the only Home design; [HomeScreen] opens it). Cached feed, pull to
 * refresh, endless YT Music pages, offline fallbacks; the look:
 *  - a full-bleed spotlight with the current / recent / featured song's artwork edge to edge, fading into the page,
 *    greeting + big title + artist + Play and Shuffle over it (blur only on API 31+);
 *  - round shortcut bubbles (Liked, Downloads, History, On device, Search); outlined mood chips;
 *  - "Jump back in" (History) as wide cards near the top; "On repeat" as a numbered top-5 list with Play all;
 *  - Pack 5: "Your top artists" (PlayStats) and a "Your stats, anytime" card, each shown only with enough data;
 *  - shelves with large headers and a subtitle line.
 */
@Composable
fun SpotlightHomeScreen() {
    val nav = LocalNav.current
    val online by Graph.online.collectAsState()
    val lib by Graph.library.data.collectAsState()
    // 1.0.8 Home smoothness: only the finished downloads matter here. Collecting the whole map recomposed (and
    // re-sorted) the entire Home on every progress tick of every running download. Now the list is derived off the
    // main thread and Home only hears about it when the finished set actually changes.
    val downloadedFlow = remember {
        Graph.downloads.items
            .map { m -> m.values.filter { it.state == DownloadEntry.State.DONE }.sortedByDescending { it.atMs }.map { it.track } }
            .distinctUntilChanged()
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
    }
    val downloaded by downloadedFlow.collectAsState(initial = emptyList())
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

    val favourites = com.ravi.musicspot.ui.components.rememberOffMain(stats, lib.liked) { // 1.0.7: recomputed off the main thread
        com.ravi.musicspot.logic.Taste.favourites(stats.stats.values, lib.liked, System.currentTimeMillis()) }
    // 1.0.6 on-device time-of-day mix: songs this listener plays more at this part of the day (no network, no API).
    val todPart = remember(stats) { com.ravi.musicspot.logic.TimeOfDay.partAt(System.currentTimeMillis()) }
    val todPicks = com.ravi.musicspot.ui.components.rememberOffMain(stats, todPart) { com.ravi.musicspot.logic.TimeOfDay.picksFor(stats.stats.values, stats.dayparts, todPart, System.currentTimeMillis()) }
    val recent = remember(lib.history) { lib.history.map { it.track }.distinctBy { it.id }.take(20) }
    // Pack 5 personalised Home (all on-device, from History + PlayStats; each row hides itself without enough data).
    // Only the loaded song's id matters here (the whole player state would recompose Home on every player event).
    val currentIdFlow = remember { Graph.player.state.map { it.current?.id }.distinctUntilChanged() }
    val currentId by currentIdFlow.collectAsState(initial = Graph.player.state.value.current?.id)
    val spotlightId = currentId ?: recent.firstOrNull()?.id
    val jumpBack = com.ravi.musicspot.ui.components.rememberOffMain(lib.history, spotlightId) {
        com.ravi.musicspot.data.HomePersonal.jumpBackIn(lib.history, spotlightId, System.currentTimeMillis()) }
    val topArtists = com.ravi.musicspot.ui.components.rememberOffMain(stats) {
        com.ravi.musicspot.data.HomePersonal.topArtists(stats.stats.values, System.currentTimeMillis()) }
    val wrappedWeek = com.ravi.musicspot.ui.components.rememberOffMain(stats) {
        com.ravi.musicspot.logic.Wrapped.summarize(stats, com.ravi.musicspot.logic.Wrapped.Range.WEEK, System.currentTimeMillis()) }
    val hasAnyPlays = remember(stats) { stats.days.values.any { m -> m.values.any { it > 0 } } || stats.stats.values.any { it.plays > 0 } }
    val artists = com.ravi.musicspot.ui.components.rememberOffMain(lib.history, lib.liked) { // 1.0.7: off the main thread
        (lib.history.map { it.track } + lib.liked).filter { (it.artistId?.startsWith("UC") == true || it.artistId?.startsWith("yt:") == true) && it.artist.isNotBlank() }
            .groupBy { it.artistId!! }.entries.sortedByDescending { it.value.size }.take(14)
            .map { (id, ts) -> Collection(id, Collection.Kind.ARTIST, ts.first().artist.substringBefore(',').trim(), "${ts.size} songs", ts.first().artUrl) } // 1.0.6: History is one row per song
    }
    val mixes = com.ravi.musicspot.ui.components.rememberOffMain(lib.playlists, lib.saved, feed) { // 1.0.7: off the main thread
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
            // 1.0.5: shelf inputs derived once per data change instead of inside item lambdas on every recomposition.
            val moodTitles = remember(feed?.moods) { feed?.moods.orEmpty().map { it.title } }
            val favTop = remember(favourites) { favourites.take(5) }
            val mixShelf = remember(mixes) { mixes.distinctBy { it.id }.take(20) }
            val dlShelf = remember(downloaded) { downloaded.take(20) }
            val heroAll = remember(recent, feed?.quickPicks) { (if (recent.isNotEmpty()) recent else feed?.quickPicks.orEmpty()).take(5) }
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = padding) {
                item(key = "offline", contentType = "offline") { if (!online) Box(Modifier.statusBarsPadding()) { OfflineBanner() } } // 1.0.5: contentType on every item
                val f = feed
                // Spotlight: recent songs first (continue listening), else quick picks (featured); the playing song wins.
                val heroTracks = heroAll // 1.0.8: remembered list (was rebuilt on every pass), so the spotlight can skip recomposition
                item(key = "s-spotlight", contentType = "spotlight") {
                    SpotlightTop(ytAccount, online, heroTracks, if (recent.isNotEmpty()) "Continue listening" else "Featured for you", d)
                }
                // 1.0.7 (Raj): the Liked / Downloads / History / On device / Search shortcut bubbles are gone from Home
                // (all still in Library and the Search tab).
                if (f != null && f.moods.isNotEmpty()) item(key = "s-moods", contentType = "moods") {
                    SpotlightMoods(moodTitles, d) { i -> f.moods[i].let { m -> nav.push(Route.Mood(m.title, m.browseId, m.params)) } }
                }

                if (f == null && online && favourites.size < 2) {
                    // v2.0.14: a failed first load (no cache) used to leave the shimmer up forever; show the error with Retry.
                    if (error == null || refreshing) item(key = "s-skeleton", contentType = "skeleton") { HomeSkeleton(d) }
                    else item(key = "s-load-error", contentType = "empty") { EmptyState(Icons.Rounded.CloudOff, "Couldn't load Home", error ?: "Pull down to try again.", "Retry") { scope.launch { HomeFeedRepo.refresh() } } }
                    return@LazyColumn
                }

                var sec = 0
                fun nextSec() = sec++
                // Pack 5: "Jump back in" (was "Recently played", further down): newest History rows, near the top.
                if (recent.size > 1 && jumpBack.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "s-kl-h", contentType = "header-row") { SpotlightHeader("Jump back in", "Pick up where you left off", i, d) { nav.push(Route.History) } }
                    item(key = "s-kl", contentType = "recent-cards") { SpotlightJumpBackCards(jumpBack, d, i) }
                }
                if (favourites.size >= 2) {
                    val i = nextSec()
                    item(key = "s-fav-h", contentType = "fav-header") { SpotlightOnRepeatHeader(favourites, i, d) }
                    item(key = "s-fav", contentType = "top-list") { SpotlightTopList(favTop, d, i) }
                }
                if (todPicks.size >= 4) {
                    val i = nextSec()
                    val shelf = Shelf(todPart.shelfTitle, todPicks)
                    item(key = "s-tod-h", contentType = "header-row") { SpotlightHeader(todPart.shelfTitle, "What you usually play around this time", i, d) { nav.push(Route.ShelfAll(shelf)) } }
                    item(key = "s-tod", contentType = "track-shelf") { TrackShelf(todPicks, d.card, d, i) }
                }
                if (topArtists.isNotEmpty()) { // Pack 5: from PlayStats (3+ artists with 3+ counted plays each)
                    val i = nextSec()
                    item(key = "s-top-art-h", contentType = "header-row") { SpotlightHeader("Your top artists", "Who you play the most lately", i, d) }
                    item(key = "s-top-art", contentType = "artist-row") { SpotlightTopArtists(topArtists, d, i) }
                }
                if (hasAnyPlays) { // Pack 5: entry to Your stats (Wrapped anytime); hidden until something was counted
                    val i = nextSec()
                    item(key = "s-wrapped", contentType = "wrapped-card") { SpotlightWrappedCard(wrappedWeek, d, i) { nav.push(Route.Wrapped) } }
                }
                f?.forYou?.let { spotlightShelf("s-foryou", it, d, nextSec()) } // v2.0.15: seed-artist shelf on top
                if (f != null && f.quickPicks.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "s-qp-h", contentType = "header-row") { SpotlightHeader("Quick picks", "Start radio from a song", i, d) { nav.push(Route.ShelfAll(Shelf("Quick picks", f.quickPicks))) } }
                    item(key = "s-qp", contentType = "quickpicks") { QuickPicks(f.quickPicks, d, i, rows = 3) }
                }
                if (mixes.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "s-mix-h", contentType = "header-row") { SpotlightHeader("Your mixes & playlists", "From your library", i, d) { nav.select(Tab.LIBRARY) } }
                    item(key = "s-mix", contentType = "collection-shelf") { CollectionShelf(mixShelf, d.card * 1.12f, d, i) }
                }
                if (!online && downloaded.isNotEmpty()) {
                    val i = nextSec()
                    item(key = "s-dl-h", contentType = "header-row") { SpotlightHeader("Your downloads", "Plays offline", i, d) { nav.push(Route.Downloads) } }
                    item(key = "s-dl", contentType = "track-shelf") { TrackShelf(dlShelf, d.card, d, i) }
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
                        item(key = "s-art-h", contentType = "header-row") { SpotlightHeader("Artists you like", "Based on what you play", i, d) }
                        item(key = "s-art", contentType = "artist-row") { ArtistRow(artists, d, i) }
                    }
                    f.jsPlaylists?.let { spotlightShelf("s-jsp", it, d, nextSec()) }
                    f.ytShelves.drop(3).forEachIndexed { n, s -> spotlightShelf("s-ytm$n", s, d, nextSec()) }
                    if (loadingMore) item(key = "s-more", contentType = "more") { Row(Modifier.fillMaxWidth().padding(16.dp)) { SkeletonBox(d.card, d.card) } }
                    if (f.isEmpty) item(key = "s-empty", contentType = "empty") {
                        if (SourceKind.YOUTUBE !in settings.enabledSources && SourceKind.JIOSAAVN !in settings.enabledSources)
                            EmptyState(Icons.Rounded.PhoneAndroid, "Online sources are off", "Home comes from YouTube Music and JioSaavn. Turn one on in Settings → Sources.", "Open Sources") { nav.select(Tab.SETTINGS); nav.push(Route.SettingsPage("sources")) }
                        else EmptyState(Icons.Rounded.Download, "Couldn't load Home", error ?: "Pull down to try again.", "Retry") { scope.launch { HomeFeedRepo.refresh() } }
                    }
                }
                if (!online && f == null) item(key = "s-offline-empty", contentType = "empty") {
                    EmptyState(Icons.Rounded.CloudOff, "You're offline", "Downloads and music on this device still play.", "Downloads") { nav.push(Route.Downloads) }
                }
                item(key = "s-end", contentType = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun LazyListScope.spotlightShelf(key: String, s: FeedShelf, d: HomeDims, index: Int) {
    if (s.isEmpty) return
    item(key = "$key-h", contentType = "header-row") { // 1.0.5: contentType
        val nav = LocalNav.current
        val fallback = if (s.tracks.isNotEmpty()) "${s.tracks.size} songs" else "${s.collections.size} albums & playlists"
        SpotlightHeader(s.title, s.subtitle?.takeIf { it.isNotBlank() } ?: fallback, index, d) { nav.push(Route.ShelfAll(s.toShelf())) }
    }
    val dense = s.tracks.size >= 8 && s.collections.isEmpty()
    if (s.tracks.isNotEmpty()) item(key = "$key-t", contentType = if (dense) "quickpicks" else "track-shelf") {
        if (dense) QuickPicks(s.tracks, d, index, rows = 3) else TrackShelf(s.tracks, d.card, d, index)
    }
    if (s.collections.isNotEmpty()) item(key = "$key-c", contentType = "collection-shelf") { CollectionShelf(s.collections, d.card, d, index) }
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
    // Pack 5: device clock, re-read at the top of every hour so an open Home doesn't keep a stale greeting.
    var hour by remember { mutableStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    LaunchedEffect(Unit) {
        while (true) {
            val cal = Calendar.getInstance()
            val toNextHour = (60 - cal.get(Calendar.MINUTE)) * 60_000L - cal.get(Calendar.SECOND) * 1000L
            kotlinx.coroutines.delay(toNextHour.coerceAtLeast(1_000L))
            hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        }
    }
    val greeting = com.ravi.musicspot.data.HomePersonal.greeting(hour)
    val first = acc?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
    val tag = when { isCurrent && st.playing -> "Now playing"; isCurrent -> "Paused"; else -> label }

    var seed by remember(t?.artUrl) { mutableStateOf<Color?>(null) }
    LaunchedEffect(t?.artUrl) { seed = runCatching { com.ravi.musicspot.ui.theme.artSeed(ctx, t?.artUrl) }.getOrNull() }
    val still = reducedMotion()
    // 1.0.8: the tint glide is read only while drawing (was read in composition: the whole spotlight recomposed on
    // every frame of the 700 ms glide each time the song changed).
    val tint = animateColorAsState(seed ?: c.primary, if (still) snap() else tween(700), label = "spotlightTint")
    val bg = c.background
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val h = (if (d.wide) 400.dp else 360.dp) + top

    fun playMain() {
        when {
            isCurrent -> Graph.player.toggle()
            heroTracks.isNotEmpty() -> Graph.player.play(heroTracks, 0)
        }
    }

    Box(Modifier.fillMaxWidth().height(h).drawBehind { drawRect(lerp(tint.value, bg, 0.55f)) }) {
        if (t != null) {
            com.ravi.musicspot.ui.components.SoftArt(t.artUrl, Modifier.fillMaxSize().clipToBounds().graphicsLayer { alpha = 0.7f; scaleX = 1.25f; scaleY = 1.25f }) // 1.0.2: no blur pass
            Artwork(
                t.artUrl,
                Modifier.fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(SpotlightArtFadeMask, blendMode = BlendMode.DstIn) // 1.0.5: hoisted constant mask (was rebuilt every draw)
                    },
                RectangleShape, t.title,
            )
        }
        // Dark top for the status bar + greeting, fading into the page background at the bottom.
        // 1.0.5: remembered per background colour instead of re-allocated on every recomposition (tint animation frames).
        val pageFade = remember(bg) {
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.6f), 0.28f to Color.Black.copy(alpha = 0.12f), 0.55f to bg.copy(alpha = 0.35f), 0.82f to bg.copy(alpha = 0.88f), 1f to bg,
            )
        }
        Box(Modifier.fillMaxSize().background(pageFade))

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
                        .pressable(onClickLabel = if (isCurrent && st.playing) "Pause" else "Play ${t.title}", pressedScale = 0.94f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { playMain() }
                        .padding(start = 16.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayPauseIcon(isCurrent && st.playing, Modifier.size(24.dp), tint = c.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isCurrent && st.playing) "Pause" else if (isCurrent) "Resume" else "Play", style = MaterialTheme.typography.titleSmall, color = c.onPrimary, fontWeight = FontWeight.Bold)
                    }
                    if (heroTracks.size > 1) {
                        Spacer(Modifier.width(10.dp))
                        // 1.0.8: a still glass film (sheen + rim) instead of a live backdrop blur: this pill scrolls with
                        // the page, and the sampled blur re-rendered on every frame of every scroll over Home.
                        Row(Modifier.height(48.dp).clip(Ms.pill).background(c.surfaceContainerHigh.copy(alpha = 0.72f))
                            .background(SpotlightPillSheen).border(1.dp, SpotlightPillRim, Ms.pill)
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

// 1.0.8: still glass look for the spotlight's Shuffle pill (no live blur while scrolling).
private val SpotlightPillSheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.45f to Color.Transparent, 1f to Color.Transparent)
private val SpotlightPillRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f)))

// 1.0.5: constant alpha mask for the spotlight artwork fade.
private val SpotlightArtFadeMask = Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent)

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
        items(bubbles, key = { "bubble-" + it.label }, contentType = { "bubble" }) { b -> // 1.0.5: contentType
            Column(Modifier.width(size + 12.dp).pressable(onClickLabel = b.label, pressedScale = 0.92f, onClick = b.go), horizontalAlignment = Alignment.CenterHorizontally) {
                val bubbleBrush = remember(b.a, b.b) { Brush.linearGradient(listOf(b.a, b.b)) } // 1.0.5: remembered gradient
                Box(Modifier.size(size).clip(CircleShape).background(bubbleBrush), contentAlignment = Alignment.Center) {
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
        // 1.0.5: titles can repeat, so the key carries the position; contentType for reuse.
        itemsIndexed(titles, key = { i, t -> "mood:$i:$t" }, contentType = { _, _ -> "mood-chip" }) { i, t ->
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
                .pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.98f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { if (playing) Graph.player.toggle() else Graph.player.play(tracks, i) }
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

/**
 * Pack 5 "Jump back in" (was "Recently played"): wide cards, cover on the left, title / artist / when it was played,
 * round play button. A card for the song that is loaded but paused resumes it where it stopped instead of restarting;
 * any other card plays History from that song on. (History keeps no per-song position, so only the loaded song resumes.)
 */
@Composable
private fun SpotlightJumpBackCards(items: List<com.ravi.musicspot.data.HomePersonal.JumpBack>, d: HomeDims, index: Int) {
    val c = MaterialTheme.colorScheme
    val w = if (d.wide) 320.dp else 272.dp
    val h = 84.dp
    val st by Graph.player.state.collectAsState()
    val tracks = remember(items) { items.map { it.entry.track } }
    LazyRow(Modifier.staggerIn(index), contentPadding = PaddingValues(horizontal = d.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // ids are unique (HomePersonal.jumpBackIn keeps each song once).
        itemsIndexed(items, key = { _, j -> "srp" + j.entry.track.id }, contentType = { _, _ -> "recent-card" }) { i, j ->
            val t = j.entry.track
            val loaded = st.current?.id == t.id
            val resume = loaded && !st.playing
            val detail = when {
                loaded && st.playing -> "Playing now"
                resume -> "Paused · tap to resume"
                j.entry.plays > 1 -> "${j.ago} · played ${j.entry.plays}×"
                else -> j.ago
            }
            Row(Modifier.width(w).height(h).clip(Ms.r20).background(c.surfaceContainerHigh)
                .pressable(onClickLabel = if (resume) "Resume ${t.title}" else "Play ${t.title}", pressedScale = 0.97f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) {
                    if (loaded) Graph.player.toggle() else Graph.player.play(tracks, i)
                }, verticalAlignment = Alignment.CenterVertically) {
                Artwork(t.artUrl, Modifier.size(h), RectangleShape, t.title)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (loaded) c.primary else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.padding(end = 12.dp).size(36.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                    PlayPauseIcon(loaded && st.playing, Modifier.size(20.dp), tint = c.onPrimary)
                }
            }
        }
    }
}

/**
 * Pack 5 "Your top artists" (PlayStats): round bubbles like "Artists you like". Opens the artist page when the songs
 * carry a YouTube artist id; otherwise plays that artist's counted songs (most played first), all on-device.
 */
@Composable
private fun SpotlightTopArtists(artists: List<com.ravi.musicspot.data.HomePersonal.TopArtist>, d: HomeDims, index: Int) {
    val nav = LocalNav.current
    val size = if (d.wide) 112.dp else 92.dp
    LazyRow(Modifier.staggerIn(index), contentPadding = PaddingValues(horizontal = d.gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(artists, key = { "top-art:" + it.name }, contentType = { "artist" }) { a ->
            Column(Modifier.width(size).pressable(onClickLabel = "Open ${a.name}") {
                if (a.artistId != null) nav.push(Route.Artist(a.artistId, a.name)) else Graph.player.play(a.tracks, 0)
            }, horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(a.artUrl, Modifier.size(size), CircleShape, a.name)
                Spacer(Modifier.height(6.dp))
                Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text("${a.plays} plays", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Pack 5: Home entry to Your stats (Wrapped anytime). Shown only once PlayStats has counted something. */
@Composable
private fun SpotlightWrappedCard(week: com.ravi.musicspot.logic.Wrapped.Summary, d: HomeDims, index: Int, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val line = buildList {
        if (week.totalPlays > 0) add("${week.totalPlays} ${if (week.totalPlays == 1) "play" else "plays"} this week")
        if (week.currentStreak > 1) add("${week.currentStreak}-day streak")
    }.joinToString(" · ").ifEmpty { "Your top songs, artists and listening habits" }
    Row(Modifier.fillMaxWidth().staggerIn(index).padding(start = d.gutter, end = d.gutter, top = 24.dp).clip(Ms.r20).background(c.surfaceContainerHigh)
        .pressable(onClickLabel = "Open your stats", pressedScale = 0.97f, onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(Ms.r12).background(c.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Insights, null, Modifier.size(24.dp), tint = c.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Your stats, anytime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(22.dp), tint = c.onSurfaceVariant)
    }
}
