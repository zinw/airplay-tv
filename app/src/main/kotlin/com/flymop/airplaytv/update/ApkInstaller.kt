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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads a release APK into app cache and launches the system package installer.
 * Requires [android.permission.REQUEST_INSTALL_PACKAGES] and a FileProvider entry.
 */
object ApkInstaller {
    private const val TAG = "ApkInstaller"
    private const val CACHE_DIR = "updates"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

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

    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_DIR).apply {
            if (!exists()) mkdirs()
        }
        // Clear previous downloads to avoid filling TV cache.
        dir.listFiles()?.forEach { it.delete() }

        val safeName = fileName.substringAfterLast('/').ifBlank { "update.apk" }
        val outFile = File(dir, safeName)
        val tmpFile = File(dir, "$safeName.part")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "AirPlayTV-UpdateCheck")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("APK download failed: HTTP $code")
            }
            val total = conn.contentLengthLong.coerceAtLeast(-1L)
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
                        onProgress?.invoke(downloaded, total)
                    }
                    output.flush()
                }
            }
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }
            Log.i(TAG, "Downloaded APK: ${outFile.absolutePath} (${outFile.length()} bytes)")
            outFile
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    fun install(activity: Activity, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            throw IllegalStateException("APK file missing or empty")
        }
        val uri = FileProvider.getUriForFile(activity, authority(activity), apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
