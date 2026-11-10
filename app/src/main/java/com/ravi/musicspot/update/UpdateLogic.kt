package com.ravi.musicspot.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 1.0.0 in-app updater: pure parts (version comparison, GitHub release JSON parsing, notes cleanup). Unit-tested. */
object UpdateLogic {
    const val REPO = "kulana121-bit/UpZe-music"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    data class Release(
        val tag: String,
        val version: String,
        val name: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val pageUrl: String?,
        /** 1.0.7: GitHub's asset digest ("sha256:<hex>"), lower-case hex only; null on older releases. */
        val apkSha256: String? = null,
    )

    /** A semantic version: numeric core plus an optional pre-release ("1.2.0-beta.1"); build metadata is ignored. */
    data class SemVer(val core: List<Int>, val pre: List<String>) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            for (i in 0 until maxOf(core.size, other.core.size)) {
                val c = core.getOrElse(i) { 0 }.compareTo(other.core.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            // A release ranks above any of its pre-releases.
            if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
            for (i in 0 until maxOf(pre.size, other.pre.size)) {
                val a = pre.getOrNull(i) ?: return -1
                val b = other.pre.getOrNull(i) ?: return 1
                val an = a.toIntOrNull(); val bn = b.toIntOrNull()
                val c = when {
                    an != null && bn != null -> an.compareTo(bn)
                    an != null -> -1
                    bn != null -> 1
                    else -> a.compareTo(b)
                }
                if (c != 0) return c
            }
            return 0
        }
    }

    /** "v1.0.1", "1.0.1", "V2.3", "1.0.0-rc.1+build5" → SemVer; null when it has no leading number. */
    fun parseVersion(raw: String?): SemVer? {
        val s = raw?.trim()?.removePrefix("v")?.removePrefix("V")?.substringBefore('+')?.takeIf { it.isNotEmpty() } ?: return null
        val coreStr = s.substringBefore('-')
        val pre = if ('-' in s) s.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
        val core = coreStr.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: return null }
        if (core.isEmpty()) return null
        return SemVer(core, pre)
    }

    /** True when [remote] is a strictly newer version than [current]. Unparseable versions never count as newer. */
    fun isNewer(remote: String?, current: String?): Boolean {
        val r = parseVersion(remote) ?: return false
        val c = parseVersion(current) ?: return true
        return r > c
    }

    /**
     * 1.0.7: is the release's APK the build that is already installed? The tag alone can't say: v1.01 on GitHub was
     * published with versionName "v1", so the app compared "1.01" with "1" forever and offered the update again after
     * every install. Same SHA-256 (GitHub's asset digest) means the same file; without a digest an identical byte size
     * is used (a signed APK of another build practically never has the exact same size).
     */
    fun isInstalledBuild(rel: Release, installedSha256: String?, installedSize: Long): Boolean = when {
        rel.apkSha256 != null && installedSha256 != null -> rel.apkSha256.equals(installedSha256, ignoreCase = true)
        else -> rel.apkSize > 0 && rel.apkSize == installedSize
    }

    /** 1.0.7: a downloaded APK is only an update when its versionCode is higher than the installed one. */
    fun isUpgrade(downloadedCode: Long?, installedCode: Long): Boolean = downloadedCode == null || downloadedCode > installedCode

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses GitHub's "latest release" JSON. Returns null for drafts, pre-releases, or a release without an .apk asset
     * (the first asset whose name ends with .apk is used).
     */
    fun parseRelease(body: String): Release? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        if ((o["draft"] as? JsonPrimitive)?.booleanOrNull == true || (o["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val tag = o.str("tag_name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val version = tag.removePrefix("v").removePrefix("V")
        if (parseVersion(version) == null) return null
        val asset = (o["assets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .firstOrNull { it.str("name")?.endsWith(".apk", ignoreCase = true) == true } ?: return null
        val url = asset.str("browser_download_url")?.takeIf { it.startsWith("https://") } ?: return null
        return Release(
            tag = tag, version = version,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: tag,
            notes = cleanNotes(o.str("body").orEmpty()),
            apkUrl = url, apkName = asset.str("name")!!,
            apkSize = (asset["size"] as? JsonPrimitive)?.longOrNull ?: -1L,
            pageUrl = o.str("html_url"),
            apkSha256 = asset.str("digest")?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substringAfter(':')?.lowercase()?.takeIf { it.length == 64 },
        )
    }

    /** Release notes as plain text: markdown headings, emphasis, code ticks, links and images reduced to their text. */
    fun cleanNotes(md: String): String = md.replace("\r\n", "\n").lines().joinToString("\n") { line ->
        var l = line
        l = l.replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")          // images → alt text
        l = l.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")           // links → text
        l = l.replace(Regex("""^\s{0,3}#{1,6}\s*"""), "")                // headings
        l = l.replace(Regex("""^(\s*)[*+-]\s+"""), "$1• ")               // bullets
        l = l.replace(Regex("""(\*\*|__)(.+?)\1"""), "$2")               // bold
        l = l.replace(Regex("""(?<![\w*])[*_](?!\s)(.+?)(?<!\s)[*_](?![\w*])"""), "$1") // italics
        l = l.replace("`", "")
        l.trimEnd()
    }.replace(Regex("\n{3,}"), "\n\n").trim()
}
