package com.ravi.musicspot

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ravi.musicspot.account.Account
import com.ravi.musicspot.account.AccountStore
import com.ravi.musicspot.account.KeyValue
import com.ravi.musicspot.account.SyncManager
import com.ravi.musicspot.core.CrashGuard
import com.ravi.musicspot.core.GcmBox
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.data.DownloadsRepo
import com.ravi.musicspot.data.LibraryRepo
import com.ravi.musicspot.data.QueueStore
import com.ravi.musicspot.data.SettingsRepo
import com.ravi.musicspot.lyrics.KuGouProvider
import com.ravi.musicspot.lyrics.LrcLib
import com.ravi.musicspot.lyrics.LyricsCache
import com.ravi.musicspot.lyrics.LyricsChain
import com.ravi.musicspot.lyrics.LyricsPlusProvider
import com.ravi.musicspot.lyrics.LyricsProvider
import com.ravi.musicspot.lyrics.LyricsQuery
import com.ravi.musicspot.lyrics.Lyrics
import com.ravi.musicspot.lyrics.NetEaseProvider
import com.ravi.musicspot.lyrics.YtLyricsProvider
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.playback.PlayerConnection
import com.ravi.musicspot.source.ArchiveSource
import com.ravi.musicspot.source.AudiusSource
import com.ravi.musicspot.source.JamendoSource
import com.ravi.musicspot.source.JellyfinSource
import com.ravi.musicspot.source.JioSaavnSource
import com.ravi.musicspot.source.LocalSource
import com.ravi.musicspot.source.SoundCloudSource
import com.ravi.musicspot.source.SourceManager
import com.ravi.musicspot.source.SubsonicSource
import com.ravi.musicspot.spotify.SpotifyClient
import com.ravi.musicspot.yt.Innertube
import com.ravi.musicspot.yt.YtMusic
import com.ravi.musicspot.yt.YtStreamResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

/**
 * Process-wide objects. Construction is cheap and does no disk I/O; [init] loads every store on Dispatchers.IO
 * (fixes OpenTune B2: no JSON decoding on the main thread at startup). Screens wait on [ready].
 */
object Graph {
    lateinit var app: Application; private set
    lateinit var settings: SettingsRepo; private set
    lateinit var library: LibraryRepo; private set
    lateinit var playStats: com.ravi.musicspot.data.PlayStatsRepo; private set
    lateinit var queueStore: QueueStore; private set
    lateinit var downloads: DownloadsRepo; private set
    lateinit var accounts: AccountStore; private set
    lateinit var tube: Innertube; private set
    lateinit var yt: YtMusic; private set
    lateinit var jiosaavn: JioSaavnSource; private set
    lateinit var local: LocalSource; private set
    lateinit var archive: ArchiveSource; private set
    lateinit var subsonic: SubsonicSource; private set
    lateinit var jellyfin: JellyfinSource; private set
    lateinit var sources: SourceManager; private set
    lateinit var spotify: SpotifyClient; private set
    lateinit var sync: SyncManager; private set
    lateinit var lyricsCache: LyricsCache; private set
    lateinit var player: PlayerConnection; private set

    private val readyDeferred = CompletableDeferred<Unit>()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    suspend fun awaitReady() = readyDeferred.await()

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    @Volatile var metered = false; private set
    var lowEnd = false; private set
    /**
     * 1.0.1: "lite effects" tier: low-RAM devices plus phones/tablets with a small per-app heap (memoryClass <= 128 MB)
     * or under ~1.6 GB of RAM (DeviceTier). Unlike [lowEnd] it does not force Reduce motion or solid glass; it only turns off the
     * costly extras: list 3D depth, sheet/dialog container transforms (short fades instead), large artwork renditions.
     */
    var liteFx = false; private set

    @Volatile private var initStarted = false

