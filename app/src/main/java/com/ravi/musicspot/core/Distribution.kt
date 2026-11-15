package com.ravi.musicspot.core

import com.ravi.musicspot.BuildConfig

/**
 * Which distribution this build is. "full" (GitHub) has everything; "foss" (F-Droid, BuildConfig.IS_FOSS) has no
 * in-app APK updater — F-Droid updates the app. The pure overload takes the flag so tests can check both.
 */
object Distribution {
    val isFoss: Boolean get() = BuildConfig.IS_FOSS

    /** The GitHub in-app updater (checks, Settings › About › Updates). */
    fun updaterEnabled(foss: Boolean = isFoss): Boolean = !foss
}
