package com.ravi.musicspot.yt

import com.ravi.musicspot.core.Http
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

/** NewPipeExtractor's HTTP layer on our https-only OkHttp client. */
object NewPipeBridge : Downloader() {
    private val client by lazy { Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }
    @Volatile private var ready = false

    fun ensureInit(region: String = "IN") {
        if (ready) return
        synchronized(this) {
            if (!ready) { NewPipe.init(this, Localization("en", region), ContentCountry(region)); ready = true }
        }
    }

    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val rb = Request.Builder().url(request.url()).method(request.httpMethod(), body)
            .header("User-Agent", Http.BROWSER_UA)
        request.headers().forEach { (k, vs) -> rb.removeHeader(k); vs.forEach { rb.addHeader(k, it) } }
        client.newCall(rb.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("rate limited", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}
