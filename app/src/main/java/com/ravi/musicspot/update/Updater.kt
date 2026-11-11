package com.ravi.musicspot.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ravi.musicspot.BuildConfig
import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.core.PlaybackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 1.0.0 in-app updater. Asks GitHub for the latest release of [UpdateLogic.REPO] (unauthenticated, User-Agent "UpZe",
 * ETag-cached so an unchanged answer is a 304 that does not count against the rate limit), at most once every 12 h
 * on app start and on demand from Settings › About. A newer release with an .apk asset opens [UpdateSheet]. The APK is
 * downloaded with OkHttp into cacheDir/updates (progress in the sheet), its size checked, its SHA-256 compared with
 * the release asset's published digest (v1.04: a mismatch or a missing digest deletes the file and never installs),
 * and handed to the system package installer through a FileProvider content:// URI. Android itself refuses an APK not signed with the same key.
 * Every failure is silent apart from a line in the app log (Settings › About › Playback log).
 */
object Updater {
    private const val INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val AUTHORITY_SUFFIX = ".updates"

    sealed interface Download {
        data object Idle : Download
        data class Running(val done: Long, val total: Long) : Download
        data class Ready(val file: File) : Download
        data class Failed(val message: String) : Download
    }

    @Serializable private data class Cache(val etag: String? = null, val body: String? = null, val checkedAt: Long = 0)

    private val _available = MutableStateFlow<UpdateLogic.Release?>(null)
    /** The newer release to offer (the sheet shows while non-null). */
    val available: StateFlow<UpdateLogic.Release?> = _available
    private val _download = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = _download
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking
    private var downloadJob: Job? = null

