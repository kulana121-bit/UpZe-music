package com.ravi.musicspot.ui.components

import com.ravi.musicspot.ui.motion.pressScale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.ravi.musicspot.Graph
import com.ravi.musicspot.data.DownloadEntry
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.motion.pressable
import com.ravi.musicspot.ui.motion.sharedCover
import com.ravi.musicspot.ui.theme.Ms

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, shape: Shape = Ms.r12, contentDescription: String? = null) {
    // v2.0.5: plain AsyncImage over a static placeholder (SubcomposeAsyncImage subcomposed per load state, which was
    // the slowest part of long lists) and the artwork URL asked for at the size it is drawn at (see sizedArtUrl).
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        var failed by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(false) }
        var loaded by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(false) }
        if (url == null || failed || !loaded) ArtPlaceholder()
        if (url != null && !failed) {
            val px = with(androidx.compose.ui.platform.LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }
            // v2.0.14b: bucket the requested size and remember the request per (url, bucket) so the URL stays stable
            // while the size animates (cover morph). The bucket only grows per url, so a shrinking morph reuses the
            // already-loaded larger rendition instead of re-requesting a smaller one.
            val bucket = artBucket(px).let { if (Graph.liteFx) minOf(it, 480) else it } // 1.0.1: smaller decodes on lite devices
            val maxBucket = androidx.compose.runtime.remember(url) { IntArray(1) } // plain holder: no state write in composition
            if (bucket > maxBucket[0]) maxBucket[0] = bucket
            val reqBucket = maxBucket[0]
            val model = androidx.compose.runtime.remember(url, reqBucket) { sizedArtUrl(url, reqBucket) }
            coil3.compose.AsyncImage(
                model = model, contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true }, onError = { failed = true },
            )
        }
    }
}

/** v2.0.14b: rounds a drawn size (px) up to a fixed set so animating sizes map to few distinct requests. */
private fun artBucket(px: Int): Int = when { px <= 0 -> 0; px <= 120 -> 120; px <= 240 -> 240; px <= 480 -> 480; px <= 720 -> 720; else -> 1080 }

/**
 * Asks artwork CDNs for a rendition close to the drawn size (in px, bucketed so the disk cache is shared):
 * googleusercontent/ggpht `=wN-hN`, JioSaavn `NxN` (50/150/500). Other URLs are returned unchanged.
 */
fun sizedArtUrl(url: String, px: Int): String {
    if (px <= 0) return url
    val bucket = when { px <= 160 -> 160; px <= 320 -> 320; px <= 544 -> 544; else -> if (Graph.liteFx) 544 else 1080 } // 1.0.1: lite cap
    return when {
        ("googleusercontent.com" in url || "ggpht.com" in url) && GoogleSizeRe.containsMatchIn(url) ->
            url.replace(GoogleSizeRe, "=w$bucket-h$bucket")
        "saavncdn.com" in url && SaavnSizeRe.containsMatchIn(url) ->
            url.replace(SaavnSizeRe, if (bucket <= 160) "150x150" else "500x500")
        else -> url
    }
}

// 1.0.5: compiled once (were compiled up to twice per call, i.e. per artwork per new size bucket).
private val GoogleSizeRe = Regex("=w\\d+-h\\d+")
private val SaavnSizeRe = Regex("\\d+x\\d+")

@Composable
private fun ArtPlaceholder() {
    // 1.0.5: brush and tint remembered per theme colours (this placeholder is drawn under every list artwork until it loads).
    val a = MaterialTheme.colorScheme.surfaceContainerHighest
    val b = MaterialTheme.colorScheme.surfaceContainerHigh
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    val brush = remember(a, b) { Brush.linearGradient(listOf(a, b)) }
    val tint = remember(onVar) { onVar.copy(alpha = 0.6f) }
    Box(Modifier.fillMaxSize().background(brush), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = tint, modifier = Modifier.size(28.dp))
    }
}

