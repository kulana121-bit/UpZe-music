package com.ravi.musicspot.ui.account

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ravi.musicspot.account.Sessions
import com.ravi.musicspot.ui.Pills
import com.ravi.musicspot.ui.theme.MusicSpotTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * v2.0.8 Spotify sign-in, done the way OpenTune 1.0.4 does it (SpotifyConnectActivity, GPL-3.0-or-later): Spotify's
 * own page in an Activity of its own. Inside the main app the WebView sat under glass / graphicsLayer effects, which
 * can leave a WebView drawing a black or stale frame - the "no login options" black page. Here it is a plain view in
 * a plain FrameLayout. Popups (Facebook / Apple / Google) open in a child WebView. Once Spotify sets sp_dc, the cookie
 * is validated and saved encrypted (Sessions.signInSpotify), the web data is wiped and the screen closes.
 */
class SpotifyConnectActivity : ComponentActivity() {
    private var frame: FrameLayout? = null
    private var web: WebView? = null
    private var popup: WebView? = null
    private var done = false
    private var loading by mutableStateOf(true)
    private var saving by mutableStateOf(false)
    private var unavailable by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val page = runCatching { newWebView() }.getOrNull()
        if (page == null) unavailable = true else {
            web = page
            frame = FrameLayout(this).apply { addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
            if (savedInstanceState == null) page.loadUrl(LOGIN) else page.restoreState(savedInstanceState)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                when {
                    saving -> Unit
                    popup != null -> closePopup()
                    w != null && w.canGoBack() -> w.goBack()
                    else -> finish()
                }
            }
        })
        setContent {
            MusicSpotTheme(artUrl = null, dynamic = false) {
                val scheme = MaterialTheme.colorScheme
                Surface(Modifier.fillMaxSize(), color = scheme.background, contentColor = scheme.onBackground) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime))) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            IconButton(onClick = { if (!saving) finish() }, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                            Text("Sign in", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(SpotifyGreen), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = Color.White) }
                            Column(Modifier.weight(1f)) {
                                Text("Spotify", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text("Liked Songs, playlists and each song's Canvas", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                            }
                        }
                        Text(error ?: "If Google sign-in won't open here, use your Spotify email or username, or a one-time code by email.",
                            style = MaterialTheme.typography.labelMedium, color = if (error != null) scheme.error else scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                        val card = RoundedCornerShape(24.dp)
                        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(card).background(Color.White)) {
                            val f = frame
                            if (f != null && !unavailable) AndroidView(factory = { f }, modifier = Modifier.fillMaxSize())
                            else Text("This phone's web view isn't working, so Spotify's sign-in page can't open. Update Android System WebView in the Play Store and try again.",
                                textAlign = TextAlign.Center, color = scheme.onSurface, modifier = Modifier.fillMaxSize().background(scheme.surfaceContainer).padding(32.dp))
                            if (loading && !saving) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = SpotifyGreen, trackColor = Color.Transparent)
                            if (saving) Column(Modifier.fillMaxSize().background(scheme.background.copy(alpha = 0.94f)),
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = SpotifyGreen)
                                Text("Connecting Spotify…", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        popup?.let { runCatching { it.destroy() } }
        web?.let { w -> (w.parent as? ViewGroup)?.removeView(w); runCatching { w.destroy() } }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(this).apply {
        setBackgroundColor(android.graphics.Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Some sign-in pages hide or refuse themselves to an app's embedded browser.
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("""Version/\d+\.\d+ """), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = Client()
        webChromeClient = Chrome()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme
            return scheme != "https" && scheme != "http"
        }
        override fun onPageFinished(view: WebView, url: String?) { loading = false; signedIn() }
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { signedIn() }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { loading = newProgress < 100 }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val container = frame ?: return false
            val child = newWebView()
            popup = child
            container.addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }
        override fun onCloseWindow(window: WebView) { if (window == popup) closePopup() }
    }

    private fun closePopup() {
        val child = popup ?: return
        popup = null
        (child.parent as? ViewGroup)?.removeView(child)
        runCatching { child.destroy() }
        signedIn()
    }

    /** Spotify has set sp_dc: validate it, keep it (encrypted), wipe the web data and close. */
    private fun signedIn() {
        if (done) return
        val cm = CookieManager.getInstance()
        val spDc = Sessions.spDcFrom(cm.getCookie("https://open.spotify.com")) ?: Sessions.spDcFrom(cm.getCookie("https://accounts.spotify.com")) ?: return
        done = true
        saving = true
        error = null
        scope.launch {
            val r = runCatching { Sessions.signInSpotify(spDc) }
            r.onSuccess { name ->
                Pills.show(if (name != null) "Spotify connected as $name · importing" else "Spotify connected · importing")
                wipeWebData(this@SpotifyConnectActivity, web) { }
                setResult(Activity.RESULT_OK)
                finish()
            }.onFailure {
                saving = false
                done = false
                // Start over from a clean jar so the same rejected cookie isn't picked up again.
                wipeWebData(this@SpotifyConnectActivity, web) { web?.post { web?.loadUrl(LOGIN) } }
                error = "Spotify didn't accept that sign-in (${it.message ?: "unknown error"}). Try again."
            }
        }
    }

    companion object {
        const val LOGIN = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    }
}
