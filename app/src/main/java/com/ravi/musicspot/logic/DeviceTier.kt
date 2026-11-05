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
}
