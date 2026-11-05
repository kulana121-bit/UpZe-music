package com.ravi.musicspot.data

import com.ravi.musicspot.core.JsonFile
import com.ravi.musicspot.dsp.EnhanceLevel
import com.ravi.musicspot.dsp.EngineSettings
import com.ravi.musicspot.dsp.EqConfig
import com.ravi.musicspot.dsp.FilterType
import com.ravi.musicspot.dsp.ParamBand
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File

enum class ThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light"), PURE_BLACK("Pure black") }

/**
 * v2.0.6: what the phone dock does while a page scrolls down. MINI morphs it into a compact pill, HIDE slides it away
 * (the v2.0.3 behaviour), ALWAYS keeps it expanded. Tablets keep the side rail in every mode.
 */
enum class NavBarMode(val label: String) { MINI("Mini"), HIDE("Hide"), ALWAYS("Always visible") }

/** v2.0.10: which Now Playing design is used; both are kept. */
enum class PlayerStyle(val label: String) { CLASSIC("Classic"), AURA("Aura (liquid glass)"), VINYL("Deck (cassette)") /* v2.0.17: label only */, MINIMAL("Poster (big art)") /* v2.0.16 */ }
/** v2.0.10: which bottom dock design is used; both are kept. */
enum class DockStyle(val label: String) { CLASSIC("Classic"), ISLAND("Island (liquid glass)"), CAPSULE("Orb (floating cover)") /* v2.0.16: Orb dock */, EDGE("Split (two islands)") /* v2.0.17: Split dock */ }
/** v2.0.12: which Home dashboard design is used; both are kept. */
enum class HomeStyle(val label: String) { CLASSIC("Classic"), AURA("Aura (cards & glass)"), BENTO("Bento (grid tiles)"), SPOTLIGHT("Spotlight (big artwork)") }

// v2.0.14: WATER = clear aqua, see-through glass (works with real blur on Android 8–11 via Haze RenderScript too)
enum class GlassStyle(val label: String) { LIQUID("Liquid (lens)"), FROSTED("Frosted"), WATER("Water (clear)"), SOLID("Solid (no blur)") }

@Serializable
data class ParamBandSetting(val type: FilterType, val freqHz: Double, val gainDb: Double, val q: Double, val enabled: Boolean = true)

@Serializable
data class EqSetting(
    val enabled: Boolean = true,
    val bypassB: Boolean = false,
    val graphic: List<Double> = List(10) { 0.0 },
    val parametric: List<ParamBandSetting> = DEFAULT_PARAMS,
    val parametricOn: Boolean = false,
    val preampDb: Double = 0.0,
    val autoHeadroom: Boolean = true,
    val bassBoostDb: Double = 0.0,
    val widen: Double = 0.0,
    val limiter: Boolean = true,
    val preset: String = "Flat",
) {
    fun toConfig() = EqConfig(
        enabled = enabled, bypass = bypassB, graphic = graphic,
        parametric = if (parametricOn) parametric.map { ParamBand(it.type, it.freqHz, it.gainDb, it.q, it.enabled) } else emptyList(),
        preampDb = preampDb, autoHeadroom = autoHeadroom, bassBoostDb = bassBoostDb, widen = widen, limiter = limiter,
    )

    companion object {
        val DEFAULT_PARAMS = listOf(
            ParamBandSetting(FilterType.HIGH_PASS, 25.0, 0.0, 0.71, false),
            ParamBandSetting(FilterType.LOW_SHELF, 105.0, 0.0, 0.71),
            ParamBandSetting(FilterType.PEAK, 350.0, 0.0, 1.4),
            ParamBandSetting(FilterType.PEAK, 2_500.0, 0.0, 1.4),
            ParamBandSetting(FilterType.HIGH_SHELF, 10_000.0, 0.0, 0.71),
            ParamBandSetting(FilterType.LOW_PASS, 20_000.0, 0.0, 0.71, false),
        )
    }
}

