package com.ravi.musicspot.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ravi.musicspot.Graph
import com.ravi.musicspot.data.Backup
import com.ravi.musicspot.ui.Pills
import kotlinx.coroutines.launch

/**
 * 1.0.2: Settings › Backup & restore. Back up writes one JSON file wherever the user picks (Downloads, Drive…) via the
 * system file picker, so no storage permission is needed. Restore reads such a file back after a confirmation.
 */
@Composable
fun BackupPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lib by Graph.library.data.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching { Backup.export(ctx, uri) }
                .onSuccess { s -> Pills.show("Backed up ${s.liked} liked songs, ${s.playlists} playlists") }
                .onFailure { Pills.show("Backup failed: ${it.message ?: "couldn't write the file"}") }
            busy = false
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching { Backup.restore(ctx, uri) }
                .onSuccess { s -> Pills.show("Restored ${s.liked} liked songs, ${s.playlists} playlists, ${s.history} history") }
                .onFailure { Pills.show(it.message ?: "Couldn't restore this file") }
            busy = false
        }
    }

    SettingsScaffold("Backup & restore") {
        item(key = "backuppage-1", contentType = "static") {
            Group("Backup", footer = "Saves settings, liked songs, playlists, saved albums and artists, history, play counts and your artists in one file. Sign-ins and downloaded songs aren't included.") {
                NavRow(Icons.Rounded.Save, "Back up now", if (busy) "Working…" else "${lib.liked.size} liked · ${lib.playlists.size} playlists · ${lib.history.size} in history") {
                    if (!busy) save.launch(Backup.fileName())
                }
            }
        }
        item(key = "backuppage-2", contentType = "static") {
            Group("Restore", footer = "Replaces your current library and settings with the backup's. Your sign-ins stay.") {
                NavRow(Icons.Rounded.Restore, "Restore from a backup", "Pick an UpZe backup file") { if (!busy) confirmRestore = true }
            }
        }
    }
    com.ravi.musicspot.ui.motion.MotionAlertDialog(
        visible = confirmRestore, onDismissRequest = { confirmRestore = false },
        title = { Text("Restore a backup?") },
        text = { Text("Your liked songs, playlists, history and settings will be replaced by the ones in the file.") },
        confirmButton = { TextButton(onClick = { confirmRestore = false; open.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }) { Text("Choose file") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
    )
}
