package com.ravi.musicspot.ui.screens

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.CloudDone
import com.ravi.musicspot.ui.motion.pressScale
import com.ravi.musicspot.ui.motion.staggerIn
import com.ravi.musicspot.ui.motion.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.account.Account
import com.ravi.musicspot.account.SyncManager
import com.ravi.musicspot.data.DownloadEntry
import com.ravi.musicspot.data.LocalPlaylist
import com.ravi.musicspot.ui.Pills
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.Tab
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.components.GlassTopBar
import com.ravi.musicspot.ui.components.SectionHeader
import com.ravi.musicspot.ui.glass.GlassLayer
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.theme.Ms

/** Library: the user's own music (likes, playlists, history, downloads, device files, saved) plus synced account playlists. */
@Composable
fun LibraryScreen() {
    val nav = LocalNav.current
    val lib by Graph.library.data.collectAsState()
    val dls by Graph.downloads.items.collectAsState()
    val device by Graph.local.tracks.collectAsState()
    val accounts by Graph.accounts.accounts.collectAsState()
    val ytStatus by Graph.sync.yt.collectAsState()
    val spStatus by Graph.sync.spotify.collectAsState()
    val ytSignedIn = accounts.any { it.service == Account.Service.YOUTUBE }
    val spSignedIn = accounts.any { it.service == Account.Service.SPOTIFY }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LocalPlaylist?>(null) }
    var deleting by remember { mutableStateOf<LocalPlaylist?>(null) }

    // v2.0.14: distinct ids so keyed lazy items never collide.
    // 1.0.5: remembered per data change (were re-filtered/sorted on every recomposition of the screen).
    val own = remember(lib.playlists) { lib.playlists.filter { !it.isSynced }.distinctBy { it.id }.sortedByDescending { it.pinned } } // 1.0.2: pinned first
    val yt = remember(lib.playlists) { lib.playlists.filter { it.origin.startsWith("yt:") }.distinctBy { it.id }.sortedByDescending { it.pinned } } // 1.0.2: pinned first
    val sp = remember(lib.playlists) { lib.playlists.filter { it.origin.startsWith("spotify:") }.distinctBy { it.id }.sortedByDescending { it.pinned } } // 1.0.2: pinned first
    val downloaded = remember(dls) { dls.values.count { it.state == DownloadEntry.State.DONE } }

    val header = rememberHeaderHeight() // v2.0.17
    GlassLayer(Modifier.fillMaxSize(), overlay = {
        GlassTopBar("Library", onBack = null, height = header) {
            Surface(onClick = { creating = true }, shape = CircleShape, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).glass(CircleShape)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "New playlist") }
            }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(top = 76.dp, header = header.value)) {
            item(key = "lib-tiles", contentType = "lib-tiles") { // 1.0.5: key + contentType on every item
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.staggerIn(0), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(Icons.Rounded.Favorite, "Liked songs", count(lib.liked.size, "song"), Modifier.weight(1f)) { nav.push(Route.Liked) }
                        Tile(Icons.Rounded.Download, "Downloads", count(downloaded, "song"), Modifier.weight(1f)) { nav.push(Route.Downloads) }
                    }
                    Row(Modifier.staggerIn(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(Icons.Rounded.History, "History", count(lib.history.size, "song"), Modifier.weight(1f)) /* 1.0.6: one row per song now */ { nav.push(Route.History) }
                        Tile(Icons.Rounded.PhoneAndroid, "On this device", if (Graph.local.hasPermission()) count(device.size, "file") else "Allow access", Modifier.weight(1f)) { nav.push(Route.DeviceMusic) }
                    }
                    Tile(Icons.Rounded.Bookmark, "Albums & artists", count(lib.saved.size, "saved item"), Modifier.fillMaxWidth().staggerIn(2)) { nav.push(Route.Saved) }
                    // 1.0.2: cached songs (offline), live radio, and Listen together.
                    val cachedN by Graph.cached.complete.collectAsState()
                    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Graph.cached.refresh() } } }
                    Row(Modifier.staggerIn(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(Icons.Rounded.CloudDone, "Cached songs", if (cachedN.isEmpty()) "Play offline" else count(cachedN.size, "song"), Modifier.weight(1f)) { nav.push(Route.CachedSongs) }
                        Tile(Icons.Rounded.Radio, "Live radio", "FM near you", Modifier.weight(1f)) { nav.push(Route.Radio) }
                    }
                    val room by com.ravi.musicspot.together.ListenTogether.room.collectAsState()
                    Tile(Icons.Rounded.Groups, "Listen together", room?.let { r -> "In room ${r.code}" + if (r.listeners > 0) " · ${count(r.listeners, "listener")}" else "" } ?: "Play in sync with friends",
                        Modifier.fillMaxWidth().staggerIn(4)) { nav.push(Route.ListenTogether) }
                    // Pack 5: Your stats (Wrapped anytime), always reachable from Library.
                    Tile(Icons.Rounded.Insights, "Your stats", "Top songs, artists & streaks", Modifier.fillMaxWidth().staggerIn(5)) { nav.push(Route.Wrapped) }
                }
            }

            item(key = "own-h", contentType = "section-header") { SectionHeader("Your playlists", action = "New", onAction = { creating = true }) }
            if (own.isEmpty()) item(key = "own-empty", contentType = "empty") {
                EmptyState(Icons.AutoMirrored.Rounded.PlaylistAdd, "No playlists yet",
                    "Create one here, or add any song to a new playlist from its ⋮ menu.", "Create playlist", onAction = { creating = true })
            }
            // 1.0.5: keys namespaced per section so a playlist id in two sections can never collide; contentType for reuse.
            itemsIndexed(own, key = { _, it -> "own:" + it.id }, contentType = { _, _ -> "playlist" }) { i, p -> PlaylistRow(i + 3, /* v2.0.14: index, not indexOf */ p, { nav.push(Route.LocalPlaylist(p.id)) }, onRename = { renaming = p }, onDelete = { deleting = p }) }

            if (ytSignedIn) {
                item(key = "yt-h", contentType = "sync-header") { SyncHeader("From YouTube Music", ytStatus) { Graph.sync.syncYouTube() } }
                if (yt.isEmpty()) item(key = "yt-empty", contentType = "empty") {
                    EmptyState(Icons.Rounded.CloudSync, if (ytStatus.running) "Syncing…" else "No YouTube Music playlists yet",
                        "Your YouTube Music playlists, likes and saved albums appear here after a sync.", if (ytStatus.running) null else "Sync now",
                        onAction = { Graph.sync.syncYouTube() })
                }
                itemsIndexed(yt, key = { _, it -> "yt:" + it.id }, contentType = { _, _ -> "playlist" }) { i, p -> PlaylistRow(i + 3, /* v2.0.14: index, not indexOf */ p, { nav.push(Route.LocalPlaylist(p.id)) }, onRename = null, onDelete = { deleting = p }) }
            }
            if (spSignedIn) {
                item(key = "sp-h", contentType = "sync-header") { SyncHeader("From Spotify", spStatus) { Graph.sync.syncSpotify() } }
                if (sp.isEmpty()) item(key = "sp-empty", contentType = "empty") {
                    EmptyState(Icons.Rounded.CloudSync, if (spStatus.running) "Syncing…" else "No Spotify playlists yet",
                        "Spotify playlists are read (titles only) and each song is matched to a source that can play it.", if (spStatus.running) null else "Sync now",
                        onAction = { Graph.sync.syncSpotify() })
                }
                itemsIndexed(sp, key = { _, it -> "sp:" + it.id }, contentType = { _, _ -> "playlist" }) { i, p -> PlaylistRow(i + 3, /* v2.0.14: index, not indexOf */ p, { nav.push(Route.LocalPlaylist(p.id)) }, onRename = null, onDelete = { deleting = p }) }
            }
            if (!ytSignedIn || !spSignedIn) item(key = "bring-playlists", contentType = "bring-playlists") {
                Row(Modifier.fillMaxWidth().padding(16.dp).clip(Ms.r20).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { nav.open(Tab.SETTINGS, Route.SettingsPage("accounts")) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudSync, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bring your playlists", style = MaterialTheme.typography.titleSmall)
                        Text(when {
                            !ytSignedIn && !spSignedIn -> "Sign in to YouTube Music or Spotify to sync your likes and playlists."
                            !ytSignedIn -> "Sign in to YouTube Music to sync your likes and playlists."
                            else -> "Sign in to Spotify to import your playlists."
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    NameDialog(if (creating) Unit else null, "New playlist", { "" }, "Create", onDismiss = { creating = false }) { _, name ->
        val p = Graph.library.createPlaylist(name); creating = false; Pills.show("Created “${p.name}”")
    }
    NameDialog(renaming, "Rename playlist", { it.name }, "Save", onDismiss = { renaming = null }) { p, name -> Graph.library.renamePlaylist(p.id, name); renaming = null }
    com.ravi.musicspot.ui.motion.MotionAlertDialog(item = deleting, onDismissRequest = { deleting = null }, title = { p -> Text("Delete “${p.name}”?") },
        text = { p -> Text(if (p.isSynced) "It is removed from UpZe only; your account keeps it, and the next sync can bring it back." else "This can't be undone.") },
        confirmButton = { p -> TextButton(onClick = { Graph.library.deletePlaylist(p.id); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
}

private fun count(n: Int, noun: String) = if (n == 0) "None yet" else "$n $noun${if (n == 1) "" else "s"}"

@Composable
private fun Tile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clip(Ms.r20).pressable(onClickLabel = "Open $title", onClick = onClick).clip(Ms.r20).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(Ms.r12).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlaylistRow(index: Int, p: LocalPlaylist, onClick: () -> Unit, onRename: (() -> Unit)?, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().staggerIn(index).pressScale(press, 0.98f).clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        if (p.artUrl != null || p.tracks.isNotEmpty()) Artwork(p.artUrl ?: p.tracks.first().artUrl, Modifier.size(52.dp), Ms.r12, p.name)
        else Box(Modifier.size(52.dp).clip(Ms.r12).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val unmatched = remember(p.tracks) { p.tracks.count { it.source == com.ravi.musicspot.model.SourceKind.SPOTIFY } } // 1.0.5: counted once per change
            Text(buildString {
                append(if (p.tracks.isEmpty()) "Empty" else "${p.tracks.size} songs")
                if (unmatched > 0) append(" · $unmatched unmatched")
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options for ${p.name}") }
            com.ravi.musicspot.ui.motion.MotionDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play") }, enabled = p.tracks.isNotEmpty(), onClick = { menu = false; Graph.player.play(p.tracks) })
                DropdownMenuItem(text = { Text("Shuffle") }, enabled = p.tracks.isNotEmpty(), onClick = { menu = false; Graph.player.play(p.tracks, shuffle = true) })
                if (onRename != null) DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun SyncHeader(title: String, status: SyncManager.Status, onSync: () -> Unit) {
    Column {
        SectionHeader(title, subtitle = "Synced", action = if (status.running) null else "Sync", onAction = onSync)
        if (status.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        status.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (status.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}

/** Name prompt shown while [item] is non-null (v2.0.6: animated open/close via MotionDialog). */
@Composable
fun <T : Any> NameDialog(item: T?, title: String, initial: (T) -> String, confirm: String, onDismiss: () -> Unit, onConfirm: (T, String) -> Unit) =
    com.ravi.musicspot.ui.motion.MotionDialog(item, onDismiss) { t ->
        var name by remember(t) { mutableStateOf(initial(t)) } // v2.0.16: keyed, so a different item never shows the previous name
        com.ravi.musicspot.ui.motion.AlertDialogCard(title = { Text(title) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { onConfirm(t, name) }, enabled = name.isNotBlank()) { Text(confirm) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    }