    /**
     * Never throws for an optional subsystem: each step that can fail on some devices (Keystore, network
     * callback, store loads, local scan) is guarded and logged via [CrashGuard.nonFatal], so one failing piece
     * degrades that feature instead of killing the app. [ready] always completes.
     */
    fun init(app: Application) {
        if (initStarted) return
        initStarted = true
        this.app = app
        val dir = app.filesDir
        lowEnd = guard("lowRam", false) { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice }
        liteFx = lowEnd || guard("liteFx", false) {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            com.ravi.musicspot.logic.DeviceTier.isLite(am.memoryClass, mi.totalMem)
        }
        settings = SettingsRepo(dir)
        library = LibraryRepo(dir)
        playStats = com.ravi.musicspot.data.PlayStatsRepo(dir)
        queueStore = QueueStore(dir)
        downloads = DownloadsRepo(app)
        accounts = AccountStore(accountPrefs(app), GcmBox.keystore())
        tube = Innertube()
        val en = { k: SourceKind -> { k in settings.value.enabledSources } }
        yt = YtMusic(tube, YtStreamResolver(tube), en(SourceKind.YOUTUBE))
        jiosaavn = JioSaavnSource(en(SourceKind.JIOSAAVN))
        local = LocalSource(app, en(SourceKind.LOCAL))
        archive = ArchiveSource(en(SourceKind.ARCHIVE))
        subsonic = SubsonicSource({ accounts.get(Account.Service.SUBSONIC) }, en(SourceKind.SUBSONIC))
        jellyfin = JellyfinSource({ accounts.get(Account.Service.JELLYFIN) }, en(SourceKind.JELLYFIN))
        sources = SourceManager(
            mapOf(
                SourceKind.YOUTUBE to yt, SourceKind.JIOSAAVN to jiosaavn, SourceKind.LOCAL to local,
                SourceKind.AUDIUS to AudiusSource(en(SourceKind.AUDIUS)), SourceKind.SOUNDCLOUD to SoundCloudSource(en(SourceKind.SOUNDCLOUD)),
                SourceKind.JAMENDO to JamendoSource({ settings.value.jamendoClientId }, en(SourceKind.JAMENDO)),
                SourceKind.ARCHIVE to archive, SourceKind.SUBSONIC to subsonic, SourceKind.JELLYFIN to jellyfin,
            ),
            priority = { settings.value.sourcePriority },
            enabled = { settings.value.enabledSources },
            premium = { tube.signedIn },
            downloaded = { downloads.resolvedFor(it) },
            streamProbe = { r -> com.ravi.musicspot.source.StreamProbe.status(r) },
        )
        spotify = SpotifyClient(dir)
        lyricsCache = LyricsCache(File(app.cacheDir, "lyrics"))
        sync = SyncManager()
        player = PlayerConnection(app)
        guard("networkCallback", Unit) { watchNetwork(app) }
        // v2.0.5: DNS + TLS/HTTP-2 to YouTube Music, JioSaavn, googlevideo and the artwork CDNs before the first tap.
        guard("warmUp", Unit) { com.ravi.musicspot.core.Http.warmUp() }
        IoScope.launch {
            try {
                guard("settings.load", Unit) { settings.load() }
                guard("library.load", Unit) { library.load() }
                guard("playStats.load", Unit) { playStats.load() }
                guard("seedArtists.load", Unit) { com.ravi.musicspot.data.SeedArtists.load() } // v2.0.15: optional artist picker seeds
                guard("downloads.load", Unit) { downloads.load() }
                guard("accounts.load", Unit) { accounts.load() }
                guard("session", Unit) {
                    tube.cookie = accounts.get(Account.Service.YOUTUBE)?.secret
                    settings.value.region?.let { tube.region = it }
                }
            } finally {
                // Screens wait on this; it must complete even if every load above failed.
                readyDeferred.complete(Unit)
                _ready.value = true
            }
            if (settings.value.enabledSources.contains(SourceKind.LOCAL)) guard("local.scan", Unit) { local.scan() }
        }
    }