fun formatTime(ms: Long): String { val s = (ms.coerceAtLeast(0) / 1000); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
fun formatBytes(b: Long): String = when { b >= 1L shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024)); b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024)); b >= 1024 -> "%.0f KB".format(b / 1024.0); else -> "$b B" }

@Composable
fun TrackRow(
    t: Track, onClick: () -> Unit, onMore: (() -> Unit)? = null, playing: Boolean = false, index: Int? = null,
    showArt: Boolean = true, trailing: (@Composable () -> Unit)? = null,
    /** 1.0.1: long-press opens the same song-options sheet as ⋮ (pass null to turn it off). */
    onLongPress: (() -> Unit)? = { com.ravi.musicspot.ui.openSongMenu(t) },
) {
    val rowHaptics = com.ravi.musicspot.ui.motion.rememberHaptics()
    val downloads by Graph.downloads.items.collectAsState()
    val liked by Graph.library.data.collectAsState()
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // 1.0.5: per-row derived values remembered: the liked scan was O(liked) per row on every recomposition, and the
    // meta line (list + joinToString + duration format) was rebuilt on every recomposition of the row.
    val isLiked = remember(liked.liked, t.id) { liked.liked.any { it.id == t.id } }
    val meta = remember(t.artist, t.album, t.title, t.durationMs) {
        listOfNotNull(t.artist.ifBlank { null }, t.album?.takeIf { it.isNotBlank() && it != t.title }, t.durationMs.takeIf { it > 0 }?.let(::formatTime)).joinToString(" · ")
    }
    // v2.0.5: rows with a menu (album, playlist, artist, search, liked/history lists) get swipe actions; the queue does not.
    SwipeRow(enabled = onMore != null,
        onSwipeRight = { Graph.player.playNext(t); com.ravi.musicspot.ui.Pills.show("Playing next") },
        onSwipeLeft = { Graph.player.addToQueue(listOf(t)); com.ravi.musicspot.ui.Pills.show("Added to queue") }) {
    Row(
        Modifier.fillMaxWidth().pressScale(press, 0.98f).combinedClickable(interactionSource = press, indication = androidx.compose.material3.ripple(), onClick = onClick,
            onLongClickLabel = if (onLongPress != null) "Song options" else null,
            onLongClick = onLongPress?.let { lp -> { rowHaptics.confirm(); lp() } }).padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) Text("$index", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        if (showArt) { Artwork(t.artUrl, Modifier.size(48.dp), Ms.r8); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.explicit) { Badge("E"); Spacer(Modifier.width(4.dp)) }
                if (downloads[t.id]?.state == DownloadEntry.State.DONE) { Icon(Icons.Rounded.DownloadDone, "Downloaded", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                if (isLiked) { Icon(Icons.Rounded.Favorite, "Liked", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                Text(meta,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
        if (onMore != null) IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "More options for ${t.title}") }
    }
    }
}

@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun CollectionCard(c: Collection, width: Dp = 152.dp, onClick: () -> Unit) {
    val round = c.kind == Collection.Kind.ARTIST
    Column(Modifier.width(width).pressable(onClickLabel = "Open ${c.title}", onClick = onClick)) {
        Artwork(c.artUrl, Modifier.size(width).aspectRatio(1f).sharedCover("cover:${c.id}"), if (round) CircleShape else Ms.r16, c.title)
        Spacer(Modifier.height(8.dp))
        Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        c.subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (round) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun TrackCard(t: Track, width: Dp = 152.dp, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    Column(Modifier.width(width).pressable(onClickLabel = "Play ${t.title}", onLongClick = onLong ?: { com.ravi.musicspot.ui.openSongMenu(t) }, onClick = onClick)) { // 1.0.1: long-press = song options
        Artwork(t.artUrl, Modifier.size(width), Ms.r16, t.title)
        Spacer(Modifier.height(8.dp))
        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            subtitle?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null && onAction != null) androidx.compose.material3.TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun <T> HorizontalShelf(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    // v2.0.14: duplicate keys crash LazyRow; dedupe by key first.
    val unique = remember(items) { items.distinctBy(key) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(unique, key = { key(it) }, contentType = { "shelf-card" }) { content(it) } // 1.0.5: contentType
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: String? = null, modifier: Modifier = Modifier, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) { Spacer(Modifier.height(16.dp)); FilledTonalButton(onClick = onAction) { Text(action) } }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    val online by Graph.online.collectAsState()
    EmptyState(
        if (online) Icons.Rounded.ErrorOutline else Icons.Rounded.CloudOff,
        if (online) "Couldn't load this" else "You're offline",
        if (online) message else "Connect to the internet to browse. Your downloads and music on this device still play.",
        "Retry", onAction = onRetry,
    )
    if (secondary != null && onSecondary != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { androidx.compose.material3.TextButton(onClick = onSecondary) { Text(secondary) } }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 3.dp) }
}

/** Slim banner shown while there is no connection; tells the user what still works. */
@Composable
fun OfflineBanner() {
    val online by Graph.online.collectAsState()
    AnimatedVisibility(!online, enter = expandVertically(), exit = shrinkVertically()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Offline — downloads and music on this device still play", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** A page top bar: glass back button + title. Used inside GlassLayer overlays. */
@Composable
fun GlassTopBar(title: String, onBack: (() -> Unit)?, height: androidx.compose.runtime.MutableState<Dp>? = null, actions: @Composable () -> Unit = {}) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // v2.0.17: report the bar's real height (status bar + pill, grows with font scale) so lists pad below it.
    val measure = if (height != null) Modifier.onSizeChanged { height.value = with(density) { it.height.toDp() } } else Modifier
    // v2.0.17: soft scrim behind the status bar, so list content (e.g. the pill-shaped search field's outline) scrolling
    // up under the transparent bar no longer shows as a stray arc across the status bar.
    val bg = MaterialTheme.colorScheme.background
    val sbTop = WindowInsets.statusBars.getTop(density)
    val scrim = Modifier.drawBehind {
        val h = size.height.coerceAtLeast(1f); val sb = (sbTop / h).coerceIn(0f, 0.9f)
        drawRect(Brush.verticalGradient(0f to bg.copy(alpha = 0.96f), sb to bg.copy(alpha = 0.90f), 1f to bg.copy(alpha = 0f)))
    }
    Row(Modifier.fillMaxWidth().then(measure).then(scrim).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) Surface(onClick = onBack, shape = CircleShape, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).glass(CircleShape)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.glass(Ms.pill).padding(horizontal = 16.dp, vertical = 10.dp))
        }
        actions()
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Suppress("unused") @Composable private fun MoreIcon() = Icon(Icons.Rounded.MoreVert, null)

/**
 * 1.0.2: a soft, "blurred" artwork wash without a blur pass. The art is decoded at 32 px and stretched with bilinear
 * filtering, which reads as a heavy blur but costs one tiny bitmap draw. The old Modifier.blur was a full-screen
 * RenderEffect that re-ran whenever anything above it animated, and every glass control sampling the player
 * background had to blur that blurred layer again. Same look on every Android version; songs crossfade (700 ms).
 */
@Composable
fun SoftArt(url: String?, modifier: Modifier = Modifier) {
    val still = com.ravi.musicspot.ui.motion.reducedMotion()
    androidx.compose.animation.Crossfade(url, modifier, animationSpec = androidx.compose.animation.core.tween(if (still) 0 else 420), label = "softArt") { u -> // 1.0.8: was 700
        if (u != null) {
            val ctx = coil3.compose.LocalPlatformContext.current
            val req = androidx.compose.runtime.remember(u) { coil3.request.ImageRequest.Builder(ctx).data(sizedArtUrl(u, 120)).size(32, 32).build() }
            coil3.compose.AsyncImage(
                model = req, contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low, modifier = Modifier.fillMaxSize(),
            )
        } else Box(Modifier.fillMaxSize())
    }
}
