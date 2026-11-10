@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ravi.musicspot.playback

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * 1.0.2 seamless source switch. Up to 1.0.1 a stream that failed mid-song (an expired or refused googlevideo URL,
 * a CDN 5xx, the phone hopping from Wi-Fi to mobile data) was a fatal load error: the player stopped, showed paused,
 * re-resolved from scratch and re-buffered, an audible gap of several seconds.
 *
 * Now such a load is *retried* while the player keeps playing from its buffer (15–50 s of audio). Before each retry
 * [onRetry] drops the stale URL, so the retry goes back through ResolvingDataSource, gets a fresh URL and continues
 * at the same byte offset: the listener hears nothing. Only when a fresh resolution would deliver *different* bytes
 * (another source or rendition, [StreamSwitchException]) is the error passed on, and the service then switches at
 * the same time position with a short fade-in.
 */
class SeamlessRetryPolicy(
    private val onRetry: (trackUri: android.net.Uri, refusedUrl: String?) -> Unit,
    /** 1.0.5: the open URL expired (long pause): drop exactly that URL so the retry resolves a fresh one. */
    private val onExpired: (trackUri: android.net.Uri, expiredUrl: String) -> Unit = { uri, _ -> onRetry(uri, null) },
) : DefaultLoadErrorHandlingPolicy(MIN_RETRIES) {

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = info.exception
        if (chain(e).any { it is StreamSwitchException || it is InterruptedException }) return C.TIME_UNSET
        if (info.errorCount > MAX_RETRIES) return C.TIME_UNSET
        // 1.0.5: an expired URL (ChunkedDataSource, after a long pause) is always the refresh path: re-resolve and
        // continue at the same byte offset. Before, it would have been an untyped IOException retried with the same
        // URL (DefaultDataSource reuse), i.e. 403 on every retry until the error became fatal.
        chain(e).filterIsInstance<StreamUrlExpiredException>().firstOrNull()?.let { x ->
            runCatching { onExpired(info.loadEventInfo.dataSpec.uri, x.url) }
            return delayFor(1)
        }
        val http = chain(e).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (http == null) {
            // Network drop / Wi-Fi ↔ mobile switch: the same URL still works, just reconnect quickly. Parser and file
            // errors stay fatal (the default policy's call).
            val d = super.getRetryDelayMsFor(info)
            return if (d == C.TIME_UNSET) d else minOf(d, delayFor(info.errorCount))
        }
        if (http.responseCode !in RETRY_CODES) return super.getRetryDelayMsFor(info)
        val refused = http.takeIf { it.responseCode in setOf(403, 404, 410) }?.dataSpec?.uri?.toString()
        runCatching { onRetry(info.loadEventInfo.dataSpec.uri, refused) }
        return delayFor(info.errorCount)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_RETRIES + 1

    companion object {
        const val MIN_RETRIES = 6
        const val MAX_RETRIES = 6
        /** Refused / expired / gone / server trouble: a fresh URL usually fixes all of them. */
        val RETRY_CODES = setOf(403, 404, 408, 410, 429, 500, 502, 503, 504)
        /** 0.3 s, 0.8 s, 1.5 s, 2.5 s, 4 s, 5 s: well inside a 15 s+ buffer. */
        fun delayFor(errorCount: Int): Long = listOf(300L, 800L, 1_500L, 2_500L, 4_000L, 5_000L)[(errorCount - 1).coerceIn(0, 5)]
        internal fun chain(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }
    }
}

/** A fresh resolution mid-song returned other bytes (another source/rendition); the byte offset can't be reused. */
class StreamSwitchException(val trackId: String, val from: String, val to: String) :
    java.io.IOException("Stream changed mid-song ($from → $to)")
