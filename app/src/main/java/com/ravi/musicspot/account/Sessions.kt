package com.ravi.musicspot.account

import com.ravi.musicspot.Graph
import com.ravi.musicspot.yt.Innertube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a cookie captured by a sign-in WebView into a saved account: encrypted in [AccountStore], account
 * name/avatar fetched, sync started. The cookie never leaves this object except into the store and the clients.
 */
object Sessions {
    /** True when a YouTube/Google cookie header carries a signed-in session (SAPISID or its __Secure variants). */
    fun isYouTubeSession(cookieHeader: String?): Boolean = cookieHeader != null && Innertube.sapisidFrom(cookieHeader) != null

    /** The sp_dc value from a cookie header, if present. */
    fun spDcFrom(cookieHeader: String?): String? = cookieHeader?.split(';')?.map { it.trim() }
        ?.firstOrNull { it.startsWith("sp_dc=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }

    /** Saves the YouTube Music session and starts the sync. Returns the account name when YT Music reports one. */
    suspend fun signInYouTube(cookie: String): String? = withContext(Dispatchers.IO) {
        Graph.tube.cookie = cookie
        val info = runCatching { Graph.yt.accountInfo() }.getOrNull()
        Graph.accounts.upsert(Account("youtube", Account.Service.YOUTUBE, name = info?.name, email = info?.email, avatarUrl = info?.thumbnailUrl, secret = cookie))
        Graph.settings.update { it.copy(guest = false) }
        Graph.sync.syncYouTube()
        info?.name
    }

    /** Validates sp_dc with a real token fetch; on success saves it and starts the import. Throws when it isn't a session. */
    suspend fun signInSpotify(spDc: String): String? = withContext(Dispatchers.IO) {
        Graph.spotify.signedOut()
        val p = Graph.spotify.profile(spDc)
        Graph.accounts.upsert(Account("spotify", Account.Service.SPOTIFY, name = p.name, avatarUrl = p.imageUrl, secret = spDc))
        Graph.sync.syncSpotify()
        p.name
    }
}
