package com.ravi.musicspot.ui.account

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ravi.musicspot.account.Sessions
import com.ravi.musicspot.ui.Pills
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/*
 * Google / YouTube Music and Spotify sign-in, ported from OpenTune's LoginScreen + SignInPage (GPL-3.0-or-later):
 * the service's own page in a WebView; the session cookie is read with CookieManager once the page lands back
 * on the service, handed to the encrypted AccountStore (via Sessions), and every WebView cookie, cache and
 * storage entry is wiped when the screen goes. No JavaScript bridge; only https pages load.
 */

private const val MUSIC = "https://music.youtube.com"
private const val DESTROY_AFTER_MS = 1_500L
private const val GOOGLE_SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=" +
        "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
private const val SPOTIFY_SIGN_IN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

val YouTubeMusicRed = Color(0xFFFF0033)
val SpotifyGreen = Color(0xFF1DB954)

/** Removes every cookie, cache entry, web storage and saved form/auth data the sign-in WebView left behind. */
fun wipeWebData(ctx: Context, view: WebView? = null, then: (() -> Unit)? = null) {
    view?.let { v -> runCatching { v.clearCache(true); v.clearHistory(); v.clearFormData(); v.clearSslPreferences() } }
    runCatching { WebStorage.getInstance().deleteAllData() }
    runCatching { WebViewDatabase.getInstance(ctx).clearHttpAuthUsernamePassword() }
    runCatching {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { cm.flush(); then?.invoke() }
    }.onFailure { then?.invoke() }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.lockDown() {
    settings.apply {
        javaScriptEnabled = true // Google's and Spotify's sign-in pages need it; no addJavascriptInterface anywhere.
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        setGeolocationEnabled(false)
    }
}

/** https only: anything else (http, intent:, market:, file:) is refused inside the sign-in page. */
private fun blocked(url: Uri?): Boolean = url?.scheme?.lowercase() != "https"

/**
 * A WebView that wipes old web data, then opens [startUrl]. [check] is run after every page load / history
 * update (and again a few seconds later, as cookies can land just after a page finishes). Torn down and wiped
 * once off screen.
 */
@Composable
private fun SignInWebView(
    startUrl: String,
    gone: AtomicBoolean,
    web: AtomicReference<WebView?>,
    onLoading: (Boolean) -> Unit,
    onUnavailable: () -> Unit,
    /** Optional user agent rewrite (Spotify: drop the WebView markers so its login app serves the normal page). */
    userAgent: ((String) -> String)? = null,
    onJsError: ((String) -> Unit)? = null,
    check: (WebView, String?) -> Unit,
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val view = runCatching { CookieManager.getInstance().setAcceptCookie(true); WebView(context) }
                .onFailure { onUnavailable() }.getOrNull() ?: return@AndroidView View(context)
            view.apply {
                lockDown()
                userAgent?.let { ua -> runCatching { settings.userAgentString = ua(settings.userAgentString ?: "") } }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = blocked(request.url)
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { if (!gone.get()) onLoading(true) }
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (gone.get()) return
                        onLoading(false)
                        check(view, url)
                        for (wait in listOf(1_000L, 2_500L, 5_000L)) main.postDelayed({ if (!gone.get()) check(view, view.url) }, wait)
                    }
                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { if (!gone.get()) check(view, url) }
                }
                if (onJsError != null) webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                        if (m != null && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && !gone.get())
                            onJsError(m.message().take(160))
                        return true
                    }
                }
                web.set(this)
                // Start from a clean jar so an old half-finished sign-in can't be picked up.
                wipeWebData(context, this) { main.post { if (!gone.get()) loadUrl(startUrl) } }
            }
        },
        onRelease = { view ->
            gone.set(true)
            web.set(null)
            main.removeCallbacksAndMessages(null)
            (view as? WebView)?.run {
                stopLoading()
                onPause()
                webViewClient = WebViewClient()
                wipeWebData(context, this)
                (parent as? ViewGroup)?.removeView(this)
                // Destroyed well after it has left the screen, never inside one of its own callbacks.
                main.postDelayed({ runCatching { destroy() } }, DESTROY_AFTER_MS)
            }
        },
    )
}

