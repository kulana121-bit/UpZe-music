/*
 * Pill notifications follow OpenTune 1.0.4 PillHost.kt (GPL-3.0-or-later): a dot that stretches into a glass pill
 * under the status bar, then shrinks away. Track/collection menus and the add-to-playlist dialog are UpZe's.
 */
package com.ravi.musicspot.ui

import androidx.compose.material.icons.rounded.Groups
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.glass.glassSheet
import com.ravi.musicspot.ui.glass.liquidGlass
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

object Pills {
    internal val queue = Channel<String>(Channel.UNLIMITED)
    fun show(text: String) { queue.trySend(text) }
}

/**
 * 1.0.1: the single entry point to the song-options sheet ([TrackMenuSheet]). The ⋮ buttons and every long-press on a
 * song row/card call this, so both always show the same items and actions. It grows out of the pressed element
 * (TapOrigin, see MotionSheet).
 */
fun openSongMenu(t: Track) { Menus.track = t }

object Menus {
    var track by mutableStateOf<Track?>(null)
    var addToPlaylist by mutableStateOf<List<Track>?>(null)
    var credits by mutableStateOf<Track?>(null)
    /** 1.0.2: set (to the entry's time) when the song menu was opened from History, so it can offer "Remove from history". */
    var historyAt by mutableStateOf<Long?>(null)
    /** 1.0.2: set when the song menu was opened inside one of the user's own playlists ("Remove from playlist"). */
    var playlistId by mutableStateOf<String?>(null)
    /** 1.0.2: set when the song menu was opened from Cached songs ("Remove from cache"). */
    var cachedId by mutableStateOf<String?>(null)
}

@Composable
fun PillHost() {
    var text by remember { mutableStateOf<String?>(null) }
    val dot = remember { Animatable(0f) }
    val stretch = remember { Animatable(0f) }
    val still = reducedMotion()
    LaunchedEffect(Unit) {
        for (msg in Pills.queue) {
            text = msg; dot.snapTo(0f); stretch.snapTo(0f)
            if (still) { stretch.snapTo(1f); dot.animateTo(1f, tween(168)) } else coroutineScope {
                launch { dot.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 265f)) }
                delay(140); stretch.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 184f))
            }
            delay((1600L + msg.length * 35L).coerceAtMost(4500))
            stretch.animateTo(0f, tween(280)); dot.animateTo(0f, tween(210))
            text = null
        }
    }
    val t = text ?: return
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            t, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 420.dp).heightIn(min = 40.dp).semantics { liveRegion = LiveRegionMode.Polite }
                .graphicsLayer { val d = dot.value; scaleX = 0.4f + 0.6f * stretch.value.coerceIn(0f, 1f); scaleY = 0.6f + 0.4f * d; alpha = d.coerceIn(0f, 1f); translationY = -18.dp.toPx() * (1 - d) }
                .glass(Ms.pill, strong = true).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/**
 * The song menu (⋮ / long-press anywhere, and the player's More). v2.0.6: a MotionSheet, so it slides up on a spring
 * over a fading scrim and slides back down when closed (any action, back, scrim tap or drag down).
 */
