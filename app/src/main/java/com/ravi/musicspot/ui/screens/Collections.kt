package com.ravi.musicspot.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.ravi.musicspot.ui.motion.scroll3d
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.data.DownloadEntry
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.Pills
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.Badge
import com.ravi.musicspot.ui.components.CollectionCard
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.components.ErrorState
import com.ravi.musicspot.ui.components.GlassTopBar
import com.ravi.musicspot.ui.components.HorizontalShelf
import com.ravi.musicspot.ui.components.Loading
import com.ravi.musicspot.ui.components.SectionHeader
import com.ravi.musicspot.ui.components.TrackCard
import com.ravi.musicspot.ui.components.TrackRow
import com.ravi.musicspot.ui.components.formatBytes
import com.ravi.musicspot.ui.components.formatTime
import com.ravi.musicspot.ui.glass.GlassLayer
import com.ravi.musicspot.ui.openCollection
import com.ravi.musicspot.ui.theme.Ms
import com.ravi.musicspot.yt.CollectionPage
import kotlinx.coroutines.launch
import com.ravi.musicspot.ui.motion.sharedCover
import com.ravi.musicspot.ui.motion.staggerIn

/** Generic page for any list of songs: header art, play / shuffle / download-all, and the rows. */
@Composable
fun TrackListPage(
    title: String,
    subtitle: String?,
    art: String?,
    tracks: List<Track>,
    emptyIcon: ImageVector = Icons.AutoMirrored.Rounded.QueueMusic,
    emptyTitle: String = "Nothing here yet",
    emptyBody: String = "",
    headerActions: @Composable () -> Unit = {},
    description: String? = null,
    rowTrailing: (@Composable (Int, Track) -> Unit)? = null,
    extra: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
    sharedKey: String? = null,
    loading: Boolean = false,
) {
    val nav = LocalNav.current
    val header = rememberHeaderHeight() // v2.0.17
    GlassLayer(Modifier.fillMaxSize(), overlay = { GlassTopBar(title, onBack = { nav.back() }, height = header) }) {
        val list3d = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize(), state = list3d, contentPadding = pagePadding(top = 72.dp, header = header.value)) {
            item {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(art ?: tracks.firstOrNull()?.artUrl, Modifier.size(200.dp).sharedCover(sharedKey), Ms.r20, title)
                    Spacer(Modifier.height(16.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    val total = tracks.sumOf { it.durationMs }
                    if (tracks.isNotEmpty()) Text("${tracks.size} songs${if (total > 0) " · ${formatTime(total)}" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { Graph.player.play(tracks) }, enabled = tracks.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play") }
                        FilledTonalButton(onClick = { Graph.player.play(tracks, shuffle = true) }, enabled = tracks.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
                        IconButton(onClick = {
                            val todo = tracks.filter { it.source != SourceKind.LOCAL && !Graph.downloads.isDownloaded(it.id) }
                            todo.forEach { Graph.downloads.enqueue(it, Graph.settings.value.wifiOnlyDownloads) }
                            Pills.show(if (todo.isEmpty()) "Already downloaded" else "Downloading ${todo.size} songs")
                        }, enabled = tracks.isNotEmpty()) { Icon(Icons.Rounded.Download, "Download all") }
                        headerActions()
                    }
                    description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp)) }
                }
            }
            if (loading) item { Loading() }
            else if (tracks.isEmpty()) item { EmptyState(emptyIcon, emptyTitle, emptyBody) }
            items(tracks.size, key = { "${tracks[it].id}-$it" }, contentType = { "track" }) { i -> // 1.0.1: contentType
                val t = tracks[i]
                androidx.compose.foundation.layout.Box(Modifier.scroll3d(list3d, i + 1, focus = 0.5f, maxTilt = 24f, minScale = 0.9f, minAlpha = 0.55f, flat = 0.45f, flatRows = com.ravi.musicspot.ui.motion.Depth.FLAT_TRACKS).staggerIn(i, enabled = i < 14)) { TrackRow(t, onClick = { Graph.player.play(tracks, i) }, onMore = { Menus.track = t }, trailing = rowTrailing?.let { { it(i, t) } }) }
            }
            extra?.invoke(this)
        }
    }
}