/** Google → YouTube Music sign-in. [onDone] gets true when an account was saved. */
@Composable
fun GoogleLoginScreen(onDone: (Boolean) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val gone = remember { AtomicBoolean(false) }
    val finished = remember { AtomicBoolean(false) }
    val left = remember { AtomicBoolean(false) }
    val web = remember { AtomicReference<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { gone.set(true) } }
    val leave = { ok: Boolean -> if (left.compareAndSet(false, true)) onDone(ok) }
    BackHandler {
        val page = web.get()
        if (!saving && page != null && page.canGoBack()) page.goBack() else if (!saving) leave(false)
    }

    val sentToMusic = remember { AtomicBoolean(false) }
    val check: (WebView, String?) -> Unit = check@{ view, url ->
        if (gone.get() || finished.get()) return@check
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return@check
        val host = uri.host?.lowercase() ?: return@check
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return@check
        if (uri.path?.startsWith("/signin") == true) return@check
        val cookie = CookieManager.getInstance().getCookie(MUSIC) ?: return@check
        if (!Sessions.isYouTubeSession(cookie)) return@check
        // Some phones land on m.youtube.com; the session is the same, but YouTube Music sets its own cookies.
        if (host != "music.youtube.com") { if (sentToMusic.compareAndSet(false, true)) view.loadUrl("$MUSIC/"); return@check }
        if (!finished.compareAndSet(false, true)) return@check
        view.stopLoading()
        saving = true
        scope.launch {
            val name = runCatching { Sessions.signInYouTube(cookie) }
            name.onSuccess { Pills.show(if (it != null) "Signed in as $it · syncing your library" else "Signed in to YouTube Music · syncing") }
                .onFailure { Pills.show("Couldn't save the sign-in: ${it.message}") }
            main.post { leave(name.isSuccess) }
        }
    }

    SignInPage(
        service = "YouTube Music",
        brand = YouTubeMusicRed,
        icon = Icons.Rounded.PlayCircle,
        gives = "Your likes, playlists, library, history and subscriptions",
        loading = loading,
        saving = if (saving) "Signing you in…" else null,
        onBack = { if (!saving) leave(false) },
        unavailable = if (unavailable) "Sign-in needs Android System WebView, which isn't working on this device. Update or enable it in the Play Store and try again. UpZe works fully without an account." else null,
        hint = "Use your Google account email and password. Passkey or \"Sign in with phone\" prompts may not work inside apps.",
    ) {
        SignInWebView(GOOGLE_SIGN_IN_URL, gone, web, { loading = it }, { unavailable = true; loading = false }, check = check)
    }
}

