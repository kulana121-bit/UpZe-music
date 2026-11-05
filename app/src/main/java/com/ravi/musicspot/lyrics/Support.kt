/*
 * Ported from OpenTune 1.0.4 (lyrics/LyricsQuality.kt, YouTubeCaptions.kt Ttml.time), GPL-3.0-or-later.
 * Modified for UpZe (C) 2026 Ravi, GPL-3.0-or-later.
 */
package com.ravi.musicspot.lyrics

import java.text.Normalizer
import java.util.Locale

/** Loose title/artist comparison used to reject lyrics that belong to another song. */
object LyricsQuality {
    fun sameTitle(want: String, got: String?): Boolean {
        if (got == null) return true
        val a = titleKey(want); val b = titleKey(got)
        if (a.isEmpty() || b.isEmpty() || a.contains(b) || b.contains(a)) return true
        if (latinText(a) != latinText(b)) return true
        val wa = words(want); val wb = words(got)
        if (wa.isEmpty() || wb.isEmpty()) return true
        return overlap(wa, wb) >= 0.5
    }

    fun sameArtist(want: String, got: String?): Boolean {
        if (got == null) return true
        val a = looseKey(want)
        if (a.length < 2) return true
        val parts = got.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|\bx\b|\band\b|、)\s*""", RegexOption.IGNORE_CASE)).map(::looseKey).filter { it.length >= 2 }
        if (parts.isEmpty()) return true
        if (parts.any { it.contains(a) || a.contains(it) }) return true
        return parts.none { latinText(it) == latinText(a) }
    }

    fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        return small.count { it in large }.toDouble() / small.size
    }

    private fun titleKey(s: String) = looseKey(s.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""\s+(?:feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE), ""))
    private fun words(s: String) = looseText(s).split(' ').filter { it.length >= 2 }.toSet()
    private fun looseText(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim()
    private fun looseKey(s: String) = looseText(s).filter { it.isLetterOrDigit() }
    private fun latinText(s: String) = s.count { it.code < 0x250 } * 2 >= s.length
}

object Ttml {
    fun time(t: String?): Long? {
        t ?: return null
        if (t.endsWith("ms")) return t.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (t.endsWith("s")) return t.removeSuffix("s").toDoubleOrNull()?.times(1000)?.toLong()
        val parts = t.split(':')
        val sec = parts.last().toDoubleOrNull() ?: return null
        val min = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0
        val hr = parts.getOrNull(parts.size - 3)?.toLongOrNull() ?: 0
        return ((hr * 3600 + min * 60) * 1000) + (sec * 1000).toLong()
    }
}
