package com.ravi.musicspot.ui.screens

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.layout.Box
import com.ravi.musicspot.ui.motion.staggerIn
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ravi.musicspot.Graph
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.Pills
import com.ravi.musicspot.ui.components.CollectionCard
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.components.ErrorState
import com.ravi.musicspot.ui.components.HorizontalShelf
import com.ravi.musicspot.ui.components.Loading
import com.ravi.musicspot.ui.components.OfflineBanner
import com.ravi.musicspot.ui.components.SectionHeader
import com.ravi.musicspot.ui.components.TrackRow
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.openCollection
import com.ravi.musicspot.ui.theme.Ms
import com.ravi.musicspot.yt.SearchFilter
import kotlinx.coroutines.delay

private enum class Where(val label: String, val kind: SourceKind?) {
    YT("YouTube Music", SourceKind.YOUTUBE), JS("JioSaavn", SourceKind.JIOSAAVN), DEVICE("On this device", SourceKind.LOCAL),
    SERVERS("My servers", null), AUDIUS("Audius", SourceKind.AUDIUS), SC("SoundCloud", SourceKind.SOUNDCLOUD),
    JM("Jamendo", SourceKind.JAMENDO), IA("Internet Archive", SourceKind.ARCHIVE),
}

private data class Results(val tracks: List<Track>, val collections: List<Collection>, val top: Track? = null)