    private fun log(msg: String) = runCatching { PlaybackLog.add("Updater: $msg") }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "update-check.json")
    private fun readCache(ctx: Context): Cache = runCatching { Http.json.decodeFromString(Cache.serializer(), cacheFile(ctx).readText()) }.getOrDefault(Cache())
    private fun writeCache(ctx: Context, c: Cache) = runCatching { cacheFile(ctx).writeText(Http.json.encodeToString(Cache.serializer(), c)) }

    /** App start: checks only when the setting is on and the last check is older than 12 h. Never throws. */
    fun checkOnStart(ctx: Context) {
        val app = ctx.applicationContext
        IoScope.launch {
            runCatching {
                Graph.awaitReady()
                if (!Graph.settings.value.autoUpdateCheck) return@launch
                val last = readCache(app).checkedAt
                if (System.currentTimeMillis() - last in 0 until INTERVAL_MS) return@launch
                check(app, manual = false)
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; log("start check failed: ${it.javaClass.simpleName}") }
        }
    }

    enum class Result { UPDATE, UP_TO_DATE, FAILED }

    /** Checks GitHub now on Dispatchers.IO. Manual checks also offer a version the user skipped before. */
    suspend fun check(ctx: Context, manual: Boolean): Result = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        _checking.value = true
        try {
            val cache = readCache(app)
            val req = Request.Builder().url(UpdateLogic.LATEST_URL)
                .header("User-Agent", "UpZe")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (cache.etag != null && cache.body != null) header("If-None-Match", cache.etag) }
                .build()
            val client = Http.client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
            val body: String = client.newCall(req).execute().use { r ->
                when {
                    r.code == 304 && cache.body != null -> cache.body
                    r.isSuccessful -> r.body?.string().orEmpty().also { writeCache(app, Cache(r.header("ETag"), it, System.currentTimeMillis())) }
                    r.code == 404 -> { log("no published release yet (404)"); writeCache(app, cache.copy(checkedAt = System.currentTimeMillis())); return@withContext Result.UP_TO_DATE }
                    else -> { log("GitHub answered HTTP ${r.code}"); return@withContext Result.FAILED }
                }
            }
            writeCache(app, readCache(app).copy(checkedAt = System.currentTimeMillis()))
            val rel = UpdateLogic.parseRelease(body)
            if (rel == null) { log("latest release has no usable .apk asset"); return@withContext Result.UP_TO_DATE }
            if (!UpdateLogic.isNewer(rel.version, BuildConfig.VERSION_NAME)) return@withContext Result.UP_TO_DATE
            // 1.0.7: the tag says newer, but is this exactly the APK already installed? (tag and versionName differ)
            if (installedIs(app, rel)) { log("v${rel.version} is the build already installed"); return@withContext Result.UP_TO_DATE }
            if (Graph.settings.value.skippedUpdate == "installed:" + rel.tag) { log("v${rel.version} was already installed"); return@withContext Result.UP_TO_DATE }
            if (!manual && Graph.settings.value.skippedUpdate == rel.version) { log("v${rel.version} skipped by the user"); return@withContext Result.UP_TO_DATE }
            log("v${rel.version} available (installed ${BuildConfig.VERSION_NAME})")
            _available.value = rel
            Result.UPDATE
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log("check failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.FAILED
        } finally { _checking.value = false }
    }

    fun later() { downloadJob?.cancel(); _available.value = null; _download.value = Download.Idle }

    fun skip(version: String) {
        runCatching { Graph.settings.update { it.copy(skippedUpdate = version) } }
        later()
    }

    /** Downloads the APK (or re-uses a complete earlier download), then starts the install. */
    fun update(ctx: Context, rel: UpdateLogic.Release) {
        val app = ctx.applicationContext
        if (_download.value is Download.Running) return
        (_download.value as? Download.Ready)?.let { install(ctx, it.file); return }
        downloadJob = IoScope.launch {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "UpZe-v${rel.version}.apk".replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val part = File(dir, target.name + ".part")
            try {
                dir.listFiles()?.filter { it != target && it != part }?.forEach { it.delete() }
                if (!(target.isFile && rel.apkSize > 0 && target.length() == rel.apkSize)) {
                    _download.value = Download.Running(0, rel.apkSize)
                    val req = Request.Builder().url(rel.apkUrl).header("User-Agent", "UpZe").build()
                    val client = Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                        val body = r.body ?: throw java.io.IOException("empty response")
                        val total = if (rel.apkSize > 0) rel.apkSize else body.contentLength()
                        var done = 0L; var lastEmit = 0L
                        body.byteStream().use { input -> part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (isActive) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (done - lastEmit > 128 * 1024) { lastEmit = done; _download.value = Download.Running(done, total) }
                            }
                        } }
                        if (!isActive) { part.delete(); return@launch }
                        if (rel.apkSize > 0 && part.length() != rel.apkSize) throw java.io.IOException("size mismatch (${part.length()} of ${rel.apkSize} bytes)")
                    }
                    target.delete()
                    if (!part.renameTo(target)) throw java.io.IOException("could not save the download")
                }
                log("downloaded ${target.name} (${target.length()} bytes)")
                // v1.04: the size check alone proves nothing about the bytes. Compare the file's SHA-256 with the digest
                // GitHub publishes for the release asset; anything but a match is deleted and never reaches the installer.
                val verification = UpdateLogic.verifyDownload(target, rel.apkSha256)
                if (verification != UpdateLogic.Verification.MATCH) {
                    log("v${rel.version} failed SHA-256 verification ($verification); deleted ${target.name}")
                    target.delete()
                    _download.value = Download.Failed(UpdateLogic.verificationFailedMessage(verification))
                    return@launch
                }
                log("SHA-256 verified for ${target.name}")
                // 1.0.7: never hand the installer a build that is not newer than this one (it would "update" to itself
                // and the sheet would come back on the next check). Remember the tag so it is not offered again.
                val code = archiveVersionCode(app, target)
                if (!UpdateLogic.isUpgrade(code, installedVersionCode(app))) {
                    log("v${rel.version} is versionCode $code, not newer than the installed ${installedVersionCode(app)}")
                    runCatching { Graph.settings.update { it.copy(skippedUpdate = "installed:" + rel.tag) } }
                    target.delete()
                    _download.value = Download.Failed("You already have the latest version.")
                    return@launch
                }
                _download.value = Download.Ready(target)
                withContext(Dispatchers.Main) { install(app, target) }
            } catch (e: Exception) {
                part.delete()
                if (e is kotlinx.coroutines.CancellationException) { _download.value = Download.Idle; throw e }
                log("download failed: ${e.javaClass.simpleName}: ${e.message}")
                _download.value = Download.Failed(e.message ?: "Download failed")
            }
        }
    }

    private fun installedVersionCode(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(BuildConfig.VERSION_CODE.toLong())

    private fun archiveVersionCode(ctx: Context, apk: File): Long? = runCatching {
        val pi = ctx.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return@runCatching null
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrNull()

    /** SHA-256 of the installed base APK, computed once per process (about 7 MB, read on Dispatchers.IO). */
    @Volatile private var installedSha: String? = null
    private fun installedIs(ctx: Context, rel: UpdateLogic.Release): Boolean = runCatching {
        val apk = File(ctx.applicationInfo.sourceDir)
        val sha = if (rel.apkSha256 == null) null else installedSha ?: run {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            md.digest().joinToString("") { "%02x".format(it) }.also { installedSha = it }
        }
        UpdateLogic.isInstalledBuild(rel, sha, apk.length())
    }.getOrDefault(false)

    /** True when the installer may be opened; otherwise opens the "Install unknown apps" screen for UpZe. */
    fun ensureInstallPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()) return true
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: ActivityNotFoundException) { log("no unknown-sources settings screen") }
        return false
    }

    /** Opens the system installer for [file]. Returns false when the permission screen was shown instead. */
    fun install(ctx: Context, file: File): Boolean {
        if (!ensureInstallPermission(ctx)) return false
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + AUTHORITY_SUFFIX, file)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            log("install could not start: ${e.javaClass.simpleName}: ${e.message}")
            _download.value = Download.Failed("Couldn't open the installer")
            false
        }
    }
}
