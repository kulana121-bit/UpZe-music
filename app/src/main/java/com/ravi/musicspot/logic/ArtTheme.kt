package com.ravi.musicspot.logic

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 1.0.7: artwork → theme colours (pure, unit-tested). The old seed was Palette's "vibrant" swatch, often a tiny
 * speck of the cover, and its saturation was clamped to 0.40 (accent) and 0.22 (surfaces), so many covers gave a
 * washed-out grey-pink UI that didn't look like the art. Now every swatch is scored by how much of the cover it
 * covers AND how colourful it is; the background family follows the cover's dominant colour, the accent its most
 * characteristic colourful one, and saturation is kept (within readable limits) instead of flattened.
 */
object ArtTheme {
    data class Picked(val accent: Int, val base: Int, val vivid: Boolean)
    /** Ready-to-use tones (ARGB). */
    data class Tones(
        val accent: Int, val bg: Int, val s1: Int, val s2: Int, val s3: Int,
        val container: Int, val textVariant: Int, val outlineVariant: Int,
    )

    /** Colourfulness 0..1 (HSL saturation weighted by distance from black/white). */
    fun chroma(argb: Int): Float { val h = Contrast.toHsl(argb); return h[1] * (1f - abs(2f * h[2] - 1f)) }

    /** [swatches] = (rgb, population) pairs from Palette. Null when there are none. */
    fun pick(swatches: List<Pair<Int, Int>>): Picked? {
        val sw = swatches.filter { it.second > 0 }
        if (sw.isEmpty()) return null
        val total = sw.sumOf { it.second }.toFloat()
        val base = sw.maxBy { it.second }.first
        val accent = sw.maxBy { (rgb, pop) ->
            val l = Contrast.toHsl(rgb)[2]
            val extremes = if (l < 0.10f || l > 0.92f) 0.3f else 1f // near-black/white specks make poor accents
            sqrt(pop / total) * (0.15f + chroma(rgb)) * extremes
        }.first
        return Picked(accent, base, chroma(accent) >= 0.10f)
    }

    fun tones(p: Picked, dark: Boolean, pureBlack: Boolean): Tones {
        val a = Contrast.toHsl(p.accent)
        val b = Contrast.toHsl(p.base)
        // Surfaces take the cover's dominant hue when it has one, else the accent's.
        val sHue = if (chroma(p.base) >= 0.06f) b[0] else a[0]
        val sSat = if (p.vivid) (maxOf(a[1], b[1]) * 0.5f).coerceIn(0.16f, 0.36f) else minOf(maxOf(a[1], b[1]), 0.08f)
        val accSat = if (p.vivid) a[1].coerceIn(0.50f, 0.85f) else a[1].coerceAtMost(0.20f)
        fun hsl(h: Float, s: Float, l: Float) = Contrast.fromHsl(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        return if (dark) Tones(
            accent = hsl(a[0], accSat, 0.72f),
            bg = if (pureBlack) 0xFF000000.toInt() else hsl(sHue, sSat, 0.08f),
            s1 = hsl(sHue, if (pureBlack) sSat * 0.6f else sSat, if (pureBlack) 0.05f else 0.115f),
            s2 = hsl(sHue, if (pureBlack) sSat * 0.6f else sSat, if (pureBlack) 0.085f else 0.155f),
            s3 = hsl(sHue, if (pureBlack) sSat * 0.6f else sSat, if (pureBlack) 0.12f else 0.20f),
            container = hsl(a[0], (accSat * 0.55f), 0.26f),
            textVariant = hsl(sHue, minOf(sSat, 0.14f), 0.76f),
            outlineVariant = hsl(sHue, sSat * 0.7f, 0.26f),
        ) else Tones(
            accent = hsl(a[0], accSat, 0.38f),
            bg = hsl(sHue, sSat * 1.1f, 0.965f),
            s1 = hsl(sHue, sSat * 1.1f, 0.94f),
            s2 = hsl(sHue, sSat * 1.1f, 0.91f),
            s3 = hsl(sHue, sSat * 1.1f, 0.87f),
            container = hsl(a[0], accSat * 0.6f, 0.86f),
            textVariant = hsl(sHue, minOf(sSat, 0.16f), 0.32f),
            outlineVariant = hsl(sHue, sSat * 0.8f, 0.80f),
        )
    }
}
