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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
fun ArtistPickerStep(modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val selected by SeedArtists.state.collectAsState()
    val online by Graph.online.collectAsState()
    val selectedIds = remember(selected) { selected.mapTo(HashSet()) { it.id } }
    var query by rememberSaveable { mutableStateOf("") }
    // 1.0.1: suggestions are cached in SeedArtists for the session, so leaving the step (the setup pager disposes
    // off-screen pages), rotating, or reopening the picker from Settings no longer refetches ~13 network calls.
    var suggested by remember { mutableStateOf(SeedArtists.cachedSuggestions) }
    var results by remember { mutableStateOf<List<SeedArtist>?>(null) }
    // v2.0.16: suggestions and search each own a loading flag; one finishing no longer hides the other's spinner.
    var loadingSug by remember { mutableStateOf(false) }
    var loadingSearch by remember { mutableStateOf(false) }
    val loading = loadingSug || loadingSearch
    // 1.0.1: separate errors: a failed search no longer leaves its message (and Retry) on the suggestions view.
    var sugError by remember { mutableStateOf<String?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val q = query.trim()

    LaunchedEffect(retry, online) {
        if (suggested.isNotEmpty()) return@LaunchedEffect
        if (!online) { sugError = "You're offline. Connect to see artists."; return@LaunchedEffect }
        loadingSug = true; sugError = null
        try {
            suggested = SeedArtists.suggested(24)
            if (suggested.isEmpty()) sugError = "Couldn't load artists right now." else SeedArtists.cachedSuggestions = suggested
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            sugError = "Couldn't load artists. Check your connection."
        } finally { loadingSug = false }
    }

    LaunchedEffect(q, retry) {
        searchError = null
        if (q.isEmpty()) { results = null; return@LaunchedEffect }
        delay(350)
        // v2.0.16: drop the previous query's results so they are not shown under the new query.
        if (!Graph.online.value) { results = emptyList(); searchError = "You're offline. Connect to search artists."; return@LaunchedEffect }
        loadingSearch = true
        try {
            results = SeedArtists.search(q)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            results = emptyList(); searchError = "Search failed. Check your connection."
        } finally { loadingSearch = false }
    }
    val error = if (q.isNotEmpty()) searchError else sugError

    // Picked artists stay visible on top of the suggestions so they can be un-picked.
    val shown: List<SeedArtist> = results ?: (selected + suggested).distinctBy { it.id }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        if (showTitle) {
            Spacer(Modifier.height(16.dp))
            Text("Pick artists you love", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Optional — we'll tune Home to them", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                    items(shown, key = { it.id }, contentType = { "artist" }) { a -> ArtistBubble(a, a.id in selectedIds) { SeedArtists.toggle(a) } }
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
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth(), // 1.0.1: was a fixed 112 dp, wider than a 96 dp cell
        )
    }
}

/**
 * 1.0.1: the artist picker as its own screen, opened from Settings (Appearance › Your artists and the Settings list).
 * Selections are saved as they are tapped (SeedArtists), so Back and Done both keep them.
 */
@Composable
fun ArtistsScreen(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Your artists", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Home's “For you” follows the artists you pick", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onBack) { Text("Done") }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                ArtistPickerStep(Modifier.widthIn(max = 900.dp), showTitle = false)
            }
        }
    }
}
