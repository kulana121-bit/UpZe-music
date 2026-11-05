package com.ravi.musicspot.data

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.dsp.FilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder

/** 10-band graphic presets (32 Hz … 16 kHz). Plain tonal curves, not measurements. */
object EqPresets {
    val graphic: Map<String, List<Double>> = linkedMapOf(
        "Flat" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        "Bass" to listOf(5.0, 4.0, 3.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        "Warm" to listOf(2.0, 2.0, 1.5, 1.0, 0.0, 0.0, -0.5, -1.0, -1.5, -2.0),
        "Vocal" to listOf(-2.0, -1.5, -1.0, 0.0, 1.5, 2.5, 2.5, 1.5, 0.0, -1.0),
        "Bright" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.5, 2.5, 3.0, 3.0),
        "V-shape" to listOf(4.0, 3.0, 1.5, 0.0, -1.5, -1.5, 0.0, 1.5, 3.0, 3.5),
        "Loudness" to listOf(4.0, 3.0, 1.0, 0.0, -0.5, 0.0, 0.0, 1.0, 2.5, 3.0),
        "Late night" to listOf(-1.0, -0.5, 0.0, 0.5, 1.0, 1.0, 0.5, 0.0, -1.5, -2.5),
        "Treble cut" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0, -2.5, -4.0, -5.0),
    )
}

/**
 * AutoEq (github.com/jaakkopasanen/AutoEq, MIT): headphone correction profiles. The index (~850 KB) is fetched
 * once and cached; a picked profile's ParametricEQ.txt is parsed into parametric bands + preamp.
 */
object AutoEq {
    data class Entry(val name: String, val source: String, val path: String)
    data class Profile(val name: String, val preampDb: Double, val bands: List<ParamBandSetting>)

    private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    @Volatile private var index: List<Entry>? = null
    private val line = Regex("""^- \[(.+?)]\(\./(.+?)\) by (.+?)(?: on .*)?$""")
    private val filter = Regex("""Filter\s+\d+:\s+(ON|OFF)\s+(PK|LSC|HSC|LS|HS|LP|HP|LPQ|HPQ)\s+Fc\s+([\d.]+)\s*Hz(?:\s+Gain\s+(-?[\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""", RegexOption.IGNORE_CASE)

    suspend fun search(cacheDir: File, query: String, limit: Int = 40): List<Entry> {
        val all = loadIndex(cacheDir)
        val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return all.filter { e -> val n = e.name.lowercase(); terms.all { it in n } }.take(limit)
    }

    private suspend fun loadIndex(cacheDir: File): List<Entry> = index ?: withContext(Dispatchers.IO) {
        val f = File(cacheDir, "autoeq-index.md")
        val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < 14L * 24 * 3600 * 1000
        val text = if (fresh) f.readText() else Http.getString(BASE + "INDEX.md").also { runCatching { f.parentFile?.mkdirs(); f.writeText(it) } }
        text.lineSequence().mapNotNull { l -> line.find(l.trim())?.let { m -> Entry(m.groupValues[1], m.groupValues[3], m.groupValues[2]) } }.toList().also { index = it }
    }

    suspend fun profile(e: Entry): Profile = withContext(Dispatchers.IO) {
        val folderName = URLDecoder.decode(e.path.substringAfterLast('/'), "UTF-8")
        val url = BASE + e.path + "/" + java.net.URLEncoder.encode("$folderName ParametricEQ.txt", "UTF-8").replace("+", "%20")
        parse(e.name, Http.getString(url))
    }

    fun parse(name: String, text: String): Profile {
        val pre = Regex("""Preamp:\s*(-?[\d.]+)\s*dB""").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val bands = filter.findAll(text).mapNotNull { m ->
            val type = when (m.groupValues[2].uppercase()) {
                "PK" -> FilterType.PEAK; "LSC", "LS" -> FilterType.LOW_SHELF; "HSC", "HS" -> FilterType.HIGH_SHELF
                "LP", "LPQ" -> FilterType.LOW_PASS; "HP", "HPQ" -> FilterType.HIGH_PASS; else -> null
            } ?: return@mapNotNull null
            ParamBandSetting(type, m.groupValues[3].toDouble(), m.groupValues[4].toDoubleOrNull() ?: 0.0,
                m.groupValues[5].toDoubleOrNull() ?: 0.71, m.groupValues[1].equals("ON", true))
        }.toList()
        require(bands.isNotEmpty()) { "No filters in this profile" }
        return Profile(name, pre, bands)
    }
}
