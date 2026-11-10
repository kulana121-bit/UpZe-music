package com.ravi.musicspot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.model.Shelf
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.components.CollectionCard
import com.ravi.musicspot.ui.components.ErrorState
import com.ravi.musicspot.ui.components.GlassTopBar
import com.ravi.musicspot.ui.components.HorizontalShelf
import com.ravi.musicspot.ui.components.Loading
import com.ravi.musicspot.ui.components.OfflineBanner
import com.ravi.musicspot.ui.components.SectionHeader
import com.ravi.musicspot.ui.components.TrackCard
import com.ravi.musicspot.ui.glass.GlassLayer
import com.ravi.musicspot.ui.motion.pressable
import com.ravi.musicspot.ui.openCollection
import com.ravi.musicspot.ui.theme.Ms
import com.ravi.musicspot.yt.MoodSection

@Composable
fun ExploreScreen() {
    var seg by rememberSaveable { mutableStateOf(0) }
    val segs = ExploreSegments // 1.0.5: constant list hoisted (was re-allocated every recomposition)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding()) {
        item(key = "offline", contentType = "offline") { OfflineBanner() } // 1.0.5: key + contentType on every item
        item(key = "title", contentType = "title") { Text("Explore", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp)) }
        item(key = "segments", contentType = "chip-row") {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(segs.size, key = { "seg:$it" }, contentType = { "chip" }) { i -> FilterChip(seg == i, { seg = i }, label = { Text(segs[i]) }) }
            }
        }
        when (seg) {
            0 -> item(key = "sec-charts", contentType = "sec-charts") { ChartsSection() }
            1 -> item(key = "sec-new", contentType = "sec-new") { ShelvesSection("new") { Graph.yt.newReleases() } }
            else -> item(key = "sec-moods", contentType = "sec-moods") { MoodsSection() }
        }
    }
}

private val ExploreSegments = listOf("Charts", "New releases", "Moods & genres")

@Composable
private fun ChartsSection() {
    val settings by Graph.settings.state.collectAsState()
    val load = rememberLoad(settings.region, settings.enabledSources) {
        val yt = if (SourceKind.YOUTUBE in settings.enabledSources) Graph.yt.charts(settings.region ?: Graph.tube.region) else emptyList()
        val js = if (SourceKind.JIOSAAVN in settings.enabledSources) runCatching { listOf(Graph.jiosaavn.charts()) }.getOrDefault(emptyList()) else emptyList()
        yt + js.filter { it.collections.isNotEmpty() }
    }
    ShelfList(load)
}

@Composable
private fun ShelvesSection(key: String, block: suspend () -> List<Shelf>) = ShelfList(rememberLoad(key) { block() })

@Composable
private fun ShelfList(load: Loader<List<Shelf>>) {
    val nav = LocalNav.current
    com.ravi.musicspot.ui.motion.StateCrossfade(load.state) { s -> when (s) {
        is Load.Loading -> Loading()
        is Load.Failed -> ErrorState(s.message, load.retry)
        is Load.Ok -> Column {
            if (s.data.isEmpty()) ErrorState("No sources returned anything. Check Settings → Sources.", load.retry)
            s.data.forEach { shelf ->
                SectionHeader(shelf.title, shelf.subtitle)
                if (shelf.tracks.isNotEmpty()) HorizontalShelf(shelf.tracks, { it.id }) { t -> TrackCard(t, 140.dp, onClick = { Graph.player.play(shelf.tracks, shelf.tracks.indexOf(t)) }) }
                if (shelf.collections.isNotEmpty()) HorizontalShelf(shelf.collections, { it.id }) { c -> CollectionCard(c, 140.dp) { nav.openCollection(c) } }
            }
            Spacer(Modifier.height(16.dp))
        }
    } }
}

@Composable
private fun MoodsSection() {
    val nav = LocalNav.current
    val load = rememberLoad("moods") { Graph.yt.moods() }
    when (val s = load.state) {
        is Load.Loading -> Loading()
        is Load.Failed -> ErrorState(s.message, load.retry)
        is Load.Ok -> Column(Modifier.padding(horizontal = 16.dp)) {
            s.data.forEach { sec: MoodSection ->
                Text(sec.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                val pairs = remember(sec.moods) { sec.moods.chunked(2) } // 1.0.5: chunk once per data change
                pairs.forEachIndexed { row, pair ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEachIndexed { col, m ->
                            val hue = ((row * 2 + col) * 47 + sec.title.length * 13) % 360
                            // 1.0.5: tile gradient remembered per hue instead of re-allocated on every recomposition.
                            val tileBrush = remember(hue) { Brush.linearGradient(listOf(Color.hsl(hue.toFloat(), 0.28f, 0.30f), Color.hsl(hue.toFloat(), 0.22f, 0.18f))) }
                            Box(
                                Modifier.weight(1f).heightIn(min = 64.dp).pressable(onClickLabel = m.title) { nav.push(Route.Mood(m.title, m.browseId, m.params)) }
                                    .background(tileBrush, Ms.r16)
                                    .padding(14.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) { Text(m.title, style = MaterialTheme.typography.titleSmall, color = Color(0xFFF2F0EE), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun MoodScreen(r: Route.Mood) {
    val nav = LocalNav.current
    val header = rememberHeaderHeight() // v2.0.17
    GlassLayer(Modifier.fillMaxSize(), overlay = { GlassTopBar(r.title, onBack = { nav.back() }, height = header) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(top = 72.dp, header = header.value)) {
            item(key = "mood-shelves", contentType = "shelf-list") { ShelfList(rememberLoad(r.browseId, r.params) { Graph.yt.shelvesOf(r.browseId, r.params) }) }
        }
    }
}
