// 1.0.1: device tier rule for lite effects, kept pure for unit tests.
package com.ravi.musicspot.logic

object DeviceTier {
    /** Per-app heap at or below this (MB) counts as lite. */
    const val LITE_MEMORY_CLASS_MB = 96
    /** Total RAM below this counts as lite (an 8" Android 10 tablet with 2-3 GB stays on full effects). */
    const val LITE_TOTAL_RAM_BYTES = 1_600L * 1024 * 1024

    /** True on devices that should skip costly visual extras (see Graph.liteFx). Unknown values (<= 0) never count. */
    fun isLite(memoryClassMb: Int, totalRamBytes: Long): Boolean =
        (memoryClassMb in 1..LITE_MEMORY_CLASS_MB) || (totalRamBytes in 1 until LITE_TOTAL_RAM_BYTES)

    // 1.0.5: frame pacing for ambient (non-interactive) loops on lite devices. Pure, for unit tests.

    /** 1.0.5: target interval between published frames of an ambient loop on lite devices (~30 FPS). */
    const val LITE_AMBIENT_FRAME_NANOS = 33_000_000L

    /**
     * 1.0.5: vsync timestamps jitter by a fraction of a millisecond, so two 60 Hz frames (33.3 ms) can measure 32.9 ms.
     * This slack keeps the cadence at every 2nd frame on 60 Hz (3rd on 90 Hz, 4th on 120 Hz) instead of dropping to 20 FPS.
     */
    const val FRAME_SLACK_NANOS = 2_000_000L

    /** 1.0.5: minimum nanoseconds between published frames of an ambient loop; 0 = every frame (full rate). */
    /**
     * 1.0.8 lighter: ambient loops (slow aura drift, cover breathing, shimmer) are paced on EVERY device now: ~30 FPS on
     * full devices, ~20 FPS on lite ones. These motions are slow (seconds-long periods), so the eye can't tell; but every
     * skipped frame is a frame the glass above them doesn't have to re-blur. Interactive motion is never paced.
     */
    fun ambientFrameIntervalNanos(lite: Boolean): Long = if (lite) LITE_AMBIENT_FRAME_NANOS_108 else FULL_AMBIENT_FRAME_NANOS

    /** 1.0.8: full-device ambient pacing (~30 FPS). */
    const val FULL_AMBIENT_FRAME_NANOS = 33_000_000L
    /** 1.0.8: lite-device ambient pacing (~20 FPS). */
    const val LITE_AMBIENT_FRAME_NANOS_108 = 50_000_000L

    /** 1.0.5: true when a frame at [nowNanos] should be published, given the last published frame and the interval. */
    fun shouldPublishFrame(lastPublishedNanos: Long, nowNanos: Long, intervalNanos: Long): Boolean =
        intervalNanos <= 0L || nowNanos - lastPublishedNanos >= intervalNanos - FRAME_SLACK_NANOS
}
