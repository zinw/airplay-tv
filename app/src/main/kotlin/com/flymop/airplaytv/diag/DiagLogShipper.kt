package com.flymop.airplaytv.diag

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Temporary next-episode audio diagnostic shipper (debug build).
 * Fire-and-forget HTTPS POST of AirPlayAudio lines only. Failures never affect playback.
 * Remove this class (and native wiring) once Honor root-cause is confirmed.
 */
object DiagLogShipper {
    // TEMPORARY debug ingest — do not document in public PR/release notes.
    private const val INGEST_URL = "https://tp.zinw.top/airplay-diag/ingest"
    private const val TOKEN = "EQSVQhiUkKHj-SpELm9r74Uh"

    private const val TAG = "DiagLogShipper"
    private const val MAX_BODY = 60 * 1024
    private const val CONNECT_MS = 1500
    private const val READ_MS = 2000

    private val started = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>(512)
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "airplay-diag-ship").apply { isDaemon = true }
    }

    /** Idempotent; safe to call from service onCreate. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        exec.execute {
            val batch = StringBuilder(8192)
            while (started.get() || queue.isNotEmpty()) {
                try {
                    val first = queue.poll(400, TimeUnit.MILLISECONDS)
                    if (first != null) {
                        appendLine(batch, first)
                        while (batch.length < MAX_BODY / 2) {
                            val next = queue.poll() ?: break
                            if (batch.length + next.length + 1 > MAX_BODY) {
                                queue.offer(next)
                                break
                            }
                            appendLine(batch, next)
                        }
                    }
                    if (batch.isNotEmpty()) {
                        postQuietly(batch.toString())
                        batch.setLength(0)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "ship loop: ${t.message}")
                    batch.setLength(0)
                }
            }
        }
    }

    /**
     * Immediate hello so we can tell "TV cannot reach ingest" vs "diag never armed".
     * Fire-and-forget; never throws to callers.
     */
    fun sendBeacon(versionName: String, versionCode: Int) {
        start()
        val line =
            "BEACON hello AirPlayTV version=$versionName versionCode=$versionCode from=kotlin"
        enqueueBatch(line)
        // Direct one-shot so the first POST is not delayed by batch coalesce.
        exec.execute {
            try {
                postQuietly(line)
            } catch (t: Throwable) {
                Log.w(TAG, "beacon failed: ${t.message}")
            }
        }
    }

    fun stop() {
        started.set(false)
        queue.clear()
    }

    /**
     * Called from native (any thread) via JNI. Must return quickly — only enqueue.
     */
    @JvmStatic
    fun enqueueBatch(text: String?) {
        if (text.isNullOrEmpty()) return
        start()
        // Drop on overflow rather than block audio/RTP threads.
        if (!queue.offer(text)) {
            Log.w(TAG, "diag ship queue full; dropping batch")
        }
    }

    private fun appendLine(batch: StringBuilder, line: String) {
        if (batch.isNotEmpty()) batch.append('\n')
        batch.append(line)
    }

    private fun postQuietly(body: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(INGEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setRequestProperty("X-Log-Token", TOKEN)
                setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            // Drain response; ignore status — never surface to UI/playback.
            try {
                conn.inputStream?.use { it.readBytes() }
            } catch (_: Exception) {
                try {
                    conn.errorStream?.use { it.readBytes() }
                } catch (_: Exception) {
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ingest failed: ${t.message}")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