    /** SharedPreferences-backed key-value for the sealed account list; a broken prefs file degrades to memory only. */
    private fun accountPrefs(app: Application): KeyValue {
        val prefs = guard("accountPrefs", null) { app.getSharedPreferences("secure_accounts", Context.MODE_PRIVATE) }
        if (prefs == null) {
            val mem = java.util.concurrent.ConcurrentHashMap<String, String>()
            return object : KeyValue {
                override fun get(key: String) = mem[key]
                override fun put(key: String, value: String?) { if (value == null) mem.remove(key) else mem[key] = value }
            }
        }
        return object : KeyValue {
            override fun get(key: String) = runCatching { prefs.getString(key, null) }.getOrNull()
            override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
        }
    }

    private inline fun <T> guard(step: String, fallback: T, block: () -> T): T =
        try { block() } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            reportNonFatal(step, e); fallback
        }

    @PublishedApi internal fun reportNonFatal(step: String, e: Throwable) =
        CrashGuard.nonFatal(if (::app.isInitialized) app else null, step, e)

    private fun watchNetwork(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        fun refresh() {
            // getNetworkCapabilities can throw SecurityException on some Android 10/11 builds; never let it escape.
            runCatching {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                metered = cm.isActiveNetworkMetered
            }
        }
        if (networkCallbackRegistered) return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
            })
            networkCallbackRegistered = true
        }.onFailure { CrashGuard.nonFatal(ctx, "registerDefaultNetworkCallback", it) }
        refresh()
    }
    @Volatile private var networkCallbackRegistered = false

    // ---- lyrics ----------------------------------------------------------------------------------------------
    private val providers: Map<String, LyricsProvider> by lazy {
        listOf(LrcLib, LyricsPlusProvider, KuGouProvider, NetEaseProvider,
            YtLyricsProvider { vid -> yt.lyrics(vid)?.let { it.text to it.source } }).associateBy { it.id }
    }
    fun lyricsProviders(): List<LyricsProvider> = providers.values.toList()

    suspend fun lyricsFor(t: Track, force: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        val key = "${t.title}|${t.artist}|${t.durationMs / 2000}"
        if (!force) { val (hit, cached) = lyricsCache.get(key); if (hit) return@withContext cached }
        val s = settings.value
        val chain = s.lyricsOrder.filter { it in s.lyricsEnabled }.mapNotNull { providers[it] }
        val found = LyricsChain.find(chain, LyricsQuery(t.title, t.artist, t.album, t.durationMs, if (t.source == SourceKind.YOUTUBE) t.nativeId else null))
        lyricsCache.put(key, found)
        found
    }
}

class MusicSpotApp : Application(), coil3.SingletonImageLoader.Factory {
    /**
     * v2.0.5: one Coil loader for the app: the shared OkHttp client (connection pool, HTTP/2, IPv4-first DNS), a
     * memory cache (20% of the app's heap) and a 200 MB disk cache in cacheDir/image_cache, so artwork shows from
     * memory/disk instantly on revisits. Request sizes come from each image's layout size (see Artwork).
     */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { com.ravi.musicspot.core.Http.client })) }
        .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, if (Graph.liteFx) 0.15 else 0.20).build() } // 1.0.1: smaller on lite devices
        .diskCache { coil3.disk.DiskCache.Builder().directory(java.io.File(context.cacheDir, "image_cache").toOkioPath()).maxSizeBytes(200L * 1024 * 1024).build() }
        .crossfade(!Graph.lowEnd)
        .build()

    override fun onCreate() {
        // Crash safety net first, before anything else can throw.
        CrashGuard.install(this)
        super.onCreate()
        // The ":crash" process only shows the crash report: no Graph, no playback, no network.
        if (CrashGuard.isCrashProcess(this)) return
        runCatching { com.ravi.musicspot.core.PlaybackLog.init(this) }
        Graph.init(this)
    }
}
