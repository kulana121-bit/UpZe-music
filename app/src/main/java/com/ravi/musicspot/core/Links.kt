package com.ravi.musicspot.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 1.0.0: opens a web link in the browser with ACTION_VIEW. Compose's LocalUriHandler throws (IllegalArgumentException
 * wrapping ActivityNotFoundException) on devices with no browser; this never throws.
 */
object Links {
    const val GITHUB = "https://github.com/kulana121-bit/UpZe-music"
    /** Opens in the Instagram app when installed (it handles instagram.com links), otherwise in the browser. */
    const val INSTAGRAM = "https://www.instagram.com/jus7_rv/"

    fun open(ctx: Context, url: String): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i); true
    } catch (e: ActivityNotFoundException) {
        runCatching { com.ravi.musicspot.ui.Pills.show("No browser found to open the link") }; false
    } catch (e: SecurityException) { false }
}