@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val settings by Graph.settings.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    var where by rememberSaveable { mutableStateOf(Where.YT) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var listening by remember { mutableStateOf(false) }
    val available = Where.entries.filter { w ->
        when (w) { Where.SERVERS -> Graph.subsonic.available() || Graph.jellyfin.available(); Where.DEVICE -> true; else -> w.kind in settings.enabledSources }
    }

    fun submit(q: String) {
        val s = q.trim(); if (s.isEmpty()) return
        input = s; query = s; suggestions = emptyList(); focus.clearFocus()
        Graph.settings.update { it.copy(recentSearches = (listOf(s) + it.recentSearches.filterNot { r -> r.equals(s, true) }).take(10)) }
    }

    // Real voice search: Android SpeechRecognizer; RECORD_AUDIO is requested on first use only.
    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }
    fun listen() {
        val r = recognizer ?: run { Pills.show("Voice search isn't available on this device"); return }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) { listening = false; results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::submit) }
            override fun onPartialResults(partialResults: Bundle?) { partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input = it } }
            override fun onError(error: Int) { listening = false; Pills.show(if (error == SpeechRecognizer.ERROR_NO_MATCH) "Didn't catch that" else "Voice search error ($error)") }
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true).putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a song or artist"))
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) listen() else Pills.show("Microphone permission is needed for voice search") }

    // v2.0.16: keyed on query too, so submitting the text already typed cancels an in-flight suggestion fetch
    // (it used to pop the list back up over the results); cancellation is rethrown instead of writing an empty list.
    LaunchedEffect(input, query) {
        if (input.isBlank() || input == query) { suggestions = emptyList(); return@LaunchedEffect }
        delay(250)
        if (SourceKind.YOUTUBE in settings.enabledSources) {
            val sug = try { Graph.yt.suggestions(input) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            suggestions = sug.take(6)
        }
    }

    val results = rememberLoad(query, filter, where) {
        if (query.isBlank()) return@rememberLoad Results(emptyList(), emptyList())
        when (where) {
            Where.YT -> Graph.yt.search(query, filter).let { Results(it.tracks, it.collections, it.top) }
            Where.IA -> Results(emptyList(), Graph.archive.searchItems(query))
            Where.SERVERS -> Results((runCatching { Graph.subsonic.search(query, 30) }.getOrDefault(emptyList()) + runCatching { Graph.jellyfin.search(query, 30) }.getOrDefault(emptyList())), emptyList())
            Where.DEVICE -> Results(Graph.local.search(query, 50), emptyList())
            else -> {
                // v2.0.14: null-safe instead of !! (source may be disabled/unregistered).
                val src = where.kind?.let { Graph.sources.source(it) } ?: return@rememberLoad Results(emptyList(), emptyList())
                Results(src.search(query, 30), emptyList())
            }
        }
    }

    val staggerGroup = remember(query, filter, where, results.state is Load.Ok) { com.ravi.musicspot.ui.motion.StaggerGroup() }
    androidx.compose.runtime.CompositionLocalProvider(com.ravi.musicspot.ui.motion.LocalStaggerGroup provides staggerGroup) {
    val stillSug = com.ravi.musicspot.ui.motion.reducedMotion()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding()) {
        item { OfflineBanner() }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).glass(Ms.pill), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(if (listening) "Listening…" else "Songs, artists, albums") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit(input) }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                )
                if (input.isNotEmpty()) IconButton(onClick = { input = ""; query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Icon(Icons.Rounded.Mic, "Voice search", tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        // v2.0.6: suggestions drop in / fade out with the shared motion specs (instant with Reduce motion).
        if (suggestions.isNotEmpty()) items(suggestions.distinct(), key = { "sug:$it" }) { s ->
            Row(Modifier.animateItem(
                    fadeInSpec = if (stillSug) null else androidx.compose.animation.core.tween(com.ravi.musicspot.ui.motion.MotionSpec.MEDIUM, easing = com.ravi.musicspot.ui.motion.MotionSpec.emphasizedDecelerate),
                    placementSpec = if (stillSug) null else com.ravi.musicspot.ui.motion.MotionSpec.surfaceSpring(androidx.compose.ui.unit.IntOffset.VisibilityThreshold),
                    fadeOutSpec = if (stillSug) null else androidx.compose.animation.core.tween(com.ravi.musicspot.ui.motion.MotionSpec.SHORT),
                ).fillMaxWidth().clickable { submit(s) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(14.dp))
                Text(s, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { input = s }) { Icon(Icons.AutoMirrored.Rounded.CallMade, "Use $s") }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(available) { w -> FilterChip(where == w, { where = w }, label = { Text(w.label) }) }
            }
        }
        if (where == Where.YT) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchFilter.entries.toList()) { f -> FilterChip(filter == f, { filter = f }, label = { Text(f.label) }) }
            }
        }
        if (query.isBlank()) {
            if (settings.recentSearches.isEmpty()) item {
                EmptyState(Icons.Rounded.Search, "Search everything", "YouTube Music, JioSaavn, your device and any other sources you turn on in Settings.")
            } else {
                item { SectionHeader("Recent searches", action = "Clear") { Graph.settings.update { it.copy(recentSearches = emptyList()) } } }
                items(settings.recentSearches) { r ->
                    Row(Modifier.fillMaxWidth().clickable { submit(r) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(14.dp))
                        Text(r, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { Graph.settings.update { it.copy(recentSearches = it.recentSearches - r) } }) { Icon(Icons.Rounded.Close, "Remove $r", Modifier.size(18.dp)) }
                    }
                }
            }
        } else when (val s = results.state) {
            is Load.Loading -> item { Loading() }
            is Load.Failed -> item { ErrorState(s.message, results.retry) }
            is Load.Ok -> {
                val r = s.data
                if (r.tracks.isEmpty() && r.collections.isEmpty()) item {
                    EmptyState(Icons.Rounded.SearchOff, "No results", if (where == Where.DEVICE && !Graph.local.available()) "Turn on music on this device in Settings → Sources." else "Try other words or another source.")
                }
                if (r.collections.isNotEmpty()) {
                    item { SectionHeader(if (where == Where.IA) "Recordings" else "Albums, artists & playlists") }
                    item { Box(Modifier.staggerIn(0)) { HorizontalShelf(r.collections, { it.id }) { c -> CollectionCard(c, 136.dp) { nav.openCollection(c) } } } }
                }
                if (r.tracks.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    // v2.0.14: distinct ids; play by position, not indexOf.
                    val songs = r.tracks.distinctBy { it.id }
                    itemsIndexed(songs, key = { _, it -> it.id }, contentType = { _, _ -> "track" }) { i, t -> Box(Modifier.staggerIn(i + 1)) { TrackRow(t, onClick = { Graph.player.play(songs, i) }, onMore = { Menus.track = t }) } }
                }
            }
        }
    }
    }
}