@Composable
fun RemoteCollectionScreen(c: Collection) {
    val nav = LocalNav.current
    val lib by Graph.library.data.collectAsState()
    val load = rememberLoad(c.id) {
        when {
            c.id.startsWith("yt:") -> Graph.yt.collection(c.id.removePrefix("yt:"))
            c.id.startsWith("js:al:") -> Graph.jiosaavn.album(c.id.removePrefix("js:al:")).let { CollectionPage(it.first, it.second) }
            c.id.startsWith("js:pl:") -> Graph.jiosaavn.playlist(c.id.removePrefix("js:pl:")).let { CollectionPage(it.first, it.second) }
            c.id.startsWith("ia:") -> Graph.archive.item(c.id.removePrefix("ia:")).let { CollectionPage(it.first, it.second) }
            else -> throw IllegalStateException("Unknown collection")
        }
    }
    com.ravi.musicspot.ui.motion.StateCrossfade(load.state, key = { if (it is Load.Failed) 1 else 0 }) { s -> when (s) {
        // Header art (same page while loading and loaded, so only the error state crossfades) (shared-element target) shows immediately while the songs load.
        is Load.Loading -> TrackListPage(c.title, c.subtitle, c.artUrl, emptyList(), sharedKey = "cover:${c.id}", loading = true)
        is Load.Failed -> GlassLayer(Modifier.fillMaxSize(), overlay = { GlassTopBar(c.title, onBack = { nav.back() }) }) { Box(Modifier.padding(top = 100.dp)) { ErrorState(s.message, load.retry) } }
        is Load.Ok -> {
            val page = s.data
            val col = page.collection.copy(title = page.collection.title.ifBlank { c.title }, artUrl = page.collection.artUrl ?: c.artUrl)
            val saved = lib.saved.any { it.id == col.id }
            TrackListPage(col.title, col.subtitle ?: c.subtitle, col.artUrl ?: c.artUrl, page.tracks, description = page.description, sharedKey = "cover:${c.id}",
                emptyTitle = "No playable songs", emptyBody = "This ${if (col.kind == Collection.Kind.ALBUM) "album" else "playlist"} has no songs we can play.",
                headerActions = {
                    IconButton(onClick = { Graph.library.toggleSaved(col); Pills.show(if (saved) "Removed from library" else "Saved to library") }) {
                        Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (saved) "Remove from library" else "Save to library")
                    }
                    IconButton(onClick = { val p = Graph.library.createPlaylist(col.title, page.tracks, art = col.artUrl); Pills.show("Copied to your playlist ${p.name}") }) {
                        Icon(Icons.Rounded.LibraryMusic, "Copy to my playlists")
                    }
                },
                extra = {
                    page.shelves.forEach { sh ->
                        item { SectionHeader(sh.title) }
                        if (sh.collections.isNotEmpty()) item { HorizontalShelf(sh.collections, { it.id }) { x -> CollectionCard(x, 132.dp) { nav.openCollection(x) } } }
                    }
                })
        }
    } }
}

@Composable
fun ArtistScreen(r: Route.Artist) {
    val nav = LocalNav.current
    val lib by Graph.library.data.collectAsState()
    val load = rememberLoad(r.id) { Graph.yt.artist(r.id.removePrefix("yt:")) }
    val header = rememberHeaderHeight() // v2.0.17
    GlassLayer(Modifier.fillMaxSize(), overlay = { GlassTopBar(r.name, onBack = { nav.back() }, height = header) }) {
        com.ravi.musicspot.ui.motion.StateCrossfade(load.state) { s -> when (s) {
            is Load.Loading -> Box(Modifier.padding(top = 120.dp)) { Loading() }
            is Load.Failed -> Box(Modifier.padding(top = 100.dp)) { ErrorState(s.message, load.retry) }
            is Load.Ok -> {
                val a = s.data
                val saved = lib.saved.any { it.id == a.collection.id }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(top = 72.dp, header = header.value)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Artwork(a.collection.artUrl, Modifier.size(180.dp).sharedCover("cover:${r.id}"), CircleShape, a.collection.title)
                            Spacer(Modifier.height(12.dp))
                            Text(a.collection.title.ifBlank { r.name }, style = MaterialTheme.typography.headlineMedium)
                            a.collection.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { Graph.player.play(a.topTracks) }, enabled = a.topTracks.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Text("Play") }
                                FilledTonalButton(onClick = { Graph.library.toggleSaved(a.collection) }) { Text(if (saved) "Following" else "Follow") }
                            }
                            a.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp)) }
                        }
                    }
                    // v2.0.14: distinct ids (duplicate keys crash LazyColumn); play by index, not indexOf.
                    val top = a.topTracks.distinctBy { it.id }
                    if (top.isNotEmpty()) {
                        item { SectionHeader("Top songs") }
                        itemsIndexed(top.take(10), key = { _, it -> it.id }, contentType = { _, _ -> "track" }) { i, t -> Box(Modifier.staggerIn(i + 1)) { TrackRow(t, onClick = { Graph.player.play(top, i) }, onMore = { Menus.track = t }) } }
                    }
                    a.shelves.forEachIndexed { i, sh ->
                        item(key = "s$i") { SectionHeader(sh.title) }
                        if (sh.tracks.isNotEmpty()) item(key = "st$i") { HorizontalShelf(sh.tracks, { it.id }) { t -> TrackCard(t, 132.dp, onClick = { Graph.player.play(sh.tracks, sh.tracks.indexOf(t)) }) } }
                        if (sh.collections.isNotEmpty()) item(key = "sc$i") { HorizontalShelf(sh.collections, { it.id }) { c -> CollectionCard(c, 132.dp) { nav.openCollection(c) } } }
                    }
                }
            }
        } }
    }
}