@Composable
fun TrackMenuSheet(nav: Navigator, onOpenPlayerClose: () -> Unit) = com.ravi.musicspot.ui.motion.MotionSheet(Menus.track, onDismiss = { Menus.track = null; Menus.historyAt = null; Menus.playlistId = null; Menus.cachedId = null }) { t ->
    val ctx = LocalContext.current
    val lib by Graph.library.data.collectAsState()
    val dls by Graph.downloads.items.collectAsState()
    val liked = lib.liked.any { it.id == t.id }
    // 1.0.2: "Play next" makes no sense for the song that is already playing (the player's ⋮), so it is hidden then.
    val playerState by Graph.player.state.collectAsState()
    val isCurrent = playerState.current?.id == t.id
    val historyAt = Menus.historyAt
    val inPlaylist = Menus.playlistId
    val fromCache = Menus.cachedId
    val roomNow by com.ravi.musicspot.together.ListenTogether.room.collectAsState()
    val inRoomAsListener = roomNow?.role == com.ravi.musicspot.together.ListenTogether.Role.LISTENER && !t.source.isLive
    fun close() { Menus.track = null; Menus.historyAt = null; Menus.playlistId = null; Menus.cachedId = null }
    val stagger = remember(t.id) { java.util.concurrent.atomic.AtomicInteger(0) }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface, LocalMenuStagger provides stagger) {
        // v2.0.10 iOS-style focus: the chosen song lifts out as its own floating "cutout" card (pops in on a soft
        // spring with a deep shadow) over the blurred app, with the options sheet below it.
        val stillCut = com.ravi.musicspot.ui.motion.reducedMotion()
        val lift = remember(t.id) { Animatable(if (stillCut) 1f else 0f) }
        androidx.compose.runtime.LaunchedEffect(t.id) { if (!stillCut) lift.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f)) } // 1.0.8: was 180
        Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth()
            .graphicsLayer { val v = lift.value; scaleX = 0.9f + 0.1f * v; scaleY = scaleX; alpha = v.coerceIn(0f, 1f); translationY = (1f - v) * 40.dp.toPx()
                shadowElevation = 28.dp.toPx() * v; shape = Ms.r28; clip = false }
            .liquidGlass(Ms.r28).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(t.artUrl, Modifier.size(72.dp).graphicsLayer { shadowElevation = 10.dp.toPx(); shape = Ms.r20; clip = true }, Ms.r20)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${t.artist} · ${t.source.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Column(Modifier.glassSheet(Ms.r28).navigationBarsPadding().padding(top = 8.dp, bottom = 8.dp)) {
            if (fromCache != null) MenuRow(Icons.Rounded.Delete, "Remove from cache") { Graph.cached.remove(fromCache); Pills.show("Removed from cache"); close() }
            if (inRoomAsListener) MenuRow(Icons.Rounded.Groups, "Send to the room") { com.ravi.musicspot.together.ListenTogether.suggest(t); close() }
            if (inPlaylist != null) MenuRow(Icons.Rounded.Delete, "Remove from playlist") { Graph.library.removeFromPlaylist(inPlaylist, t.id); Pills.show("Removed from playlist"); close() }
            if (historyAt != null) MenuRow(Icons.Rounded.Delete, "Remove from history") { Graph.library.removeHistory(t.id, historyAt); Pills.show("Removed from history"); close() }
            if (!isCurrent) MenuRow(Icons.Rounded.SkipNext, "Play next") { Graph.player.playNext(t); Pills.show("Plays next"); close() }
            // 1.0.2: the playing song's menu (player ⋮) can cast/switch output and pin the song to the home screen.
            if (isCurrent) {
                // 1.0.8: switch Now Playing between the normal artwork and Live lyrics (kinetic, beat-reactive lyrics).
                val liveOn = Graph.settings.state.collectAsState().value.liveLyrics
                MenuRow(if (liveOn) Icons.Rounded.Album else Icons.Rounded.AutoAwesome, if (liveOn) "Turn off Motion Lyrics" else "Motion Lyrics") {
                    Graph.settings.update { it.copy(liveLyrics = !it.liveLyrics) }
                    Pills.show(if (liveOn) "Motion Lyrics off" else "Motion Lyrics on. Lyrics dance over the artwork")
                    close()
                }
                MenuRow(Icons.Rounded.Cast, "Cast / play on another device") {
                    if (!com.ravi.musicspot.core.TrackShortcuts.openOutputPicker(ctx)) Pills.show("No cast or output picker on this phone"); close()
                }
                if (com.ravi.musicspot.core.TrackShortcuts.supported(ctx)) MenuRow(Icons.Rounded.PushPin, "Pin to home screen") {
                    val app = ctx.applicationContext
                    com.ravi.musicspot.core.IoScope.launch {
                        val ok = com.ravi.musicspot.core.TrackShortcuts.pin(app, t)
                        Pills.show(if (ok) "Pinned. Confirm on your home screen if asked" else "Your launcher doesn't allow pinning")
                    }
                    close()
                }
            }
            MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { Graph.player.addToQueue(listOf(t)); Pills.show("Added to queue"); close() }
            MenuRow(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Remove from Liked" else "Like") {
                Graph.library.setLiked(t, !liked); Pills.show(if (liked) "Removed from Liked" else "Added to Liked"); close()
            }
            MenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { Menus.addToPlaylist = listOf(t); close() }
            if (t.source == SourceKind.YOUTUBE) MenuRow(Icons.Rounded.Radio, "Start radio") {
                Graph.player.play(listOf(t)); Pills.show("Starting radio…"); close()
            }
            val dl = dls[t.id]
            if (dl?.state == com.ravi.musicspot.data.DownloadEntry.State.DONE) MenuRow(Icons.Rounded.Delete, "Remove download") { Graph.downloads.delete(t.id); close() }
            else if (t.source != SourceKind.LOCAL && !t.source.isLive) MenuRow(Icons.Rounded.Download, if (dl != null) "Downloading…" else "Download") {
                if (dl == null) { Graph.downloads.enqueue(t, Graph.settings.value.wifiOnlyDownloads); Pills.show("Download started") }; close()
            }
            t.artistId?.let { aid -> MenuRow(Icons.Rounded.Person, "Go to artist") { onOpenPlayerClose(); nav.push(Route.Artist(aid, t.artist)); close() } }
            t.albumId?.takeIf { it.startsWith("yt:") }?.let { aid -> MenuRow(Icons.Rounded.Album, "Go to album") {
                onOpenPlayerClose(); nav.push(Route.Remote(Collection(aid, Collection.Kind.ALBUM, t.album ?: "Album", t.artist, t.artUrl))); close() } }
            MenuRow(Icons.Rounded.Info, "Credits & stream info") { Menus.credits = t; close() }
            MenuRow(Icons.Rounded.Share, "Share") {
                val link = when (t.source) {
                    SourceKind.YOUTUBE -> "https://music.youtube.com/watch?v=${t.nativeId}"
                    SourceKind.SPOTIFY -> "https://open.spotify.com/track/${t.nativeId}"
                    SourceKind.NETEASE -> "https://music.163.com/#/song?id=${t.nativeId}"
                    SourceKind.MONOCHROME -> "https://tidal.com/browse/track/${t.nativeId}"
                    SourceKind.QOBUZ -> "https://open.qobuz.com/track/${t.nativeId}"
                    SourceKind.SOUNDCLOUD -> t.nativeId
                    else -> null
                }
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, listOfNotNull("${t.title} — ${t.artist}", link).joinToString("\n")), "Share song"))
                close()
            }
        }
    }
}

