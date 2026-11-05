package com.ravi.musicspot.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.data.SeedArtist
import com.ravi.musicspot.data.SeedArtists
import com.ravi.musicspot.ui.components.Artwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * v2.0.15: optional artist picker (OpenTune-style onboarding). Self-contained: reads and writes [SeedArtists];
 * Home's "For you · based on your artists" shelf follows the selection. Suggestions come from YT Music
 * (charts' artist cards + artist searches); the search box searches artists, debounced 350 ms (a new query
 * cancels the previous search because LaunchedEffect restarts on the key).
 */
@Composable
fun ArtistPickerStep(modifier: Modifier = Modifier) {
    val selected by SeedArtists.state.collectAsState()
    val online by Graph.online.collectAsState()
    val selectedIds = remember(selected) { selected.mapTo(HashSet()) { it.id } }
    var query by rememberSaveable { mutableStateOf("") }
    var suggested by remember { mutableStateOf<List<SeedArtist>>(emptyList()) }
    var results by remember { mutableStateOf<List<SeedArtist>?>(null) }
    // v2.0.16: suggestions and search each own a loading flag; one finishing no longer hides the other's spinner.
    var loadingSug by remember { mutableStateOf(false) }
    var loadingSearch by remember { mutableStateOf(false) }
    val loading = loadingSug || loadingSearch
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val q = query.trim()

    LaunchedEffect(retry, online) {
        if (suggested.isNotEmpty()) return@LaunchedEffect
        if (!online) { error = "You're offline. Connect to see artists."; return@LaunchedEffect }
        loadingSug = true; error = null
        try {
            suggested = SeedArtists.suggested(24)
            if (suggested.isEmpty()) error = "Couldn't load artists right now."
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = "Couldn't load artists. Check your connection."
        } finally { loadingSug = false }
    }

    LaunchedEffect(q, retry) {
        if (q.isEmpty()) { results = null; return@LaunchedEffect }
        delay(350)
        // v2.0.16: drop the previous query's results so they are not shown under the new query.
        if (!Graph.online.value) { results = emptyList(); error = "You're offline. Connect to search artists."; return@LaunchedEffect }
        loadingSearch = true; error = null
        try {
            results = SeedArtists.search(q)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            results = emptyList(); error = "Search failed. Check your connection."
        } finally { loadingSearch = false }
    }

    // Picked artists stay visible on top of the suggestions so they can be un-picked.
    val shown: List<SeedArtist> = results ?: (selected + suggested).distinctBy { it.id }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Pick artists you love", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Optional — we'll tune Home to them", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search artists") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
            shape = RoundedCornerShape(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = if (selected.isEmpty()) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    "${selected.size} selected", style = MaterialTheme.typography.labelLarge,
                    color = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            if (selected.isNotEmpty()) TextButton(onClick = { SeedArtists.clear() }) { Text("Clear") }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                shown.isEmpty() && error != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = { retry++ }) { Text("Retry") }
                }
                shown.isEmpty() && results != null && !loading -> Text(
                    "No artists found for \"$q\"", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center),
                )
                shown.isEmpty() && loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(shown, key = { it.id }) { a -> ArtistBubble(a, a.id in selectedIds) { SeedArtists.toggle(a) } }
                }
            }
        }
    }
}

@Composable
private fun ArtistBubble(a: SeedArtist, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Artwork(
                a.thumbnailUrl,
                Modifier.fillMaxSize().then(if (selected) Modifier.border(BorderStroke(3.dp, accent), CircleShape).padding(5.dp) else Modifier),
                shape = CircleShape, contentDescription = a.name,
            )
            if (selected) Box(
                Modifier.align(Alignment.BottomEnd).size(26.dp).clip(CircleShape).background(accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            a.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(112.dp),
        )
    }
}
