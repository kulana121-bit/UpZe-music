package com.ravi.musicspot.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** The panes Now Playing opens: Up next, Lyrics, Related, Credits. */
enum class Pane(val label: String, val icon: ImageVector) {
    QUEUE("Up next", Icons.AutoMirrored.Rounded.PlaylistPlay), // 1.0.7: list-with-play, so it no longer looks like the Lyrics note icon
    LYRICS("Lyrics", Icons.Rounded.Lyrics),
    RELATED("Related", Icons.Rounded.Explore),
    CREDITS("Credits", Icons.Rounded.Info),
}

/**
 * 1.0.5: a Text whose string is computed (and its state read) only in its own scope, so a position tick recomposes
 * just this Text instead of the seek bar / deck around it. Same Text call as before.
 */
@Composable
internal fun PlayerTickText(text: () -> String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) {
    // 1.0.7: the string is derived, so a 250 ms position tick that does not change the shown "m:ss" (3 ticks out of 4)
    // invalidates nothing; the Text recomposes once per displayed second. rememberUpdatedState keeps the latest lambda
    // (it captures the caller's scrub state and duration) without restarting the derivation.
    val latest = rememberUpdatedState(text)
    val shown by remember { derivedStateOf { latest.value() } }
    Text(shown, style = style, color = color, modifier = modifier)
}
