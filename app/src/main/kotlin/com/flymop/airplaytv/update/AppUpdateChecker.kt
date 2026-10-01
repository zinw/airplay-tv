package com.flymop.airplaytv.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases of zinw/airplay-tv for a newer APK than the installed build.
 *
 * Version comparison (first match wins):
 * 1. `versionCode: N` (or `versionCode=N`) in the release body — preferred
 * 2. SemVer build metadata in the tag, e.g. `v1.0.1+12` → versionCode 12
 * 3. Numeric-only tag, e.g. `v12` → versionCode 12
 * 4. Fallback: compare SemVer [versionName](tag without leading `v`) to PackageManager.versionName
 *
 * APK asset selection prefers the device ABI, then `universal`, then any `*.apk`.
 *
 * Publishing a self-test release: bump versionCode/versionName, tag `v{versionName}`
 * (optionally `v{versionName}+{versionCode}`), put `versionCode: N` in the body,
 * attach a clearly named APK (`app-debug.apk`, `app-universal-release.apk`, or ABI-specific).
 */
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    private const val OWNER = "zinw"
    private const val REPO = "airplay-tv"
    private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000

    data class AvailableUpdate(
        val tagName: String,
        val releaseName: String,
        val versionLabel: String,
        val remoteVersionCode: Long?,
        val remoteVersionName: String?,
        val apkDownloadUrl: String,
        val apkFileName: String,
        val apkSizeBytes: Long?,
        val releaseNotes: String?,
        val htmlUrl: String,
    )

    suspend fun check(context: Context): AvailableUpdate? = withContext(Dispatchers.IO) {
        val local = localVersion(context)
        val release = fetchLatestRelease() ?: return@withContext null
        if (release.optBoolean("draft", false)) return@withContext null

        val tagName = release.optString("tag_name").orEmpty()
        if (tagName.isBlank()) return@withContext null

        val body = release.optString("body").orEmpty()
        val releaseName = release.optString("name").orEmpty().ifBlank { tagName }
        val htmlUrl = release.optString("html_url").orEmpty()
        val remoteCode = parseRemoteVersionCode(tagName, body)
        val remoteName = parseRemoteVersionName(tagName)

        if (!isNewer(remoteCode, remoteName, local.code, local.name)) {
            Log.i(TAG, "No update: local=${local.name}(${local.code}) remote=$remoteName($remoteCode) tag=$tagName")
            return@withContext null
        }

        val assets = release.optJSONArray("assets") ?: JSONArray()
        val apk = pickApkAsset(assets) ?: run {
            Log.w(TAG, "Newer release $tagName has no .apk asset")
            return@withContext null
        }

        val apkUrl = apk.optString("browser_download_url").orEmpty()
        val apkName = apk.optString("name").orEmpty()
        if (apkUrl.isBlank() || apkName.isBlank()) return@withContext null
        val apkSize = apk.optLong("size", -1L).takeIf { it > 0L }

        val label = when {
            remoteName != null && remoteCode != null -> "$remoteName ($remoteCode)"
            remoteName != null -> remoteName
            remoteCode != null -> remoteCode.toString()
            else -> tagName
        }

        AvailableUpdate(
            tagName = tagName,
            releaseName = releaseName,
            versionLabel = label,
            remoteVersionCode = remoteCode,
            remoteVersionName = remoteName,
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
            apkSizeBytes = apkSize,
            releaseNotes = body.takeIf { it.isNotBlank() },
            htmlUrl = htmlUrl,
        )
    }

    private data class LocalVersion(val code: Long, val name: String)

    private fun localVersion(context: Context): LocalVersion {
        val pm = context.packageManager
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
        return LocalVersion(code, pkg.versionName ?: "0")
    }

    private fun fetchLatestRelease(): JSONObject? {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "AirPlayTV-UpdateCheck")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                Log.i(TAG, "No GitHub releases published yet")
                return null
            }
            if (code == 403 || code == 429) {
                Log.w(TAG, "GitHub rate-limited or forbidden: HTTP $code")
                return null
            }
            if (code !in 200..299) {
                Log.w(TAG, "GitHub releases API failed: HTTP $code")
                return null
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseRemoteVersionCode(tagName: String, body: String): Long? {
        val bodyMatch = Regex("""(?im)^\s*versionCode\s*[:=]\s*(\d+)\s*$""")
            .find(body)
        if (bodyMatch != null) {
            return bodyMatch.groupValues[1].toLongOrNull()
        }
        val buildMeta = Regex("""^v?[0-9]+(?:\.[0-9]+)*(?:\-[0-9A-Za-z.]+)?\+(\d+)$""")
            .find(tagName.trim())
        if (buildMeta != null) {
            return buildMeta.groupValues[1].toLongOrNull()
        }
        val numericTag = Regex("""^v?(\d+)$""").find(tagName.trim())
        return numericTag?.groupValues?.get(1)?.toLongOrNull()
    }

    internal fun parseRemoteVersionName(tagName: String): String? {
        val trimmed = tagName.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null
        // Pure numeric tags are treated as versionCode only.
        if (trimmed.all { it.isDigit() }) return null
        val name = trimmed.substringBefore("+").substringBefore(" ")
        if (!name.contains('.') && name.toLongOrNull() != null) return null
        return name.takeIf { it.isNotBlank() }
    }

    internal fun isNewer(
        remoteCode: Long?,
        remoteName: String?,
        localCode: Long,
        localName: String,
    ): Boolean {
        if (remoteCode != null) return remoteCode > localCode
        if (remoteName != null) return compareSemVer(remoteName, localName) > 0
        return false
    }

    internal fun compareSemVer(a: String, b: String): Int {
        val pa = semVerParts(a)
        val pb = semVerParts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun semVerParts(version: String): List<Int> {
        val core = version.trim().removePrefix("v").removePrefix("V")
            .substringBefore("+")
            .substringBefore("-")
        return core.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun pickApkAsset(assets: JSONArray): JSONObject? {
        val apks = buildList {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) add(a)
            }
        }
        if (apks.isEmpty()) return null

        val abis = Build.SUPPORTED_ABIS.filterNotNull()
        for (abi in abis) {
            apks.firstOrNull { it.optString("name").contains(abi, ignoreCase = true) }?.let { return it }
        }
        apks.firstOrNull { it.optString("name").contains("universal", ignoreCase = true) }?.let { return it }
        apks.firstOrNull {
            val n = it.optString("name").lowercase()
            n.contains("release") || n.contains("debug") || n.contains("airplay")
        }?.let { return it }
        return apks.first()
    }
}
