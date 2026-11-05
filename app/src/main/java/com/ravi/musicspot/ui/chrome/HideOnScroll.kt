/*
 * v2.0.3: hide the bottom nav pill while scrolling down (phones only). The connection never consumes scroll; it only
 * watches what the page's lists actually consumed, so pages that don't scroll (or horizontal carousels) never hide it.
 */
package com.ravi.musicspot.ui.chrome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

class NavHideState(private val hideAfterPx: Float, private val showAfterPx: Float) {
    /** True while the nav pill should be off screen. */
    var hidden by mutableStateOf(false); private set
    /** Turned off on wide windows and when the setting is off; [hidden] is then always false. */
    var enabled: Boolean = true
        set(v) { field = v; if (!v) show() }

    private var down = 0f
    private var up = 0f

    fun show() { hidden = false; down = 0f; up = 0f }

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled) return Offset.Zero
            val dy = consumed.y
            when {
                // Content moved towards its end (finger up): hide after ~24 dp.
                dy < 0f -> { up = 0f; down += -dy; if (!hidden && down >= hideAfterPx) { hidden = true; down = 0f } }
                // Content moved back towards its start: show on scroll up (small slop against finger jitter).
                dy > 0f -> { down = 0f; up += dy; if (hidden && up >= showAfterPx) show() }
                // Pulling down while already at the top of the list: always show.
                available.y > 0f -> show()
            }
            return Offset.Zero
        }
    }
}
