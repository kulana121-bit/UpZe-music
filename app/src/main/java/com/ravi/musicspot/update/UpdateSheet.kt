package com.ravi.musicspot.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.ui.glass.glassSheet
import com.ravi.musicspot.ui.theme.Ms

/** "Update available — vX" bottom sheet: release notes, download progress, Update / Later / Skip this version. */
@Composable
fun UpdateSheet() {
    val rel by Updater.available.collectAsState()
    com.ravi.musicspot.ui.motion.MotionSheet(rel, onDismiss = { Updater.later() }) { r ->
        val ctx = LocalContext.current
        val dl by Updater.download.collectAsState()
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxWidth().glassSheet(Ms.r28).navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Update available — v${r.version}", style = MaterialTheme.typography.titleLarge)
                Text("You have ${com.ravi.musicspot.BuildConfig.VERSION_NAME}." + if (r.apkSize > 0) " Download ${"%.1f".format(r.apkSize / 1_048_576.0)} MB." else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.notes.isNotBlank()) Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text(r.notes, style = MaterialTheme.typography.bodyMedium)
                }
                when (val d = dl) {
                    is Updater.Download.Running -> {
                        if (d.total > 0) LinearProgressIndicator(progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Downloading… ${d.done / 1024} KB" + if (d.total > 0) " of ${d.total / 1024} KB" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    is Updater.Download.Failed -> Text(d.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is Updater.Download.Ready -> Text("Downloaded. If the installer did not open, allow UpZe to install apps, then tap Install.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Updater.Download.Idle -> {}
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { Updater.skip(r.version) }) { Text("Skip this version") }
                    TextButton(onClick = { Updater.later() }) { Text("Later") }
                    Button(onClick = { Updater.update(ctx, r) }, enabled = dl !is Updater.Download.Running) {
                        Text(when (dl) { is Updater.Download.Ready -> "Install"; is Updater.Download.Failed -> "Retry"; else -> "Update" })
                    }
                }
            }
        }
    }
}
