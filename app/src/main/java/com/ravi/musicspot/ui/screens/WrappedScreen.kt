package com.ravi.musicspot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.logic.TimeOfDay
import com.ravi.musicspot.logic.Wrapped
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.glass.GlassLayer
import com.ravi.musicspot.ui.components.GlassTopBar
import com.ravi.musicspot.ui.components.rememberOffMain
import com.ravi.musicspot.ui.motion.pressable
import com.ravi.musicspot.ui.motion.staggerIn
import com.ravi.musicspot.ui.theme.Ms
import kotlin.math.roundToInt

/**
 * Pack 5 (Nayi Jeet): "Your stats", Wrapped anytime. Range picker (Week / Month / Year / All time) over the on-device
 * play counts ([Wrapped.summarize], pure and unit-tested); cards in a fixed order, each hidden when the range has no
 * data for it. Every source is counted by the same PlayStats counter, so nothing here is per source. No export yet.
 */
@Composable
fun WrappedScreen() {
    val nav = LocalNav.current
    val stats by Graph.playStats.data.collectAsState()
    var range by rememberSaveable { mutableStateOf(Wrapped.Range.MONTH) }
    val s = rememberOffMain(stats, range) { Wrapped.summarize(stats, range, System.currentTimeMillis()) }
    val header = rememberHeaderHeight()
    GlassLayer(Modifier.fillMaxSize(), overlay = { GlassTopBar("Your stats", onBack = { nav.back() }, height = header) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(top = 76.dp, header = header.value)) {
            item(key = "w-range", contentType = "range") {
                WrappedRangePicker(range) { range = it }
                Text("All your sources, counted together", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp))
            }
            if (s.isEmpty) {
                item(key = "w-empty", contentType = "empty") {
                    EmptyState(Icons.Rounded.Insights, "Nothing counted ${rangePhrase(range)}",
                        "A song counts after about 30 seconds of listening. Play some music and your stats fill in here.")
                }
            } else {
                var n = 0
                item(key = "w-total", contentType = "card") { TotalCard(s, n) }; n++
                if (s.listeningDays > 0 || s.currentStreak > 0) { val i = n++; item(key = "w-days", contentType = "card") { DaysCard(s, i) } }
                if (s.topSongs.isNotEmpty()) { val i = n++; item(key = "w-songs", contentType = "card") { TopSongsCard(s, i) } }
                if (s.topArtists.isNotEmpty()) { val i = n++; item(key = "w-artists", contentType = "card") { TopArtistsCard(s, i) } }
                s.mostRepeated?.let { r -> val i = n++; item(key = "w-repeat", contentType = "card") { RepeatCard(r, s.range, i) } }
                s.favouritePart?.let { p -> val i = n++; item(key = "w-tod", contentType = "card") { TimeOfDayCard(p, s.favouriteShare, i) } }
            }
            item(key = "w-end", contentType = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun rangePhrase(r: Wrapped.Range) = when (r) {
    Wrapped.Range.WEEK -> "in the last 7 days"; Wrapped.Range.MONTH -> "in the last 30 days"
    Wrapped.Range.YEAR -> "in the last 12 months"; Wrapped.Range.ALL -> "yet"
}

@Composable
private fun WrappedRangePicker(selected: Wrapped.Range, onSelect: (Wrapped.Range) -> Unit) {
    val c = MaterialTheme.colorScheme
    LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Wrapped.Range.entries, key = { it.name }, contentType = { "range-chip" }) { r ->
            val on = r == selected
            Box(Modifier.height(38.dp).clip(Ms.pill).background(if (on) c.primary else Color.Transparent)
                .border(1.dp, if (on) c.primary else c.outlineVariant, Ms.pill)
                .pressable(onClickLabel = r.label, pressedScale = 0.92f) { onSelect(r) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(r.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) c.onPrimary else c.onSurface, maxLines = 1)
            }
        }
    }
}

/** The shared card shell: rounded, tonal surface, a small caps label on top. */
@Composable
private fun WrappedCard(label: String, index: Int, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().staggerIn(index).padding(horizontal = 16.dp, vertical = 6.dp).clip(Ms.r20).background(c.surfaceContainerHigh).padding(16.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp), color = c.primary, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun BigNumber(value: String, caption: String) {
    Column {
        Text(value, style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, letterSpacing = (-1).sp), fontWeight = FontWeight.Black, maxLines = 1)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TotalCard(s: Wrapped.Summary, index: Int) = WrappedCard("Total plays", index) {
    BigNumber("${s.totalPlays}", (if (s.totalPlays == 1) "play " else "plays ") + rangePhrase(s.range).replace("yet", "all time"))
}

@Composable
private fun DaysCard(s: Wrapped.Summary, index: Int) = WrappedCard("Listening days", index) {
    Row(Modifier.fillMaxWidth()) {
        if (s.listeningDays > 0) Box(Modifier.weight(1f)) { BigNumber("${s.listeningDays}", if (s.listeningDays == 1) "day with music" else "days with music") }
        Box(Modifier.weight(1f)) { BigNumber("${s.currentStreak}", if (s.currentStreak == 1) "day streak" else "day streak, up to today") }
    }
}

@Composable
private fun TopSongsCard(s: Wrapped.Summary, index: Int) = WrappedCard("Top ${s.topSongs.size} songs", index) {
    val c = MaterialTheme.colorScheme
    val tracks = s.topSongs.map { it.track }
    s.topSongs.forEachIndexed { i, row ->
        Row(Modifier.fillMaxWidth().height(60.dp).clip(Ms.r12)
            .pressable(onClickLabel = "Play ${row.track.title}", pressedScale = 0.98f, onLongClick = { com.ravi.musicspot.ui.openSongMenu(row.track) }) { Graph.player.play(tracks, i) },
            verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), fontWeight = FontWeight.Black,
                color = if (i == 0) c.primary else c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
            Spacer(Modifier.width(8.dp))
            Artwork(row.track.artUrl, Modifier.size(46.dp), Ms.r8, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.track.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.track.artist, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${row.plays}", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun TopArtistsCard(s: Wrapped.Summary, index: Int) = WrappedCard("Top ${s.topArtists.size} artists", index) {
    val c = MaterialTheme.colorScheme
    s.topArtists.forEachIndexed { i, a ->
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), fontWeight = FontWeight.Black,
                color = if (i == 0) c.primary else c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
            Spacer(Modifier.width(8.dp))
            Artwork(a.artUrl, Modifier.size(44.dp), CircleShape, null)
            Spacer(Modifier.width(12.dp))
            Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (a.plays == 1) "1 play" else "${a.plays} plays", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun RepeatCard(r: Wrapped.Repeated, range: Wrapped.Range, index: Int) = WrappedCard("Most repeated", index) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(Ms.r12).pressable(onClickLabel = "Play ${r.track.title}", pressedScale = 0.98f) { Graph.player.play(listOf(r.track), 0) },
        verticalAlignment = Alignment.CenterVertically) {
        Artwork(r.track.artUrl, Modifier.size(72.dp), Ms.r12, r.track.title)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.track.artist, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Repeat, null, Modifier.size(16.dp), tint = c.primary)
                Spacer(Modifier.width(4.dp))
                // Repeats are a lifetime count per song (the day log has none); the range picks which songs qualify.
                Text((if (r.repeats == 1) "1 repeat" else "${r.repeats} repeats") + if (range == Wrapped.Range.ALL) "" else " in total",
                    style = MaterialTheme.typography.labelMedium, color = c.primary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TimeOfDayCard(p: TimeOfDay.Part, share: Double, index: Int) = WrappedCard("Favourite time of day", index) {
    val hours = when (p) { TimeOfDay.Part.MORNING -> "5 am – 12 pm"; TimeOfDay.Part.AFTERNOON -> "12 – 5 pm"; TimeOfDay.Part.EVENING -> "5 – 10 pm"; TimeOfDay.Part.NIGHT -> "10 pm – 5 am" }
    BigNumber(p.label, "$hours · about ${(share * 100).roundToInt()}% of your plays")
}
