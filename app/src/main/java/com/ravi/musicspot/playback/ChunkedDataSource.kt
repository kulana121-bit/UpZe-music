@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ravi.musicspot.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.PlaybackLog
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException // v2.0.14:

/**
 * v2.0.5: googlevideo streams are fetched in ranged chunks of at most [CHUNK_BYTES], always with a `Range` header.
 *
 * Ravi's log: the resolver's 16 KB probe (ranged) was answered 206 audio/webm, and ~4 s later ExoPlayer's own request
 * for the same URL got 403. ExoPlayer's first request from position 0 with an unknown length carries no Range header
 * at all (and `Accept-Encoding: identity`); googlevideo refuses unbounded requests from these app clients, which is
 * why NewPipe and older OpenTune fetch in chunks. Every other host passes straight through to [upstream].
 */
class ChunkedDataSource(private val upstream: HttpDataSource, private val chunk: Long = CHUNK_BYTES) : DataSource {
    private var spec: DataSpec? = null
    private var chunked = false
    private var nextPos = 0L          // absolute position of the next byte to read
    private var endPos = C.LENGTH_UNSET.toLong() // absolute end (exclusive) when known
    private var chunkLeft = 0L
    private var lastChunk = false
    private var chunkRead = 0L        // v2.0.14: bytes read from the current chunk
    private var zeroChunks = 0        // v2.0.14: consecutive chunks that yielded no data

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = isGoogleVideo(dataSpec.uri)
        if (!chunked) return upstream.open(dataSpec)
        nextPos = dataSpec.position
        zeroChunks = 0 // v2.0.14:
        endPos = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else C.LENGTH_UNSET.toLong()
        openChunk()
        val total = totalFromContentRange(upstream.responseHeaders)
        if (endPos == C.LENGTH_UNSET.toLong() && total != null) endPos = total
        return if (endPos != C.LENGTH_UNSET.toLong()) endPos - dataSpec.position else C.LENGTH_UNSET.toLong()
    }

    private fun openChunk() {
        val s = spec ?: throw IOException("closed") // v2.0.14:
        chunkRead = 0 // v2.0.14:
        val len = if (endPos != C.LENGTH_UNSET.toLong()) minOf(chunk, endPos - nextPos) else chunk
        // DataSpec.subrange keeps the per-request headers (the minting client's User-Agent, Origin/Referer).
        val sub = s.buildUpon().setPosition(nextPos).setLength(len).build()
        val opened = upstream.open(sub)
        chunkLeft = if (opened != C.LENGTH_UNSET.toLong()) opened else len
        // The server sent fewer bytes than asked for: this chunk reaches the end of the stream.
        lastChunk = opened != C.LENGTH_UNSET.toLong() && opened < len
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            if (endPos != C.LENGTH_UNSET.toLong() && nextPos >= endPos) return C.RESULT_END_OF_INPUT
            if (chunkLeft > 0) {
                val n = upstream.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
                if (n == C.RESULT_END_OF_INPUT) {
                    // Server ended the chunk early: with an unknown total that is the end of the stream.
                    if (endPos == C.LENGTH_UNSET.toLong()) return C.RESULT_END_OF_INPUT
                    chunkLeft = 0
                } else { nextPos += n; chunkLeft -= n; chunkRead += n; return n }
            }
            // Chunk done. A short last chunk (fewer bytes than asked for) with an unknown total means end of stream.
            if (endPos == C.LENGTH_UNSET.toLong() && lastChunk) return C.RESULT_END_OF_INPUT
            // v2.0.14: guard against an endless loop of empty chunks.
            if (chunkRead == 0L) { if (++zeroChunks >= 3) throw IOException("no data") } else zeroChunks = 0
            upstream.close()
            openChunk()
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() { spec = null; upstream.close() }

    class Factory(private val upstream: HttpDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource())
    }

    companion object {
        /** ≤ 1 MB per googlevideo request. */
        const val CHUNK_BYTES = 1L shl 20

        fun isGoogleVideo(uri: Uri?): Boolean = uri?.host?.endsWith("googlevideo.com") == true

        /** "bytes 0-1048575/3456789" → 3456789. */
        fun totalFromContentRange(headers: Map<String, List<String>>): Long? =
            headers.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
                ?.substringAfterLast('/')?.trim()?.toLongOrNull()

        /**
         * The media client: the shared [Http.client] (same pool, DNS and TLS as the resolver's probe) plus a network
         * interceptor that writes ExoPlayer's real googlevideo request headers and status into [PlaybackLog]
         * (header names, User-Agent, Range; first chunk and every failure only).
         */
        val mediaClient: OkHttpClient by lazy {
            Http.client.newBuilder().addNetworkInterceptor(Interceptor { chain ->
                val req = chain.request()
                val res = chain.proceed(req)
                if (req.url.host.endsWith("googlevideo.com")) {
                    val range = req.header("Range")
                    if (res.code >= 400 || range == null || range.startsWith("bytes=0-")) {
                        PlaybackLog.add("ExoPlayer googlevideo request: HTTP ${res.code} ${res.header("Content-Type") ?: "-"} · Range ${range ?: "(none)"} · " +
                            "UA ${req.header("User-Agent")?.take(60) ?: "(none)"} · headers [${req.headers.names().sorted().joinToString(",")}] · ${req.url}")
                    }
                }
                res
            }).build()
        }

        /**
         * OkHttpDataSource on [mediaClient]. The browser UA is only a default request property: OkHttpDataSource merges
         * default properties with the DataSpec's headers into one map, so a googlevideo request carries exactly one
         * User-Agent, the minting client's (never setUserAgent(), which would add a second one).
         */
        fun httpFactory(): OkHttpDataSource.Factory = OkHttpDataSource.Factory(mediaClient).setDefaultRequestProperties(mapOf("User-Agent" to Http.BROWSER_UA))
    }
}
