package com.flymop.airplaytv.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Downloads a release APK into app cache and launches the system package installer.
 *
 * China / flaky-GitHub path:
 * 1. Try the primary `github.com/.../releases/download/...` URL first
 * 2. Fall back to public prefix mirrors (documented in [MIRROR_PREFIXES])
 * 3. Retry each candidate with exponential backoff
 * 4. Reject empty / truncated files before handing off to the installer
 *
 * Requires [android.permission.REQUEST_INSTALL_PACKAGES] and a FileProvider entry.
 */
object ApkInstaller {
    private const val TAG = "ApkInstaller"
    private const val CACHE_DIR = "updates"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_ATTEMPTS_PER_URL = 2
    private const val MIN_VALID_APK_BYTES = 64 * 1024L
    private const val APK_ZIP_LOCAL_HEADER = 0x04034b50 // "PK\u0003\u0004"

    /**
     * Working public GitHub release mirrors (verified 2026-10).
     * Usage: `prefix + originalGithubUrl`, e.g.
     * `https://ghproxy.net/https://github.com/owner/repo/releases/download/v1/app.apk`
     *
     * - `ghproxy.net` — hunshcn/gh-proxy public instance (release assets OK)
     * - `ghfast.top` — alternate prefix proxy that returns the asset body directly
     *
     * `mirror.ghproxy.com` was probed and did not respond from this environment; omitted.
     */
    val MIRROR_PREFIXES: List<String> = listOf(
        "https://ghproxy.net/",
        "https://ghfast.top/",
    )

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createUnknownSourcesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    /** Build ordered download candidates: primary GitHub URL, then configured mirrors. */
    fun candidateUrls(primaryUrl: String): List<String> {
        val primary = primaryUrl.trim()
        if (primary.isEmpty()) return emptyList()
        if (!primary.contains("github.com/", ignoreCase = true)) {
            return listOf(primary)
        }
        val mirrored = MIRROR_PREFIXES.map { prefix ->
            if (primary.startsWith(prefix)) primary else prefix + primary
        }
        return (listOf(primary) + mirrored).distinct()
    }

    fun sourceLabel(url: String): String = when {
        url.startsWith("https://ghproxy.net/", ignoreCase = true) -> "ghproxy.net"
        url.startsWith("https://ghfast.top/", ignoreCase = true) -> "ghfast.top"
        url.contains("github.com", ignoreCase = true) -> "GitHub"
        else -> "mirror"
    }

    data class Progress(
        val downloaded: Long,
        val total: Long,
        val sourceLabel: String,
        val attempt: Int,
    )

    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        expectedSizeBytes: Long? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val candidates = candidateUrls(url)
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("Empty download URL")
        }

        val dir = File(context.cacheDir, CACHE_DIR).apply {
            if (!exists()) mkdirs()
        }
        dir.listFiles()?.forEach { it.delete() }

        val safeName = fileName.substringAfterLast('/').ifBlank { "update.apk" }
        val outFile = File(dir, safeName)
        val errors = mutableListOf<String>()

        for ((index, candidate) in candidates.withIndex()) {
            val label = sourceLabel(candidate)
            for (attempt in 1..MAX_ATTEMPTS_PER_URL) {
                coroutineContext.ensureActive()
                try {
                    if (index > 0 || attempt > 1) {
                        val backoffMs = min(8_000L, 700L * (1L shl ((index * MAX_ATTEMPTS_PER_URL) + attempt - 1)))
                        delay(backoffMs)
                    }
                    Log.i(TAG, "Downloading APK via $label (attempt $attempt): $candidate")
                    val file = downloadOnce(
                        url = candidate,
                        outFile = outFile,
                        expectedSizeBytes = expectedSizeBytes,
                        sourceLabel = label,
                        attempt = attempt,
                        onProgress = onProgress,
                    )
                    Log.i(TAG, "Downloaded APK via $label: ${file.absolutePath} (${file.length()} bytes)")
                    return@withContext file
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    val msg = "${label}#${attempt}: ${e.message ?: e.javaClass.simpleName}"
                    Log.w(TAG, "APK download failed ($msg)", e)
                    errors += msg
                    outFile.delete()
                    File(dir, "$safeName.part").delete()
                }
            }
        }

        throw IOException("All download sources failed: ${errors.joinToString(" | ")}")
    }

    private suspend fun downloadOnce(
        url: String,
        outFile: File,
        expectedSizeBytes: Long?,
        sourceLabel: String,
        attempt: Int,
        onProgress: ((Progress) -> Unit)?,
    ): File {
        val tmpFile = File(outFile.parentFile, "${outFile.name}.part")
        if (tmpFile.exists()) tmpFile.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "AirPlayTV-UpdateCheck")
            instanceFollowRedirects = true
        }
        try {
            val code = try {
                conn.responseCode
            } catch (e: SocketTimeoutException) {
                throw IOException("Timed out connecting to $sourceLabel", e)
            } catch (e: UnknownHostException) {
                throw IOException("DNS failed for $sourceLabel", e)
            }
            if (code !in 200..299) {
                throw IOException("HTTP $code from $sourceLabel")
            }

            val headerTotal = conn.contentLengthLong.coerceAtLeast(-1L)
            val expected = when {
                expectedSizeBytes != null && expectedSizeBytes > 0L -> expectedSizeBytes
                headerTotal > 0L -> headerTotal
                else -> -1L
            }

            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress?.invoke(Progress(downloaded, expected, sourceLabel, attempt))
                    }
                    output.flush()
                }
            }

            validateApkFile(tmpFile, expected)

            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }
            validateApkFile(outFile, expected)
            return outFile
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** Reject empty / truncated / non-APK payloads that would surface as 「应用未安装」. */
    internal fun validateApkFile(file: File, expectedSizeBytes: Long) {
        if (!file.exists()) {
            throw IOException("APK missing after download")
        }
        val size = file.length()
        if (size <= 0L) {
            throw IOException("Downloaded APK is empty")
        }
        if (size < MIN_VALID_APK_BYTES) {
            throw IOException("Downloaded APK too small ($size bytes)")
        }
        if (expectedSizeBytes > 0L && size != expectedSizeBytes) {
            // Allow tiny header-vs-CDN drift only when we lacked an authoritative size;
            // when GitHub asset size / Content-Length is known, require an exact match.
            throw IOException("Downloaded APK truncated ($size of $expectedSizeBytes bytes)")
        }
        // APK is a ZIP; require local-file header magic.
        file.inputStream().use { input ->
            val b0 = input.read()
            val b1 = input.read()
            val b2 = input.read()
            val b3 = input.read()
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
                throw IOException("Downloaded APK unreadable")
            }
            val magic = (b0) or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            if (magic != APK_ZIP_LOCAL_HEADER) {
                throw IOException("Downloaded file is not a valid APK (bad header)")
            }
        }
    }

    fun install(activity: Activity, apkFile: File) {
        validateApkFile(apkFile, expectedSizeBytes = -1L)
        val uri = FileProvider.getUriForFile(activity, authority(activity), apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
