package com.ravi.musicspot.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Protocol
import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One shared OkHttp client. Every request is HTTPS: a plain http:// URL is refused before it leaves the
 * device (the manifest also sets cleartextTrafficPermitted=false). No telemetry, no analytics hosts.
 */
object Http {
    class InsecureUrlException(url: String) : IOException("Refusing non-HTTPS request to ${url.substringBefore('?')}")

    /** Fails any request that is not https (also on redirects, since interceptors see every hop as a network interceptor). */
    val httpsOnly = Interceptor { chain ->
        val req = chain.request()
        if (!req.url.isHttps) throw InsecureUrlException(req.url.toString())
        chain.proceed(req)
    }

    /**
     * v2.0.5: IPv4 addresses first. OkHttp 4 tries a host's addresses one after another, and on some Indian mobile
     * networks the IPv6 route silently black-holes: every request then spends the whole connect timeout on a dead
     * address before trying IPv4 (the JioSaavn "search timed out" in Ravi's log). Both families are still tried.
     */
    val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            return all.filterIsInstance<Inet4Address>() + all.filterNot { it is Inet4Address }
        }
    }

    /** One pool for the whole app (API calls, streams, artwork): kept-alive HTTP/2 connections are reused. */
    val pool = ConnectionPool(12, 5, TimeUnit.MINUTES)

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .connectionPool(pool)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            // v2.0.5: was 12 s; a dead route now fails fast enough to try the next address inside a search budget.
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .addInterceptor(httpsOnly)
            .addNetworkInterceptor(httpsOnly)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Warms DNS and TLS/HTTP-2 connections to the hosts the first play and Home need, in the background, so the
     * first real request skips the handshakes. HEAD requests; failures are ignored.
     */
    fun warmUp(hosts: List<String> = WARM_HOSTS) {
        hosts.forEach { h ->
            runCatching {
                client.newCall(Request.Builder().url("https://$h/").head().header("User-Agent", BROWSER_UA).build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) { response.close() }
                })
            }
        }
    }
    val WARM_HOSTS = listOf("music.youtube.com", "www.jiosaavn.com", "redirector.googlevideo.com", "aac.saavncdn.com", "lh3.googleusercontent.com", "i.ytimg.com")

    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = true }

    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    /** Cancellable suspend execution: cancelling the coroutine cancels the OkHttp call. */
    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap(), client: OkHttpClient = this.client): String {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code, url)
            return r.body?.string() ?: ""
        }
    }

    class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code from ${url.substringBefore('?').take(80)}")
}
