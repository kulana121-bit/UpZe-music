package com.ravi.musicspot.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.account.Account
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.source.serverBase
import com.ravi.musicspot.ui.Pills
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SourcesPage() {
    val s by Graph.settings.state.collectAsState()
    val accounts by Graph.accounts.accounts.collectAsState()
    val ytSignedIn = accounts.any { it.service == Account.Service.YOUTUBE }
    var pendingLocal by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Graph.settings.update { it.copy(enabledSources = it.enabledSources + SourceKind.LOCAL) } else Pills.show("Permission denied — device music stays off")
        pendingLocal = false
    }
    val order = (s.sourcePriority + AppDefaults.priority).distinct().filter { it != SourceKind.SPOTIFY }

    fun status(k: SourceKind): String = when (k) {
        SourceKind.YOUTUBE -> if (ytSignedIn) "Signed in · best Opus/AAC" else "Opus up to ~160 kbps"
        SourceKind.JIOSAAVN -> "320 kbps AAC"
        SourceKind.AUDIUS -> "Up to 320 kbps · independent artists"
        SourceKind.SOUNDCLOUD -> "Via NewPipe · currently failing (SoundCloud client id), left out of the automatic fallback"
        SourceKind.JAMENDO -> if (s.jamendoClientId.isNullOrBlank()) "Needs a client_id (below)" else "Creative Commons · FLAC where offered"
        SourceKind.ARCHIVE -> "Public domain & live · FLAC where uploaded"
        SourceKind.LOCAL -> if (Graph.local.hasPermission()) "Files on this phone · native quality" else "Needs permission to read audio files"
        SourceKind.SUBSONIC -> accounts.firstOrNull { it.service == Account.Service.SUBSONIC }?.let { "${it.name} @ ${it.extra?.removePrefix("https://")}" } ?: "Needs a server (below)"
        SourceKind.JELLYFIN -> accounts.firstOrNull { it.service == Account.Service.JELLYFIN }?.let { "${it.name} @ ${it.extra?.substringBefore('|')?.removePrefix("https://")}" } ?: "Needs a server (below)"
        SourceKind.SPOTIFY -> ""
    }

    fun toggle(k: SourceKind, on: Boolean) {
        if (on && k == SourceKind.LOCAL && !Graph.local.hasPermission()) { pendingLocal = true; perm.launch(Graph.local.permission); return }
        Graph.settings.update { it.copy(enabledSources = if (on) it.enabledSources + k else it.enabledSources - k) }
    }

    SettingsScaffold("Sources") {
        item {
            Group("Priority", footer = "Long-press the handle and drag to reorder. A song plays from the highest source that has it (downloads always come first); if that fails, the next one is tried. With Max or Lossless quality the best-quality match wins.") {
                ReorderList(order, onReorder = { l -> Graph.settings.update { it.copy(sourcePriority = l) } }, rowHeight = 68.dp, label = { it.label }) { k, index, handle ->
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        DragHandle(handle)
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(k.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(status(k), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(checked = k in s.enabledSources, onCheckedChange = { toggle(k, it) }, enabled = !(k == SourceKind.LOCAL && pendingLocal))
                    }
                }
            }
        }
        item { SubsonicCard(accounts.firstOrNull { it.service == Account.Service.SUBSONIC }) }
        item { JellyfinCard(accounts.firstOrNull { it.service == Account.Service.JELLYFIN }) }
        item { JamendoCard(s.jamendoClientId) }
    }
}

private object AppDefaults { val priority = com.ravi.musicspot.data.AppSettings.DEFAULT_PRIORITY }

@Composable
private fun SubsonicCard(acct: Account?) {
    val scope = rememberCoroutineScope()
    var url by remember(acct) { mutableStateOf(acct?.extra ?: "") }
    var user by remember(acct) { mutableStateOf(acct?.name ?: "") }
    var pass by remember(acct) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Group("Navidrome / Subsonic", footer = "https only. The password is encrypted with a Keystore key; each request uses a salted token, never the plain password.") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServerFields(url, { url = it }, user, { user = it }, pass, { pass = it }, passwordHint = if (acct != null) "Leave empty to keep the saved password" else null)
            msg?.let { (m, err) -> Text(m, style = MaterialTheme.typography.bodySmall, color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy && url.isNotBlank() && user.isNotBlank() && (pass.isNotBlank() || acct != null), onClick = {
                    busy = true; msg = null
                    scope.launch {
                        try {
                            val base = serverBase(url).toString().trimEnd('/')
                            val a = Account("subsonic", Account.Service.SUBSONIC, name = user.trim(), secret = pass.ifBlank { acct?.secret ?: "" }, extra = base)
                            Graph.subsonic.test(a)
                            Graph.accounts.upsert(a)
                            Graph.settings.update { it.copy(enabledSources = it.enabledSources + SourceKind.SUBSONIC) }
                            pass = ""; msg = "Connected — Subsonic is on" to false
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { msg = (e.message ?: "Couldn't connect") to true }
                        busy = false
                    }
                }) { Text(if (acct != null) "Save & test" else "Connect") }
                if (acct != null) OutlinedButton(onClick = {
                    Graph.accounts.remove(Account.Service.SUBSONIC)
                    Graph.settings.update { it.copy(enabledSources = it.enabledSources - SourceKind.SUBSONIC) }
                    msg = "Server removed" to false
                }) { Text("Remove") }
                if (busy) CircularProgressIndicator(Modifier.width(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun JellyfinCard(acct: Account?) {
    val scope = rememberCoroutineScope()
    var url by remember(acct) { mutableStateOf(acct?.extra?.substringBefore('|') ?: "") }
    var user by remember(acct) { mutableStateOf(acct?.name ?: "") }
    var pass by remember(acct) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Group("Jellyfin", footer = "https only. UpZe signs in once and keeps only the access token (encrypted with a Keystore key), not your password.") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServerFields(url, { url = it }, user, { user = it }, pass, { pass = it }, passwordHint = if (acct != null) "Enter it again to sign in again" else null)
            msg?.let { (m, err) -> Text(m, style = MaterialTheme.typography.bodySmall, color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotBlank(), onClick = {
                    busy = true; msg = null
                    scope.launch {
                        try {
                            val base = serverBase(url).toString().trimEnd('/')
                            val (token, userId) = Graph.jellyfin.login(base, user.trim(), pass)
                            Graph.accounts.upsert(Account("jellyfin", Account.Service.JELLYFIN, name = user.trim(), secret = token, extra = "$base|$userId"))
                            Graph.settings.update { it.copy(enabledSources = it.enabledSources + SourceKind.JELLYFIN) }
                            pass = ""; msg = "Signed in — Jellyfin is on" to false
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { msg = (e.message ?: "Couldn't sign in") to true }
                        busy = false
                    }
                }) { Text(if (acct != null) "Sign in again" else "Sign in") }
                if (acct != null) OutlinedButton(onClick = {
                    Graph.accounts.remove(Account.Service.JELLYFIN)
                    Graph.settings.update { it.copy(enabledSources = it.enabledSources - SourceKind.JELLYFIN) }
                    msg = "Server removed" to false
                }) { Text("Remove") }
                if (busy) CircularProgressIndicator(Modifier.width(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ServerFields(url: String, onUrl: (String) -> Unit, user: String, onUser: (String) -> Unit, pass: String, onPass: (String) -> Unit, passwordHint: String?) {
    OutlinedTextField(url, onUrl, singleLine = true, label = { Text("Server address") }, placeholder = { Text("https://music.example.com") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(user, onUser, singleLine = true, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(pass, onPass, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
        supportingText = passwordHint?.let { h -> { Text(h) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun JamendoCard(saved: String?) {
    val linkCtx = androidx.compose.ui.platform.LocalContext.current
    var id by remember(saved) { mutableStateOf(saved ?: "") }
    Group("Jamendo", footer = "Jamendo's free API needs your own client_id. Create one (free) at developer.jamendo.com.") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(id, { id = it.trim() }, singleLine = true, label = { Text("client_id") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = id != (saved ?: ""), onClick = {
                    val v = id.ifBlank { null }
                    Graph.settings.update { it.copy(jamendoClientId = v, enabledSources = if (v != null) it.enabledSources + SourceKind.JAMENDO else it.enabledSources - SourceKind.JAMENDO) }
                    Pills.show(if (v != null) "Jamendo is on" else "Jamendo client_id removed")
                }) { Text("Save") }
                TextButton(onClick = { com.ravi.musicspot.core.Links.open(linkCtx, "https://developer.jamendo.com/v3.0") }) { Text("Get a client_id") }
            }
        }
    }
}