/** Spotify sign-in: captures sp_dc, validates it with a real token fetch, then imports. */
@Composable
fun SpotifyLoginScreen(onDone: (Boolean) -> Unit) {
    // v2.0.8: sign-in moved to its own Activity (OpenTune's approach), clear of the app's glass layers that left the
    // WebView drawing black. This route just opens it and returns with the result.
    val left = remember { AtomicBoolean(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (left.compareAndSet(false, true)) onDone(r.resultCode == android.app.Activity.RESULT_OK)
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var launched by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!launched) { launched = true; launcher.launch(android.content.Intent(ctx, SpotifyConnectActivity::class.java)) }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}

/** Pre-v2.0.8 in-app Spotify sign-in (kept for reference; no longer routed). */
@Suppress("unused")
@Composable
private fun SpotifyLoginScreenInline(onDone: (Boolean) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    // The Spotify page loaded but drew nothing (its script failed in this phone's WebView): offer the fallback.
    var blank by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    // v2.0.8: which Spotify page/UA pair to try (desktop login, mobile login, web player); "Try another page" cycles.
    var variant by remember { mutableIntStateOf(0) }
    var jsError by remember { mutableStateOf<String?>(null) }
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    val webMajor = remember { webViewMajor(ctx0) }
    // An engine this old can't run Spotify's login app at all (it draws the dark background and nothing else).
    val tooOld = webMajor in 1 until MIN_SPOTIFY_WEBVIEW
    LaunchedEffect(tooOld) { if (tooOld) blank = true }
    val left = remember { AtomicBoolean(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    val leave = { ok: Boolean -> if (left.compareAndSet(false, true)) onDone(ok) }

    key(attempt, variant) {
        val gone = remember { AtomicBoolean(false) }
        val finished = remember { AtomicBoolean(false) }
        val web = remember { AtomicReference<WebView?>(null) }
        DisposableEffect(Unit) { onDispose { gone.set(true) } }
        BackHandler {
            val page = web.get()
            if (!saving && page != null && page.canGoBack()) page.goBack() else if (!saving) leave(false)
        }
        val emptyChecks = remember { java.util.concurrent.atomic.AtomicInteger(0) }
        val submit: (String) -> Unit = { spDc ->
            saving = true
            scope.launch {
                val r = runCatching { Sessions.signInSpotify(spDc) }
                saving = false
                r.onSuccess { name ->
                    Pills.show(if (name != null) "Spotify connected as $name · importing" else "Spotify connected · importing")
                    main.post { leave(true) }
                }.onFailure {
                    error = "Spotify didn't accept that sign-in (${it.message ?: "unknown error"}). Try again."
                    finished.set(false)
                }
            }
        }
        val check: (WebView, String?) -> Unit = check@{ view, url ->
            if (gone.get() || finished.get()) return@check
            val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.lowercase() ?: return@check
            if (host != "spotify.com" && !host.endsWith(".spotify.com")) return@check
            val cm = CookieManager.getInstance()
            val spDc = Sessions.spDcFrom(cm.getCookie("https://open.spotify.com")) ?: Sessions.spDcFrom(cm.getCookie("https://accounts.spotify.com"))
            if (spDc == null) {
                // No sign-in yet: is the login form actually on screen? Three empty reads in a row = blank page.
                runCatching {
                    view.evaluateJavascript("(function(){var b=document.body;return b?(b.innerText||'').trim().length+document.querySelectorAll('input,button').length:0})()") { v ->
                        if (gone.get()) return@evaluateJavascript
                        if ((v?.trim('"')?.toIntOrNull() ?: 0) == 0) { if (emptyChecks.incrementAndGet() >= 3) blank = true }
                        else { emptyChecks.set(0); blank = false }
                    }
                }
                return@check
            }
            if (!finished.compareAndSet(false, true)) return@check
            view.stopLoading()
            saving = true
            scope.launch {
                val r = runCatching { Sessions.signInSpotify(spDc) }
                saving = false
                r.onSuccess { name ->
                    Pills.show(if (name != null) "Spotify connected as $name · importing" else "Spotify connected · importing")
                    main.post { leave(true) }
                }.onFailure {
                    error = "Spotify didn't accept that sign-in (${it.message ?: "unknown error"}). Try again."
                    attempt++ // fresh, wiped WebView
                }
            }
        }
        SignInPage(
            service = "Spotify",
            brand = SpotifyGreen,
            icon = Icons.Rounded.MusicNote,
            gives = "Import your Liked Songs and playlists",
            loading = loading,
            saving = if (saving) "Checking your Spotify sign-in…" else null,
            onBack = { if (!saving) leave(false) },
            unavailable = if (unavailable) "Sign-in needs Android System WebView, which isn't working on this device. Update or enable it in the Play Store and try again." else null,
            hint = error,
            note = "Spotify audio can't be streamed in other apps. UpZe reads your library and plays each song from your other sources (YouTube Music, JioSaavn, your files and servers).",
        ) {
            Box(Modifier.fillMaxSize()) {
                val v = SPOTIFY_VARIANTS[variant % SPOTIFY_VARIANTS.size]
                SignInWebView(v.url, gone, web, { loading = it }, { unavailable = true; loading = false },
                    { ua -> v.ua(ua) }, onJsError = { jsError = it }, check = check)
                // v2.0.8: the escape hatch no longer waits for the page to report "loaded" (Spotify's page could keep
                // loading forever on a black screen, which hid it). It appears after 3 s whatever the page does, as a
                // solid button that reads on Spotify's dark background.
                var showHelp by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(3_000); showHelp = true }
                if (showHelp && !blank && !saving) androidx.compose.material3.Button(
                    onClick = { blank = true },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                ) { Text("Can't see the login option? Tap here", fontWeight = FontWeight.SemiBold) }
                if (blank && !saving) SpotifyFallback(
                    value = manual, onValue = { manual = it },
                    webInfo = buildString {
                        append(if (webMajor > 0) "Android System WebView $webMajor" else "Android System WebView: unknown version")
                        if (tooOld) append(" (too old for Spotify, needs $MIN_SPOTIFY_WEBVIEW+)")
                        append(" · page ${variant % SPOTIFY_VARIANTS.size + 1}/${SPOTIFY_VARIANTS.size}")
                        jsError?.let { append("\nPage error: ").append(it) }
                    },
                    onOtherPage = { blank = false; emptyChecks.set(0); jsError = null; variant++ },
                    onReload = { blank = false; emptyChecks.set(0); jsError = null; attempt++ },
                    onSubmit = { val v = Sessions.spDcFrom("sp_dc=" + manual.trim().removePrefix("sp_dc=")) ?: manual.trim().takeIf { it.length > 40 }
                        if (v == null) error = "That doesn't look like an sp_dc value." else if (finished.compareAndSet(false, true)) submit(v) },
                )
            }
        }
    }
}

/** The service's own page in a rounded card, with who it's for, what it brings and what's kept. */
@Composable
private fun SignInPage(
    service: String,
    brand: Color,
    icon: ImageVector,
    gives: String,
    loading: Boolean,
    saving: String?,
    onBack: () -> Unit,
    unavailable: String? = null,
    hint: String? = null,
    note: String? = null,
    web: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = scheme.background, contentColor = scheme.onBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime))) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Sign in", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(brand), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(service, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(gives, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            InfoChip(Icons.Rounded.Lock, "You sign in on $service's own page. Only the sign-in cookie is kept, encrypted on this phone; your password never reaches UpZe.")
            if (note != null) InfoChip(Icons.Rounded.Info, note)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            val card = RoundedCornerShape(24.dp)
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(card).border(1.dp, scheme.outlineVariant, card).background(Color.White),
            ) {
                if (unavailable == null) web()
                else Text(unavailable, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = scheme.onSurface,
                    modifier = Modifier.fillMaxSize().background(scheme.surfaceContainer).padding(32.dp))
                val shown by animateFloatAsState(if (loading && unavailable == null && saving == null) 1f else 0f, label = "signInLoading")
                if (shown > 0f) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).graphicsLayer { alpha = shown }, color = brand, trackColor = Color.Transparent)
                if (saving != null) Column(
                    Modifier.fillMaxSize().background(scheme.background.copy(alpha = 0.92f)),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = brand)
                    Text(saving, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoChip(icon: ImageVector, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}



/**
 * Spotify's login app refuses to draw for the stock WebView user agent on some phones (Android 10 tablets showed a
 * blank black page). Present the same engine as plain Chrome: drop the "; wv" marker and the "Version/4.0" token.
 */
private fun spotifyUserAgent(default: String): String {
    // v2.0.7b: the mobile login page still hid its options (it pushes Android users to the Spotify app). The
    // desktop page always shows email/password, Google, Facebook and Apple, so present desktop Chrome on the
    // same engine version.
    val chrome = Regex("""Chrome/([\d.]+)""").find(default)?.groupValues?.get(1) ?: "130.0.0.0"
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
}

private const val MIN_SPOTIFY_WEBVIEW = 90

private class SpotifyPage(val url: String, val ua: (String) -> String)

/** Pages tried in turn: desktop login, mobile login (stock UA minus WebView markers), the web player's own login. */
private val SPOTIFY_VARIANTS = listOf(
    SpotifyPage(SPOTIFY_SIGN_IN_URL, ::spotifyUserAgent),
    SpotifyPage("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F", ::mobileChromeUserAgent),
    SpotifyPage("https://open.spotify.com/", ::spotifyUserAgent),
)

/** Plain mobile Chrome: the stock WebView UA without "; wv" and "Version/4.0". */
private fun mobileChromeUserAgent(default: String): String =
    default.replace("; wv)", ")").replace(Regex("""\s?Version/[\d.]+"""), "")

/** Major version of the WebView engine installed on this device (0 when it can't be read). */
private fun webViewMajor(ctx: Context): Int = runCatching {
    val pkg = if (android.os.Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
    pkg?.versionName?.substringBefore('.')?.toIntOrNull()
        ?: Regex("""Chrome/(\d+)""").find(WebSettings.getDefaultUserAgent(ctx))?.groupValues?.get(1)?.toIntOrNull()
}.getOrNull() ?: 0

/** Shown over a blank Spotify page: reload, update WebView, or paste the sp_dc cookie from a browser. */
@Composable
private fun SpotifyFallback(value: String, onValue: (String) -> Unit, webInfo: String, onOtherPage: () -> Unit, onReload: () -> Unit, onSubmit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxSize().background(scheme.surfaceContainer).padding(20.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Spotify's sign-in page didn't load", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Spotify's login app needs an up-to-date Android System WebView. Try another Spotify page first; if none shows the login, update WebView from the Play Store and reload.",
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Text(webInfo, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        androidx.compose.material3.Button(onClick = onOtherPage, modifier = Modifier.fillMaxWidth()) { Text("Try another Spotify page") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilledTonalButton(onClick = onReload) { Text("Reload") }
            androidx.compose.material3.OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Update WebView") }
        }
        Text("Paste sp_dc: sign in at open.spotify.com in Chrome on a computer, open Developer tools › Application › Cookies › open.spotify.com and copy the sp_dc value. It's stored encrypted on this phone.",
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        androidx.compose.material3.OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), singleLine = true, label = { Text("sp_dc") })
        androidx.compose.material3.Button(onClick = onSubmit, enabled = value.isNotBlank()) { Text("Connect") }
    }
}
