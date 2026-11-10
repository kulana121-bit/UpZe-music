package com.ravi.musicspot.playback

import android.app.Application
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.IoScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 1.0.2 Playback › Play when Bluetooth connects. When headphones, earbuds, a speaker or a car connects, the last queue
 * resumes. Phones, keyboards and watches are ignored. Android 12+ may refuse to start playback while UpZe is fully in
 * the background (system rule); on Android 10/11 it always works.
 */
class BluetoothAutoPlay : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val app = context.applicationContext as Application
        val cls: BluetoothClass? = runCatching {
            @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.bluetoothClass
        }.getOrNull()
        if (cls != null && cls.majorDeviceClass != BluetoothClass.Device.Major.AUDIO_VIDEO) return
        val pending = goAsync()
        IoScope.launch {
            try {
                runCatching { Graph.init(app) }
                Graph.awaitReady()
                if (!Graph.settings.value.playOnBluetooth) return@launch
                delay(2_500) // let Android route audio to the new device first
                // 1.0.5: the controller is built, used and released on the main thread (its application looper), and
                // this coroutine now waits for the whole hand-off. Before, the receiver's goAsync() was finished as
                // soon as buildAsync() was called, the controller leaked when the wait/prepare threw, and a failed
                // connect left the future unobserved.
                withContext(Dispatchers.Main.immediate) {
                    val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
                    val f = MediaController.Builder(app, token).buildAsync()
                    val c = try { withTimeoutOrNull(5_000) { f.await() } } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) { MediaController.releaseFuture(f); throw e }
                        null
                    }
                    if (c == null) { MediaController.releaseFuture(f); return@withContext }
                    try {
                        // The service restores the saved queue on start; wait briefly for it.
                        withTimeoutOrNull(5_000) { while (c.mediaItemCount == 0) delay(200) }
                        if (c.mediaItemCount > 0 && !c.isPlaying) { c.prepare(); c.play() }
                        delay(1_000)
                    } finally { runCatching { c.release() } }
                }
            } finally { pending.finish() }
        }
    }
}
