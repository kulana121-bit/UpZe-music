package com.ravi.musicspot.ui.player

import android.view.TextureView
import android.graphics.SurfaceTexture
import android.os.SystemClock
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.ravi.musicspot.Graph
import com.ravi.musicspot.account.Account
import com.ravi.musicspot.model.Track

/**
 * v2.0.7 Spotify Canvas: with a Spotify account linked, the song's looping Canvas video (when Spotify has one) plays
 * muted behind Now Playing and the cover art steps aside, like OpenTune. Null when there is none / not signed in.
 */
@Composable
fun rememberCanvasUrl(t: Track): String? {
    val accounts by Graph.accounts.accounts.collectAsState()
    val cookie = accounts.firstOrNull { it.service == Account.Service.SPOTIFY }?.secret
    val url by produceState<String?>(null, t.id, cookie) {
        value = null
        if (cookie != null) value = runCatching { Graph.spotify.canvasUrl(cookie, t) }.getOrNull()
    }
    return url
}

/**
 * Muted, looping Canvas video, center-cropped to fill; fades in once the first frame is drawn.
 *
 * 1.0.8 freeze fix (Canvas stuck on one frame after opening something else and coming back, most often with Motion
 * Lyrics on). Three things changed:
 *  1. Every new video frame now also invalidates the Compose draw of the view. The player's background is recorded
 *     into a glass/blur layer (Haze on Android 8-11, the backdrop layer on 12+), and a TextureView updating on its own
 *     did not always re-record that layer, so the screen kept showing an old frame while the video ran underneath.
 *  2. The video pauses while it can't be seen ([visible] false: player collapsed, a full-screen pane over it, app in
 *     the background) and on the way back the surface is re-attached and playback resumes from a fresh frame.
 *  3. A watchdog: if no new frame arrives for 1.5 s while it should be playing, the surface is re-attached and the
 *     player re-seeks; a second stall (or a player error) rebuilds the player from scratch.
 */
@Composable
fun CanvasVideo(url: String, modifier: Modifier = Modifier, visible: () -> Boolean = { true }) {
    var generation by remember(url) { mutableIntStateOf(0) }
    key(url, generation) { CanvasVideoImpl(url, modifier, visible) { generation++ } }
}

@OptIn(UnstableApi::class)
@Composable
private fun CanvasVideoImpl(url: String, modifier: Modifier, visible: () -> Boolean, rebuild: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ready by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(if (ready) 1f else 0f, tween(840), label = "canvasIn")
    val frames = remember { mutableIntStateOf(0) }
    val lastFrame = remember { LongArray(1) } // uptime of the last frame that reached the view (main thread only)
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            addListener(object : Player.Listener { override fun onRenderedFirstFrame() { ready = true; lastFrame[0] = SystemClock.uptimeMillis() } })
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val size = remember { IntArray(2) }
    val views = remember { arrayOfNulls<TextureView>(1) }
    fun crop() {
        val tv = views[0] ?: return
        val vw = size[0]; val vh = size[1]
        if (vw <= 0 || vh <= 0 || tv.width <= 0 || tv.height <= 0) return
        // Center-crop: scale the video to cover the view, keeping its aspect ratio.
        val s = maxOf(tv.width / vw.toFloat(), tv.height / vh.toFloat())
        val m = android.graphics.Matrix()
        m.setScale(vw * s / tv.width, vh * s / tv.height, tv.width / 2f, tv.height / 2f)
        tv.setTransform(m)
    }
    /** (Re)attaches the player to [tv] and wraps the player's surface listener to count delivered frames. */
    fun bind(tv: TextureView) {
        player.clearVideoTextureView(tv)
        player.setVideoTextureView(tv)
        val inner = tv.surfaceTextureListener ?: return
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = inner.onSurfaceTextureAvailable(st, w, h)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { inner.onSurfaceTextureSizeChanged(st, w, h); crop() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = inner.onSurfaceTextureDestroyed(st)
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                inner.onSurfaceTextureUpdated(st)
                lastFrame[0] = SystemClock.uptimeMillis()
                frames.intValue++ // read in the draw below: the (recorded) background redraws with the new frame
            }
        }
        crop()
    }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) { size[0] = v.width; size[1] = v.height; crop() } }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    // Pause while hidden, re-attach on return, and recover from a stuck surface. A 250 ms poll is enough for this and
    // costs nothing next to the video itself.
    LaunchedEffect(player) {
        var wasShown = true
        var stalls = 0
        while (isActive) {
            delay(250)
            val now = SystemClock.uptimeMillis()
            val isShown = visible() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (isShown != wasShown) {
                wasShown = isShown
                if (isShown) {
                    views[0]?.let { bind(it) }
                    player.seekTo(player.currentPosition) // pushes a fresh frame onto the re-attached surface
                    player.playWhenReady = true
                    lastFrame[0] = now
                    stalls = 0
                } else player.playWhenReady = false
                continue
            }
            if (!isShown) continue
            if (player.playerError != null) { rebuild(); return@LaunchedEffect }
            val due = ready && player.playWhenReady && player.playbackState == Player.STATE_READY
            if (due && now - lastFrame[0] > 1500L) {
                if (++stalls >= 2) { rebuild(); return@LaunchedEffect }
                views[0]?.let { bind(it) }
                player.seekTo(player.currentPosition)
                lastFrame[0] = now
            } else if (now - lastFrame[0] < 500L) stalls = 0
        }
    }
    AndroidView(
        factory = { c -> TextureView(c).also { tv ->
            views[0] = tv
            bind(tv)
            tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> crop() }
        } },
        modifier = modifier.graphicsLayer { alpha = shown }.drawWithContent { frames.intValue; drawContent() },
    )
}
