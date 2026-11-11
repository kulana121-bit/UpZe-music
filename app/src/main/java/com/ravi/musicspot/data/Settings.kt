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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File

enum class ThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light"), PURE_BLACK("Pure black") }

/**
 * v2.0.6: what the phone dock does while a page scrolls down. MINI morphs it into a compact pill, HIDE slides it away
 * (the v2.0.3 behaviour), ALWAYS keeps it expanded. Tablets keep the side rail in every mode.
 */
enum class NavBarMode(val label: String) { MINI("Mini"), HIDE("Hide"), ALWAYS("Always visible") }

/**
 * v2.0.10: which bottom dock design is used. 1.0.6: only Classic and Orb remain (the Aura player and the Spotlight
 * Home are the only Now Playing / Home designs, so those choices are gone); [DockStyleSerializer] maps old values.
 */
@Serializable(with = DockStyleSerializer::class)
enum class DockStyle(val label: String) { CLASSIC("Classic"), CAPSULE("Orb (floating cover)") /* v2.0.16: Orb dock */ }

/**
 * 1.0.6: reads a saved dock name leniently, so a settings file or backup from 1.0.5 and older still loads (an unknown
 * enum name would otherwise fail the whole AppSettings decode and reset every setting). Island → Classic,
 * Split (EDGE) → Orb, anything else unknown → Classic. Written as the plain enum name, as before.
 */