@Serializable
data class AppSettings(
    val onboarded: Boolean = false,
    val guest: Boolean = true,
    val quality: Quality = Quality.AUTO,
    /** Used instead of [quality] while the active network is metered (mobile data / metered hotspot). */
    val mobileQuality: Quality = Quality.HIGH,
    val downloadQuality: Quality = Quality.MAX,
    val sourcePriority: List<SourceKind> = DEFAULT_PRIORITY,
    val enabledSources: Set<SourceKind> = setOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN),
    val jamendoClientId: String? = null,
    val lyricsOrder: List<String> = listOf("lrclib", "lyricsplus", "kugou", "netease", "ytmusic"),
    val lyricsEnabled: Set<String> = setOf("lrclib", "lyricsplus", "kugou", "netease", "ytmusic"),
    val lyricsOffsetMs: Int = 0,
    val romanize: Boolean = false,
    val crossfadeSec: Int = 0,
    /** Gapless (ExoPlayer's default gapless metadata handling). While on, crossfade is not applied. */
    val gapless: Boolean = true,
    /** Duration offered first (and used by the one-tap "Default" chip) in the sleep timer. */
    val sleepDefaultMin: Int = 30,
    val autoplay: Boolean = true,
    val sleepFade: Boolean = true,
    val sleepFinishSong: Boolean = false,
    val cacheLimitMb: Int = 1024,
    val wifiOnlyDownloads: Boolean = false,
    val glass: GlassStyle = GlassStyle.LIQUID,
    val reduceMotion: Boolean = false,
    /** v2.0.3: phones only — the bottom nav pill slides away while scrolling down a page and returns on scroll up. */
    val hideNavOnScroll: Boolean = true,
    /** v2.0.6: replaces [hideNavOnScroll]; null = not chosen yet (read through [navMode]). */
    val navBarMode: NavBarMode? = null,
    val dynamicColor: Boolean = true,
    val playerStyle: PlayerStyle = PlayerStyle.CLASSIC,
    val dockStyle: DockStyle = DockStyle.CLASSIC,
    val homeStyle: HomeStyle = HomeStyle.CLASSIC,
    /** v2.0.2: System / Dark / Light / Pure black (AMOLED). */
    val themeMode: ThemeMode = ThemeMode.DARK,
    val haptics: Boolean = true,
    /** Float output path for hi-res sources (24-bit/96k+): the software engine is bypassed in this mode. */
    val pureHiRes: Boolean = false,
    /** Android 14+: ask for bit-perfect mixer behaviour on a USB DAC (implies pure mode). */
    val bitPerfectUsb: Boolean = false,
    val eq: EqSetting = EqSetting(),
    val upscaleRate: Int = 0,
    val bwe: EnhanceLevel = EnhanceLevel.OFF,
    val transientRestore: Boolean = false,
    val region: String? = null,
    val recentSearches: List<String> = emptyList(),
    // v2.0.15: the first-time design setup (ui/setup/SetupFlow) has been finished or skipped.
    val setupDone: Boolean = false,
    /** 1.0.0: look for a newer GitHub release on app start (at most every 12 h). */
    val autoUpdateCheck: Boolean = true,
    /** 1.0.0: release version the user chose "Skip this version" for (automatic checks stay quiet about it). */
    val skippedUpdate: String? = null,
) {
    /** The quality the router should aim for right now. */
    fun effectiveQuality(metered: Boolean): Quality = if (metered) mobileQuality else quality
    /** v2.0.6 dock behaviour; before the choice existed, "hide on scroll" off meant always visible, on → Mini. */
    val navMode: NavBarMode get() = navBarMode ?: if (hideNavOnScroll) NavBarMode.MINI else NavBarMode.ALWAYS
    fun engine(lowEnd: Boolean) = EngineSettings(eq.toConfig(), upscaleRate, bwe, transientRestore, lowEnd)

    companion object {
        val DEFAULT_PRIORITY = listOf(
            SourceKind.LOCAL, SourceKind.SUBSONIC, SourceKind.JELLYFIN, SourceKind.YOUTUBE, SourceKind.JIOSAAVN,
            SourceKind.AUDIUS, SourceKind.JAMENDO, SourceKind.ARCHIVE, SourceKind.SOUNDCLOUD,
        )
    }
}

class SettingsRepo(dir: File) {
    private val file = JsonFile(File(dir, "settings.json"), AppSettings.serializer())
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state
    val value: AppSettings get() = _state.value

    /** Background thread only. */
    fun load() {
        file.read()?.let { s ->
            // New sources added in later versions join the priority list at the end.
            val prio = (s.sourcePriority + AppSettings.DEFAULT_PRIORITY).distinct().filter { it != SourceKind.SPOTIFY }
            _state.value = s.copy(sourcePriority = prio)
        }
    }

    fun update(f: (AppSettings) -> AppSettings) { _state.update(f); file.save { _state.value } }
}