@Composable
fun LocalPlaylistScreen(id: String) {
    val nav = LocalNav.current
    val lib by Graph.library.data.collectAsState()
    // v2.0.16: Delete already navigates back; without this flag the missing-playlist guard popped a second screen.
    var leaving by remember(id) { mutableStateOf(false) }
    val p = lib.playlists.firstOrNull { it.id == id } ?: run { if (!leaving) LaunchedEffect(Unit) { leaving = true; nav.back() }; return }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    TrackListPage(p.name, when {
        p.origin.startsWith("spotify:") -> "Synced from Spotify · plays via your other sources"
        p.origin.startsWith("yt:") -> "Synced from YouTube Music"
        else -> "Your playlist"
    }, p.artUrl, p.tracks, sharedKey = "cover:${p.id}", emptyTitle = "Empty playlist", emptyBody = "Add songs from any song's ⋮ menu.",
        headerActions = {
            if (p.origin.startsWith("spotify:")) IconButton(onClick = { Graph.sync.syncSpotify(setOf(p.origin.removePrefix("spotify:"))); Pills.show("Syncing from Spotify…") }) { Icon(Icons.Rounded.Refresh, "Sync now") }
            if (!p.isSynced) IconButton(onClick = { rename = true }) { Icon(Icons.Rounded.Edit, "Rename") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete playlist") }
        },
        rowTrailing = if (p.isSynced) { i, t -> if (t.source == SourceKind.SPOTIFY) Badge("UNMATCHED") } else null)
    com.ravi.musicspot.ui.motion.MotionDialog(if (rename) Unit else null, onDismiss = { rename = false }) {
        var name by remember { mutableStateOf(p.name) }
        com.ravi.musicspot.ui.motion.AlertDialogCard(title = { Text("Rename playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { Graph.library.renamePlaylist(p.id, name); rename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    }
    com.ravi.musicspot.ui.motion.MotionAlertDialog(visible = confirmDelete, onDismissRequest = { confirmDelete = false }, title = { Text("Delete “${p.name}”?") },
        text = { Text(if (p.isSynced) "It is removed from UpZe only; your account keeps it, and the next sync can bring it back." else "This can't be undone.") },
        confirmButton = { TextButton(onClick = { leaving = true; Graph.library.deletePlaylist(p.id); confirmDelete = false; nav.back() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
fun LikedScreen() {
    val lib by Graph.library.data.collectAsState()
    TrackListPage("Liked songs", "Saved on this device${if (Graph.tube.signedIn) " · includes your YouTube Music likes" else ""}", null, lib.liked,
        emptyIcon = Icons.Rounded.Favorite, emptyTitle = "No liked songs yet", emptyBody = "Tap ♥ on any song. Signing in to YouTube Music or Spotify imports your likes.")
}

@Composable
fun HistoryScreen() {
    val lib by Graph.library.data.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    TrackListPage("History", "What you played, newest first", null, lib.history.map { it.track },
        emptyIcon = Icons.Rounded.History, emptyTitle = "No history yet", emptyBody = "Songs you play show up here.",
        headerActions = { IconButton(onClick = { confirm = true }, enabled = lib.history.isNotEmpty()) { Icon(Icons.Rounded.Delete, "Clear history") } })
    com.ravi.musicspot.ui.motion.MotionAlertDialog(visible = confirm, onDismissRequest = { confirm = false }, title = { Text("Clear listening history?") },
        confirmButton = { TextButton(onClick = { Graph.library.clearHistory(); confirm = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
fun DownloadsScreen() {
    val dls by Graph.downloads.items.collectAsState()
    val done = dls.values.filter { it.state == DownloadEntry.State.DONE }.sortedByDescending { it.atMs }
    val pending = dls.values.filter { it.state != DownloadEntry.State.DONE }
    val used = remember(dls) { Graph.downloads.usedBytes() }
    TrackListPage("Downloads", "${done.size} songs · ${formatBytes(used)} on this device", null, done.map { it.track },
        emptyIcon = Icons.Rounded.Download, emptyTitle = "No downloads yet", emptyBody = "Download songs, albums or playlists from their ⋮ menu or the download button. They play offline.",
        rowTrailing = { _, t -> dls[t.id]?.info?.let { i -> Badge(listOfNotNull(i.codec, i.bitrateKbps?.let { "$it" }).joinToString(" ")) } },
        extra = {
            if (pending.isNotEmpty()) {
                item { SectionHeader("In progress") }
                items(pending, key = { "p-" + it.track.id }) { e ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(when (e.state) {
                                    DownloadEntry.State.FAILED -> "Failed: ${e.error}"
                                    DownloadEntry.State.QUEUED -> "Waiting${if (Graph.settings.value.wifiOnlyDownloads) " for Wi-Fi" else ""}…"
                                    else -> "${formatBytes(e.bytes)} of ${if (e.total > 0) formatBytes(e.total) else "?"}"
                                }, style = MaterialTheme.typography.bodySmall, color = if (e.state == DownloadEntry.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (e.state == DownloadEntry.State.FAILED) TextButton(onClick = { Graph.downloads.retry(e.track.id, Graph.settings.value.wifiOnlyDownloads) }) { Text("Retry") }
                            TextButton(onClick = { Graph.downloads.cancel(e.track.id) }) { Text("Cancel") }
                        }
                        if (e.state == DownloadEntry.State.RUNNING) LinearProgressIndicator({ e.progress }, Modifier.fillMaxWidth())
                    }
                }
            }
        })
}

@Composable
fun DeviceMusicScreen() {
    val tracks by Graph.local.tracks.collectAsState()
    val settings by Graph.settings.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var granted by remember { mutableStateOf(Graph.local.hasPermission()) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) Graph.settings.update { it.copy(enabledSources = it.enabledSources + SourceKind.LOCAL) } else Pills.show("Permission denied — device music stays off")
    }
    LaunchedEffect(granted, settings.enabledSources) {
        // v2.0.16: scan can throw (permission revoked, IO); uncaught it reached CrashGuard and killed the app.
        if (granted && SourceKind.LOCAL in settings.enabledSources) try { Graph.local.scan() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Pills.show("Couldn't scan this device") }
    }
    if (!granted || SourceKind.LOCAL !in settings.enabledSources) {
        val nav = LocalNav.current
        GlassLayer(Modifier.fillMaxSize(), overlay = { GlassTopBar("On this device", onBack = { nav.back() }) }) {
            Box(Modifier.padding(top = 100.dp)) {
                EmptyState(Icons.Rounded.PhoneAndroid, "Play music stored on this phone",
                    "UpZe reads your audio files (FLAC, ALAC, WAV, MP3, AAC…) only after you allow it. Nothing is uploaded.", "Allow access") {
                    if (granted) Graph.settings.update { it.copy(enabledSources = it.enabledSources + SourceKind.LOCAL) } else perm.launch(Graph.local.permission)
                }
            }
        }
    } else TrackListPage("On this device", "Lossless files play untouched at their native quality", null, tracks,
        emptyIcon = Icons.Rounded.PhoneAndroid, emptyTitle = "No music files found", emptyBody = "Copy audio files to your phone's Music folder.",
        headerActions = { IconButton(onClick = { scope.launch { /* v2.0.14: scan can throw (permission revoked, IO) */ val n = runCatching { Graph.local.scan().size }.getOrNull(); Pills.show(if (n != null) "Found $n songs" else "Couldn't scan this device") } }) { Icon(Icons.Rounded.Refresh, "Rescan") } },
        rowTrailing = { _, t -> t.format?.let { f -> if (com.ravi.musicspot.source.QualityEstimate.isLosslessFormat(f)) Badge(f.uppercase(), MaterialTheme.colorScheme.primary) } })
}

@Composable
fun SavedScreen() {
    val nav = LocalNav.current
    val lib by Graph.library.data.collectAsState()
    val header = rememberHeaderHeight() // v2.0.17
    GlassLayer(Modifier.fillMaxSize(), overlay = { GlassTopBar("Albums & artists", onBack = { nav.back() }, height = header) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(top = 72.dp, header = header.value)) {
            if (lib.saved.isEmpty()) item { EmptyState(Icons.Rounded.Bookmark, "Nothing saved yet", "Save albums and playlists or follow artists — they appear here.") }
            items(lib.saved.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { c -> Box(Modifier.weight(1f)) { CollectionCard(c, 160.dp) { nav.openCollection(c) } } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Suppress("unused") private fun share(t: Track) = Intent(Intent.ACTION_SEND)