object DockStyleSerializer : KSerializer<DockStyle> {
    private val legacy = mapOf("ISLAND" to DockStyle.CLASSIC, "EDGE" to DockStyle.CAPSULE)
    override val descriptor = PrimitiveSerialDescriptor("com.ravi.musicspot.data.DockStyle", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: DockStyle) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): DockStyle = fromName(decoder.decodeString())
    fun fromName(name: String): DockStyle = DockStyle.entries.firstOrNull { it.name == name } ?: legacy[name] ?: DockStyle.CLASSIC
}

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
    val quality: Quality = Quality.LOSSLESS, // 1.0.7 (Raj): lossless by default on Wi-Fi and mobile data
    /** Used instead of [quality] while the active network is metered (mobile data / metered hotspot). */
    val mobileQuality: Quality = Quality.LOSSLESS,
    val downloadQuality: Quality = Quality.MAX,
    val sourcePriority: List<SourceKind> = DEFAULT_PRIORITY,
    val enabledSources: Set<SourceKind> = setOf(SourceKind.QOBUZ, SourceKind.MONOCHROME, SourceKind.NETEASE, SourceKind.JIOSAAVN, SourceKind.YOUTUBE),
    val jamendoClientId: String? = null,
    val lyricsOrder: List<String> = DEFAULT_LYRICS_ORDER,
    /** 1.0.7: which [ORDER_REVISION] the saved source/lyrics orders were last migrated to. */
    val orderRevision: Int = 0,
    val lyricsEnabled: Set<String> = setOf("lyricsplus", "musixmatch", "amll", "kugou", "netease", "qqmusic", "jiosaavn_lyrics", "lrclib", "ytmusic", "lyricsovh"),
    val lyricsOffsetMs: Int = 0,
    val romanize: Boolean = false,
    /** 1.0.8: Now Playing shows Live lyrics (kinetic, beat-reactive lyrics around the screen) instead of the artwork. */
    val liveLyrics: Boolean = false,
    val crossfadeSec: Int = 0,
    /** Gapless (ExoPlayer's default gapless metadata handling). While on, crossfade is not applied. */
    val gapless: Boolean = true,
    /** Duration offered first (and used by the one-tap "Default" chip) in the sleep timer. */
    val sleepDefaultMin: Int = 30,
    val autoplay: Boolean = true,
    /** 1.0.2 Listen together: the ntfy-compatible relay rooms talk through (any self-hosted ntfy server works). */
    val togetherServer: String = "https://ntfy.sh",
    // ---- 1.0.2 Settings additions ----
    /** Playback › SponsorBlock: skip community-marked parts of YouTube songs (categories from [SponsorBlock.CATEGORIES]). */
    val sponsorBlock: Boolean = false,
    val sponsorCategories: Set<String> = setOf("music_offtopic", "sponsor", "selfpromo"),
    /** Playback: resume the last queue when Bluetooth headphones/speaker connect. */
    val playOnBluetooth: Boolean = false,
    /** Playback: pause when media volume reaches 0, resume when it comes back up. */
    val pauseOnMute: Boolean = false,
    /** Playback: when a song can't play (after the automatic retries), go on to the next one. */
    val skipOnError: Boolean = false,
    /** Privacy: nothing is added to history, listen stats, cached songs or recent searches. */
    val incognito: Boolean = false,
    /** Privacy: FLAG_SECURE (no screenshots or screen recording, blank in Recents). */
    val blockScreenshots: Boolean = false,
    /** AI Integration (the API key is stored encrypted elsewhere, never in settings or backups). */
    val aiPreset: String = "openai",
    val aiBaseUrl: String = "",
    val aiModel: String = "",
    val sleepFade: Boolean = true,
    val sleepFinishSong: Boolean = false,
    val cacheLimitMb: Int = 1024,
    val wifiOnlyDownloads: Boolean = false,
    val glass: GlassStyle = GlassStyle.LIQUID,
    /** 1.0.2: how strong glass blur is, 0 (no blur, tinted film) .. 1 (strongest). Applies to every blurred glass style. */
    val blurIntensity: Float = 0.6f,
    val reduceMotion: Boolean = false,
    /** v2.0.3: phones only — the bottom nav pill slides away while scrolling down a page and returns on scroll up. */
    val hideNavOnScroll: Boolean = true,
    /** v2.0.6: replaces [hideNavOnScroll]; null = not chosen yet (read through [navMode]). */
    val navBarMode: NavBarMode? = null,
    val dynamicColor: Boolean = true,
    // 1.0.6: `playerStyle` and `homeStyle` were removed (Aura player and Spotlight Home only); the keys in older
    // settings files and backups are skipped on read (Http.json ignores unknown keys).
    val dockStyle: DockStyle = DockStyle.CLASSIC,
    /** v2.0.2: System / Dark / Light / Pure black (AMOLED). */
    val themeMode: ThemeMode = ThemeMode.DARK,
    val haptics: Boolean = true,
    /** Float output path for hi-res sources (24-bit/96k+): the software engine is bypassed in this mode. */
    val pureHiRes: Boolean = false,
    /** Android 14+: ask for bit-perfect mixer behaviour on a USB DAC (implies pure mode). */
    val bitPerfectUsb: Boolean = false,
    val eq: EqSetting = EqSetting(),
    val upscaleRate: Int = 0,
    val bwe: EnhanceLevel = EnhanceLevel.LIGHT, // 1.0.7 (Raj): sound enhancer on by default (low-memory devices still skip it)
    val transientRestore: Boolean = true,
    /** 1.0.8: "3D Sound" (Spatial 3D) headphone processing in the engine; off by default. Never labelled Atmos. */
    val spatial3d: Boolean = false,
    /** 1.0.8: 3D Sound strength, 0..1. */
    val spatialIntensity: Float = 0.5f,
    /** 1.0.10: loudness normalisation (Settings › Sound › Volume levelling); off by default. */
    val loudnessNormalize: Boolean = false,
    /** 1.0.10: ReplayGain mode; Off by default. A ReplayGain value wins over loudness normalisation. */
    val replayGain: com.ravi.musicspot.dsp.ReplayGainMode = com.ravi.musicspot.dsp.ReplayGainMode.OFF,
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
    fun engine(lowEnd: Boolean) = EngineSettings(eq.toConfig(), upscaleRate, bwe, transientRestore, lowEnd,
        spatial = spatial3d, spatialIntensity = spatialIntensity.toDouble().coerceIn(0.0, 1.0),
        loudnessNormalize = loudnessNormalize, replayGain = replayGain)

    companion object {
        /**
         * 1.0.7 order (Raj): every lossless source first, then the rest, JioSaavn second to last and YouTube Music last.
         * Lossless: Qobuz and Tidal/Monochrome (FLAC up to 24-bit), NetEase (FLAC), then the user's own servers/files
         * (Local, Subsonic, Jellyfin: lossless when the files are). Then Audius, Jamendo, Internet Archive, SoundCloud.
         */
        val DEFAULT_PRIORITY = listOf(
            SourceKind.QOBUZ, SourceKind.MONOCHROME, SourceKind.NETEASE, SourceKind.LOCAL, SourceKind.SUBSONIC, SourceKind.JELLYFIN,
            SourceKind.AUDIUS, SourceKind.JAMENDO, SourceKind.ARCHIVE, SourceKind.SOUNDCLOUD,
            SourceKind.JIOSAAVN, SourceKind.YOUTUBE,
        )

        /** 1.0.7: lyrics providers by what they can return: word-by-word first, then line-synced, then plain. */
        val DEFAULT_LYRICS_ORDER = listOf(
            "lyricsplus", "amll", "musixmatch", "qqmusic", "netease", "kugou", // word-by-word (falls back to line)
            "lrclib", "jiosaavn_lyrics",                                         // line-by-line
            "ytmusic", "lyricsovh",                                              // plain text
        )

        /** Bumped when a default order changes in a way existing installs must get once (see SettingsRepo.load). */
        const val ORDER_REVISION = 3
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
            val lyr = (s.lyricsOrder + AppSettings.DEFAULT_LYRICS_ORDER).distinct()
            // 1.0.7 one-time move to the new orders (lossless → … → JioSaavn → YouTube Music; word → line → plain).
            // Later reorders by the user in Settings are kept, because the revision is saved with them.
            // 1.0.7 revision 2: quality on Wi-Fi and mobile data moves to Lossless once.
            val q0 = if (s.orderRevision < 2) s.copy(quality = Quality.LOSSLESS, mobileQuality = Quality.LOSSLESS) else s
            // 1.0.7 revision 3: the sound enhancer (Enhance light + transient restore) turns on once.
            val q = if (s.orderRevision < 3) q0.copy(bwe = if (q0.bwe == com.ravi.musicspot.dsp.EnhanceLevel.OFF) com.ravi.musicspot.dsp.EnhanceLevel.LIGHT else q0.bwe, transientRestore = true) else q0
            _state.value = if (s.orderRevision < 1) q.copy(sourcePriority = AppSettings.DEFAULT_PRIORITY + prio.filterNot { it in AppSettings.DEFAULT_PRIORITY },
                    lyricsOrder = AppSettings.DEFAULT_LYRICS_ORDER + lyr.filterNot { it in AppSettings.DEFAULT_LYRICS_ORDER },
                    lyricsEnabled = s.lyricsEnabled + AppSettings.DEFAULT_LYRICS_ORDER.filter { it !in s.lyricsOrder }, // new providers on
                    orderRevision = AppSettings.ORDER_REVISION)
                    .also { migrated -> file.save { migrated } }
            else if (s.orderRevision < AppSettings.ORDER_REVISION)
                q.copy(sourcePriority = prio, lyricsOrder = lyr, orderRevision = AppSettings.ORDER_REVISION).also { migrated -> file.save { migrated } }
            else s.copy(sourcePriority = prio, lyricsOrder = lyr)
        }
    }

    fun update(f: (AppSettings) -> AppSettings) { _state.update(f); file.save { _state.value } }
}
