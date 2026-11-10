@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravi.musicspot.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Shelf
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.motion.SkeletonBox
import com.ravi.musicspot.ui.motion.pressable
import com.ravi.musicspot.ui.motion.sharedCover
import com.ravi.musicspot.ui.motion.shimmer
import com.ravi.musicspot.ui.motion.staggerIn
import com.ravi.musicspot.ui.openCollection
import com.ravi.musicspot.ui.theme.Ms

/** Responsive sizes from the window width (phone → tablet landscape). */
internal data class HomeDims(val width: Dp, val card: Dp, val quickCol: Dp, val gutter: Dp, val wide: Boolean)

internal fun dims(w: Dp): HomeDims {
    val wide = w >= 600.dp
    val card = when { w >= 1100.dp -> 168.dp; wide -> 152.dp; else -> 132.dp }
    val quickCols = when { w >= 1100.dp -> 3; wide -> 2; else -> 1 }
    val quick = if (quickCols == 1) w * 0.86f else (w - 32.dp - 12.dp * (quickCols - 1)) / quickCols
    return HomeDims(w, card, quick, if (wide) 20.dp else 16.dp, wide)
}

/** 1.0.6: Home is the Spotlight dashboard (the Classic, Aura and Bento designs were removed). */
@Composable
fun HomeScreen() = SpotlightHomeScreen()

// ---- pieces -------------------------------------------------------------------------------------------------------

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
        itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "quick-pick" }) { i, t -> // 1.0.5: contentType (ids deduped above)
            Row(Modifier.width(d.quickCol).height(rowH).clip(Ms.r12).pressable(onClickLabel = "Play ${t.title}", pressedScale = 0.97f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { Graph.player.play(tracks, i) }.padding(horizontal = 4.dp),
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
        itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track-card" }) { i, t -> // 1.0.5: contentType (ids deduped above)
            Column(Modifier.width(w).pressable(onClickLabel = "Play ${t.title}", onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { Graph.player.play(tracks, i) }) {
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
        items(cols, key = { it.id }, contentType = { "cover-card" }) { c -> CoverCard(c, w) { nav.openCollection(c) } }
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
        items(artists, key = { it.id }, contentType = { "artist" }) { a ->
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
                items(tracks.size, key = { "t" + tracks[it].id }, contentType = { "track-tile" }) { i -> // 1.0.5: contentType
                    val t = tracks[i]
                    Column(Modifier.pressable(onClickLabel = "Play ${t.title}", onLongClick = { com.ravi.musicspot.ui.openSongMenu(t) }) { Graph.player.play(tracks, i) }) {
                        Artwork(t.artUrl, Modifier.fillMaxWidth().aspectRatio(1f), Ms.r16, t.title)
                        Spacer(Modifier.height(6.dp))
                        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                items(collections, key = { "c" + it.id }, contentType = { "collection-tile" }) { c ->
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

// ---- shared card shadow ---------------------------------------------------------------------------------------------

/** Soft, low-contrast drop shadow (elevation shadow: hardware-rendered on every API level, no blur). */
// 1.0.8 lighter: 6 dp (was 10 dp); a big soft shadow under every card of every shelf was a lot of extra GPU fill
// while scrolling Home on older phones. Lite devices skip it (the cards still sit on a tinted placeholder).
internal fun Modifier.softShadow(shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (com.ravi.musicspot.Graph.liteFx) this
    else this.shadow(6.dp, shape, clip = false, ambientColor = SoftShadowAmbient, spotColor = SoftShadowSpot)

// 1.0.5: constant shadow colours hoisted (were re-created for every card on every composition).
private val SoftShadowAmbient = Color.Black.copy(alpha = 0.25f)
private val SoftShadowSpot = Color.Black.copy(alpha = 0.35f)
