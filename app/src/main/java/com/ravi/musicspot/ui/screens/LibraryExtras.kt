package com.ravi.musicspot.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ravi.musicspot.Graph
import com.ravi.musicspot.source.Radio
import com.ravi.musicspot.together.ListenTogether
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.Pills
import com.ravi.musicspot.ui.components.Badge
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.components.GlassTopBar
import com.ravi.musicspot.ui.components.Loading
import com.ravi.musicspot.ui.components.SectionHeader
import com.ravi.musicspot.ui.components.TrackRow
import com.ravi.musicspot.ui.components.formatBytes
import com.ravi.musicspot.ui.glass.GlassLayer
import com.ravi.musicspot.ui.motion.staggerIn
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

// ---- Cached songs ----------------------------------------------------------------------------------------------

/** 1.0.2: songs already fully in the media cache; they play with no internet. */
@Composable
fun CachedSongsScreen() {
    val entries by Graph.cached.complete.collectAsState()
    var used by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(entries.isEmpty()) }
    // v1.04: "Clear all": non-null while the confirmation is open, holding (songs, bytes) that clearing frees.
    var clearAllPreview by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { val e = Graph.cached.refresh(); used = Graph.cached.bytes(e) }
        loading = false
    }
    val limit = Graph.settings.value.cacheLimitMb
    TrackListPage("Cached songs", "Songs you played to the end · play offline · ${formatBytes(used)} of $limit MB cache",
        null, entries.map { it.track }, loading = loading,
        emptyIcon = Icons.Rounded.CloudDone, emptyTitle = "No cached songs yet",
        emptyBody = "Every song you listen to all the way through is kept here and plays without internet, until the cache fills up and the oldest make room. Download a song to keep it for good.",
        headerActions = {
            IconButton(onClick = {
                scope.launch { clearAllPreview = withContext(Dispatchers.IO) { Graph.cached.clearAllPreview() } }
            }, enabled = !loading && !clearing && entries.isNotEmpty()) { Icon(Icons.Rounded.DeleteSweep, "Clear all") }
        },
        rowTrailing = { _, t -> if (Graph.downloads.isDownloaded(t.id)) Badge("SAVED") else Badge("OFFLINE") },
        rowMenu = { _, t -> Menus.cachedId = t.id; Menus.track = t })
    com.ravi.musicspot.ui.motion.MotionAlertDialog(item = clearAllPreview, onDismissRequest = { clearAllPreview = null },
        title = { Text("Clear all cached songs?") },
        text = { (count, bytes) ->
            Text("This frees ${formatBytes(bytes)} by removing $count ${if (count == 1) "song" else "songs"} from the cache. " +
                "They will stream from the internet again. Downloads are not affected.")
        },
        confirmButton = {
            TextButton(onClick = {
                clearAllPreview = null
                clearing = true
                scope.launch {
                    val n = withContext(Dispatchers.IO) { Graph.cached.clearAll() }
                    used = 0L
                    clearing = false
                    Pills.show(if (n == 1) "Cleared 1 cached song" else "Cleared $n cached songs")
                }
            }) { Text("Clear all") }
        },
        dismissButton = { TextButton(onClick = { clearAllPreview = null }) { Text("Cancel") } })
}

// ---- Listen together -------------------------------------------------------------------------------------------

