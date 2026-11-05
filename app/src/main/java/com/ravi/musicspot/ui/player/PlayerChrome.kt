// 1.0.1: shared player chrome: the ⋮ menu with the player's secondary actions, and the Up next / Lyrics entry bar.
package com.ravi.musicspot.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.playback.EngineAudioProcessor
import com.ravi.musicspot.playback.Playback
import com.ravi.musicspot.ui.motion.MotionDropdownMenu
import com.ravi.musicspot.ui.motion.MotionSpec
import com.ravi.musicspot.ui.motion.pressScale
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.rememberHaptics
import com.ravi.musicspot.ui.theme.Ms

/**
 * 1.0.1: the player's top-right ⋮ button and its menu: the secondary actions that used to crowd the bottom of the
 * player (Related, Credits, Sleep timer, Sound & EQ) plus "Song options" (the shared song sheet). The menu scales out
 * of the button (MotionDropdownMenu) and each pane it opens grows out of the tapped item.
 */
@Composable
fun PlayerMoreButton(
    onSongOptions: () -> Unit,
    onPane: (Pane) -> Unit,
    onOpenSleep: () -> Unit,
    onOpenEq: () -> Unit,
    buttonModifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val sleepTick by Playback.sleepTick.collectAsState()
    val engine by EngineAudioProcessor.status.collectAsState()
    val sleepOn = sleepTick >= 0 && Graph.player.sleepActive
    Box {
        val i = remember { MutableInteractionSource() }
        IconButton(onClick = { haptics.tick(); open = true }, interactionSource = i, modifier = buttonModifier.pressScale(i, 0.88f)) {
            Icon(Icons.Rounded.MoreVert, "More options")
        }
        MotionDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable fun Item(icon: ImageVector, label: String, detail: String? = null, tint: Color = MaterialTheme.colorScheme.onSurface, action: () -> Unit) =
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(icon, null, tint = tint) },
                    onClick = { open = false; action() },
                )
            Item(Icons.Rounded.MoreHoriz, "Song options", "Like, add to playlist, go to artist…", action = onSongOptions)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Item(Icons.Rounded.Explore, "Related", action = { onPane(Pane.RELATED) })
            Item(Icons.Rounded.Info, "Credits", "Writers, source and stream info", action = { onPane(Pane.CREDITS) })
            Item(Icons.Rounded.Bedtime, "Sleep timer", if (sleepOn) sleepLabelPublic() else null,
                tint = if (sleepOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, action = onOpenSleep)
            Item(Icons.Rounded.Equalizer, "Sound & EQ", if (engine.eqActive) "EQ on" else null, action = onOpenEq)
        }
    }
}

/** 1.0.1: small "sleep timer on" chip for the player's top bar (only while a timer runs); tap opens the sleep sheet. */
@Composable
fun SleepChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sleepTick by Playback.sleepTick.collectAsState()
    val on = sleepTick >= 0 && Graph.player.sleepActive
    if (!on) return
    Row(modifier.clip(Ms.pill).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)).clickable(onClickLabel = "Sleep timer", onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Bedtime, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Text(sleepLabelPublic(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/**
 * 1.0.1: two distinct entry points at the bottom of the player: Up next (with the next song) and Lyrics. Each opens
 * the full-screen Queue / Lyrics pager on its page, growing out of the card (container transform). Swiping up on the
 * player opens the same pager.
 */
@Composable
fun PlayerEntryBar(onQueue: () -> Unit, onLyrics: () -> Unit, modifier: Modifier = Modifier) {
    val st by Graph.player.state.collectAsState()
    val next = st.queue.getOrNull(st.index + 1)
    val haptics = rememberHaptics()
    Row(modifier.fillMaxWidth().widthIn(max = 640.dp).padding(horizontal = 20.dp, vertical = 8.dp).height(60.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EntryCard(Icons.AutoMirrored.Rounded.QueueMusic, "Up next", next?.let { "${it.title} · ${it.artist}" } ?: "Queue", Modifier.weight(1.35f)) { haptics.tick(); onQueue() }
        EntryCard(Icons.Rounded.Lyrics, "Lyrics", "Swipe up", Modifier.weight(1f)) { haptics.tick(); onLyrics() }
    }
}

@Composable
private fun EntryCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val i = remember { MutableInteractionSource() }
    val still = reducedMotion()
    Row(
        modifier.fillMaxHeight().pressScale(i, 0.96f).clip(Ms.r20)
            .background(c.surfaceContainerHigh.copy(alpha = 0.62f))
            .border(1.dp, Brush.verticalGradient(listOf(c.onSurface.copy(alpha = 0.12f), Color.Transparent)), Ms.r20)
            .clickable(interactionSource = i, indication = androidx.compose.material3.ripple(), onClickLabel = title, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = c.primary)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            AnimatedContent(subtitle, transitionSpec = { MotionSpec.fadeThrough(still) }, label = "entrySub") {
                Text(it, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