val LocalMenuStagger = androidx.compose.runtime.compositionLocalOf<java.util.concurrent.atomic.AtomicInteger?> { null }

@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    // v2.0.7b: options cascade in one after another (rise + fade + slight scale on a soft spring).
    val counter = LocalMenuStagger.current
    val idx = remember { counter?.getAndIncrement() ?: -1 }
    val still = com.ravi.musicspot.ui.motion.reducedMotion()
    val a = remember { Animatable(if (idx < 0 || still) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (a.value < 1f) { kotlinx.coroutines.delay(20L + 20L * idx); a.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 320f)) } } // 1.0.8: was 40+35/row, stiffness 133
    Row(Modifier.fillMaxWidth().graphicsLayer { val v = a.value; alpha = v.coerceIn(0f, 1f); translationY = (1f - v) * 28.dp.toPx(); scaleX = 0.94f + 0.06f * v; scaleY = scaleX }
        .clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** v2.0.6: animated (scale from 0.9 + fade over a fading scrim; reverses on close). */
@Composable
fun AddToPlaylistDialog() = com.ravi.musicspot.ui.motion.MotionDialog(Menus.addToPlaylist, onDismiss = { Menus.addToPlaylist = null }) { tracks ->
    val lib by Graph.library.data.collectAsState()
    var newName by remember { mutableStateOf("") }
    com.ravi.musicspot.ui.motion.AlertDialogCard(
        title = { Text("Add to playlist") },
        text = {
            Column {
                OutlinedTextField(newName, { newName = it }, label = { Text("New playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val own = lib.playlists.filter { !it.isSynced }.distinctBy { it.id } // 1.0.7: keyed list
                if (own.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 280.dp).padding(top = 8.dp)) {
                    items(own, key = { it.id }, contentType = { "playlist" }) { p ->
                        Row(Modifier.fillMaxWidth().clickable {
                            tracks.forEach { Graph.library.addToPlaylist(p.id, it) }; Pills.show("Added to ${p.name}"); Menus.addToPlaylist = null
                        }.padding(vertical = 12.dp)) { Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                } else Text("No playlists of your own yet — name one above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = {
            val p = Graph.library.createPlaylist(newName, tracks); Pills.show("Created ${p.name}"); Menus.addToPlaylist = null
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { Menus.addToPlaylist = null }) { Text("Cancel") } },
    )
}