@Composable
fun ListenTogetherScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val room by ListenTogether.room.collectAsState()
    val header = rememberHeaderHeight()
    GlassLayer(Modifier.fillMaxSize(), overlay = { GlassTopBar("Listen together", onBack = { nav.back() }, height = header) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(top = 76.dp, header = header.value)) {
            item(key = "libraryextras-1", contentType = "static") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val r = room
                    if (r == null) {
                        Text("Hear the same song at the same moment as your friends. One phone hosts, everyone else joins with the code. Each phone streams the music on its own internet.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Card {
                            Text("Host a room", style = MaterialTheme.typography.titleMedium)
                            Text("You control the music: songs, pause and seeking play for everyone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { ListenTogether.host() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Groups, null); Spacer(Modifier.width(8.dp)); Text("Start a room") }
                        }
                        Card {
                            Text("Join a room", style = MaterialTheme.typography.titleMedium)
                            var code by remember { mutableStateOf("") }
                            var bad by remember { mutableStateOf(false) }
                            fun go() { if (!ListenTogether.join(code)) bad = true }
                            OutlinedTextField(code, { code = it.uppercase().take(8); bad = false }, singleLine = true, isError = bad,
                                placeholder = { Text("6-character code") }, supportingText = if (bad) { { Text("That isn't a room code") } } else null,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { go() }),
                                textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
                                shape = Ms.r12, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Spacer(Modifier.height(6.dp))
                            FilledTonalButton(onClick = { go() }, enabled = code.count { it.isLetterOrDigit() } >= 6, modifier = Modifier.fillMaxWidth()) { Text("Join") }
                        }
                        Text("Anyone with the code can listen in, so share it only with friends. Rooms run through the free ntfy.sh relay; only the song name and position travel.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val host = r.role == ListenTogether.Role.HOST
                        Card {
                            Text(if (host) "Your room" else "Listening with ${r.hostName.ifBlank { "the host" }}", style = MaterialTheme.typography.titleMedium)
                            Text(r.code, style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 6.sp),
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                            Text(buildString {
                                append(if (r.connected) "Connected" else "Connecting…")
                                append(" · ")
                                append(when (r.listeners) { 0 -> if (host) "no one has joined yet" else "you're listening"; 1 -> "1 listener"; else -> "${r.listeners} listeners" })
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            if (host) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = {
                                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("UpZe room", r.code)); Pills.show("Code copied")
                                }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy") }
                                Button(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, "Listen with me on UpZe! Open Library › Listen together › Join and enter ${r.code}")
                                    runCatching { ctx.startActivity(Intent.createChooser(send, "Invite to room")) }
                                }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Invite") }
                            }
                        }
                        r.nowPlaying?.let { t ->
                            SectionHeader("Now playing in the room")
                            TrackRow(t, onClick = { if (host) Graph.player.setPlaying(true) }, onMore = { Menus.track = t })
                        }
                        r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(if (host) "Play anything as usual and the room follows. Listeners can send you songs from a song's ⋮ menu."
                            else "The host controls the music. Send them a song from any song's ⋮ menu › Send to the room.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { ListenTogether.leave() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(6.dp)); Text(if (host) "Close room for everyone" else "Leave room")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, Ms.r20).padding(16.dp)) { content() }
}

// ---- Live radio --------------------------------------------------------------------------------------------------

/** 1.0.2: live stations near the user (with location permission), top stations in their country, and search. */
@Composable
fun RadioScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val header = rememberHeaderHeight()
    var granted by remember { mutableStateOf(hasLocation(ctx)) }
    var near by remember { mutableStateOf<List<Radio.Station>?>(null) }
    var top by remember { mutableStateOf<List<Radio.Station>?>(null) }
    var found by remember { mutableStateOf<List<Radio.Station>?>(null) }
    var place by remember { mutableStateOf<String?>(null) }
    var country by remember { mutableStateOf(fallbackCountry(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (ok) attempt++ else Pills.show("Showing stations for your country instead") }

    LaunchedEffect(granted, attempt) {
        error = null
        try {
            val loc = if (granted) lastLocation(ctx) else null
            val geo = loc?.let { reverse(ctx, it) }
            geo?.first?.let { country = it }
            place = geo?.second
            if (loc != null) {
                var list = runCatching { Radio.near(loc.latitude, loc.longitude) }.getOrDefault(emptyList())
                if (list.size < 5 && geo?.second != null) list = (list + runCatching { Radio.inState(country, geo.second!!) }.getOrDefault(emptyList())).distinctBy { it.stationuuid }
                near = list
            }
            top = Radio.topIn(country)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            error = if (Graph.online.value) "Couldn't reach the radio directory" else "You're offline. Live radio needs internet."
        }
    }

    fun play(list: List<Radio.Station>, i: Int) = Graph.player.play(list.map { it.toTrack() }, i)

    GlassLayer(Modifier.fillMaxSize(), overlay = { GlassTopBar("Live radio", onBack = { nav.back() }, height = header) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(top = 76.dp, header = header.value)) {
            item(key = "libraryextras-2", contentType = "static") {
                OutlinedTextField(query, { query = it; if (it.isBlank()) found = null }, singleLine = true, placeholder = { Text("Search stations, e.g. Mirchi, FM Gold") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; found = null }) { Icon(Icons.Rounded.Close, "Clear") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { scope.launch { found = runCatching { Radio.search(query.trim()) }.getOrElse { emptyList() } } }),
                    shape = Ms.pill, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            val f = found
            if (f != null) {
                item(key = "r-results", contentType = "section-header") { SectionHeader("Results", "${f.size} stations") }
                if (f.isEmpty()) item { EmptyState(Icons.Rounded.Radio, "No stations found", "Try a shorter name or a city.") }
                // 1.0.6: contentType, and the directory can list a station twice: distinct ids keep the lazy keys unique.
                val fu = f.distinctBy { it.stationuuid }
                itemsIndexed(fu, key = { _, s -> "f" + s.stationuuid }, contentType = { _, _ -> "station" }) { i, s -> StationRow(s) { play(fu, i) } }
            } else {
                if (!granted) item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), Ms.r20).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Stations near you", style = MaterialTheme.typography.titleSmall)
                            Text("Allow approximate location to find FM stations around you. It stays on your phone; only the area is searched.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = { ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("Allow") }
                    }
                }
                error?.let { e -> item { EmptyState(Icons.Rounded.Radio, "Radio unavailable", e, "Retry") { attempt++ } } }
                near?.let { list ->
                    item(key = "r-near", contentType = "section-header") { SectionHeader("Near you", place ?: "Within about 400 km") }
                    if (list.isEmpty()) item { Text("No stations list your area yet. Try the country list below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
                    val nu = list.distinctBy { it.stationuuid }
                    itemsIndexed(nu.take(25), key = { _, s -> "n" + s.stationuuid }, contentType = { _, _ -> "station" }) { i, s -> Column(Modifier.staggerIn(i, enabled = i < 10)) { StationRow(s) { play(nu, i) } } }
                }
                val t = top
                if (t == null && error == null) item { Loading() }
                if (t != null) {
                    item(key = "r-top", contentType = "section-header") { SectionHeader("Top in ${Locale("", country).displayCountry.ifBlank { country }}", "Most listened") }
                    val tu = t.distinctBy { it.stationuuid }
                    itemsIndexed(tu, key = { _, s -> "t" + s.stationuuid }, contentType = { _, _ -> "station" }) { i, s -> StationRow(s) { play(tu, i) } }
                }
                item(key = "libraryextras-3", contentType = "static") { Text("Stations from the community-run Radio Browser directory. A few may be offline at times.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun StationRow(s: Radio.Station, onClick: () -> Unit) {
    val t = remember(s.stationuuid) { s.toTrack() }
    TrackRow(t, onClick = onClick, onMore = { Menus.track = t },
        trailing = { Badge(listOfNotNull(s.codec.ifBlank { null }, s.bitrate.takeIf { it > 0 }?.let { "$it" }).joinToString(" ").ifBlank { "LIVE" }) })
}

private fun hasLocation(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private suspend fun lastLocation(ctx: Context): Location? = withContext(Dispatchers.Main) {
    if (!hasLocation(ctx)) return@withContext null
    val lm = ctx.getSystemService(LocationManager::class.java) ?: return@withContext null
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
    providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }?.let { return@withContext it }
    val provider = providers.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) && it != LocationManager.PASSIVE_PROVIDER } ?: return@withContext null
    withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= 30) {
                val cancel = android.os.CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching { lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(ctx)) { if (cont.isActive) cont.resume(it) } }
                    .onFailure { if (cont.isActive) cont.resume(null) }
            } else {
                val l = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) { if (cont.isActive) cont.resume(location) }
                    @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(l) } }
                @Suppress("DEPRECATION")
                runCatching { lm.requestSingleUpdate(provider, l, android.os.Looper.getMainLooper()) }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }
}

/** (country code, state) for a point, from the phone's geocoder; null when it has none (no Play services, offline). */
private suspend fun reverse(ctx: Context, loc: Location): Pair<String?, String?>? = withContext(Dispatchers.IO) {
    if (!android.location.Geocoder.isPresent()) return@withContext null
    @Suppress("DEPRECATION")
    runCatching { android.location.Geocoder(ctx, Locale.ENGLISH).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()
        ?.let { it.countryCode to it.adminArea }
}

private fun fallbackCountry(ctx: Context): String {
    val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java)
    return listOf(runCatching { tm?.networkCountryIso }.getOrNull(), runCatching { tm?.simCountryIso }.getOrNull(), Locale.getDefault().country)
        .firstOrNull { !it.isNullOrBlank() && it.length == 2 }?.uppercase() ?: "IN"
}

