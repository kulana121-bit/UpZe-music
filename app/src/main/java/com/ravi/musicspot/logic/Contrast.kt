package com.ravi.musicspot.logic

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG 2.x contrast maths and soft-palette derivation, on ARGB ints (pure; unit-tested). */
object Contrast {
    private fun ch(c: Int) = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    fun luminance(argb: Int): Double = 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    fun ratio(a: Int, b: Int): Double { val la = luminance(a); val lb = luminance(b); return (max(la, lb) + 0.05) / (min(la, lb) + 0.05) }

    fun toHsl(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f; val g = ((argb shr 8) and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) { r -> (g - b) / d + (if (g < b) 6 else 0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 } * 60
        return floatArrayOf(h, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float, alpha: Int = 0xFF): Int {
        fun hue(p: Float, q: Float, tIn: Float): Float { var t = tIn; if (t < 0) t += 1; if (t > 1) t -= 1
            return when { t < 1f / 6 -> p + (q - p) * 6 * t; t < 1f / 2 -> q; t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6; else -> p } }
        val (r, g, b) = if (s == 0f) Triple(l, l, l) else {
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s; val p = 2 * l - q; val hh = h / 360f
            Triple(hue(p, q, hh + 1f / 3), hue(p, q, hh), hue(p, q, hh - 1f / 3))
        }
        return (alpha shl 24) or ((r * 255 + 0.5f).toInt() shl 16) or ((g * 255 + 0.5f).toInt() shl 8) or (b * 255 + 0.5f).toInt()
    }

    /** Softens an artwork colour into an accent: saturation capped, lightness in a calm band for a dark UI. */
    fun softAccent(seed: Int): Int {
        val (h, s, _) = toHsl(seed).let { Triple(it[0], it[1], it[2]) }
        return fromHsl(h, min(s, 0.40f).coerceAtLeast(if (s < 0.08f) s else 0.20f), 0.76f) // v2.0.8: softer, pastel accent
    }

    /** A deep, low-saturation tint of the seed for backgrounds/gradients. */
    fun softSurface(seed: Int, lightness: Float = 0.13f): Int {
        val hsl = toHsl(seed)
        return fromHsl(hsl[0], min(hsl[1], 0.22f), lightness) // v2.0.8: calmer tint
    }

    /** Lightens/darkens [fg] until it reaches [target] contrast against [bg] (AA = 4.5 for body text). */
    fun ensure(fg: Int, bg: Int, target: Double = 4.5): Int {
        if (ratio(fg, bg) >= target) return fg
        val hsl = toHsl(fg)
        val lighten = luminance(bg) < 0.5
        var l = hsl[2]
        repeat(40) {
            l = if (lighten) min(1f, l + 0.025f) else max(0f, l - 0.025f)
            val c = fromHsl(hsl[0], hsl[1], l)
            if (ratio(c, bg) >= target) return c
        }
        return if (lighten) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }
}
