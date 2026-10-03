package com.flymop.airplaytv.diag

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Temporary diagnostic log shipper (debug build).
 * Fire-and-forget HTTPS POST. Failures never affect playback or block the UI.
 * Remove once Honor root-cause is confirmed.
 */
object DiagLogShipper {
    // TEMPORARY debug ingest — do not document in public PR/release notes.
    private const val INGEST_URL = "https://tp.zinw.top/airplay-diag/ingest"
    private const val TOKEN = "EQSVQhiUkKHj-SpELm9r74Uh"
    /** Only this host may use the diag-only TLS path (Honor TV CA store lacks LE YR). */
    private const val EXPECTED_HOST = "tp.zinw.top"

    private const val TAG = "DiagLogShipper"
    private const val MAX_BODY = 60 * 1024
    private const val CONNECT_MS = 5000
    private const val READ_MS = 5000

    /** Result of one POST — no URL/token; safe to show on TV UI. */
    data class Outcome(
        val ok: Boolean,
        /** HTTP status when a response was received; null on transport failure. */
        val httpStatus: Int?,
        /**
         * Short machine reason for failures: timeout / dns / connect / ssl / http / other.
         * Null when ok.
         */
        val errorKind: String?,
    )

    fun interface OutcomeListener {
        fun onShipOutcome(outcome: Outcome)
    }

    private val started = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>(512)
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "airplay-diag-ship").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<OutcomeListener>()
    @Volatile var lastOutcome: Outcome? = null
        private set

    /**
     * Diag-only TLS: encrypt the session, require hostname [EXPECTED_HOST], but skip system
     * CA path validation (Honor TV trust store rejects the current LE YR intermediate).
     * Applied only to this ship's HttpsURLConnection — never set as JVM/app default.
     */
    private val diagTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) {
                throw SSLException("empty server cert chain")
            }
            // Present leaf must exist; CA path intentionally not checked against system store.
            chain[0].checkValidity()
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val diagHostnameVerifier = HostnameVerifier { hostname, _ ->
        hostname.equals(EXPECTED_HOST, ignoreCase = true)
    }

    private val diagSslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(diagTrustManager), SecureRandom())
        }.socketFactory
    }

    fun addOutcomeListener(listener: OutcomeListener) {
        listeners.addIfAbsent(listener)
        lastOutcome?.let { listener.onShipOutcome(it) }
    }

    fun removeOutcomeListener(listener: OutcomeListener) {
        listeners.remove(listener)
    }

    /** Idempotent background ship loop. */
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
                    Log.w(TAG, "ship loop: ${t.javaClass.simpleName}")
                    batch.setLength(0)
                }
            }
        }
    }

    /**
     * Immediate hello. Fire-and-forget; outcome is reported via [OutcomeListener].
     * @param from short label e.g. activity / service / native (no secrets).
     */
    fun sendBeacon(versionName: String, versionCode: Int, from: String = "kotlin") {
        start()
        val line =
            "BEACON hello AirPlayTV version=$versionName versionCode=$versionCode from=$from"
        // Direct one-shot so UI gets a timely outcome (not delayed by batch coalesce).
        exec.execute {
            try {
                postQuietly(line)
            } catch (t: Throwable) {
                publishOutcome(Outcome(false, null, classifyError(t)))
            }
        }
    }

    fun stop() {
        started.set(false)
        queue.clear()
    }

    /** Native/any-thread enqueue. Must return quickly. */
    @JvmStatic
    fun enqueueBatch(text: String?) {
        if (text.isNullOrEmpty()) return
        start()
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
            val url = URL(INGEST_URL)
            if (!url.host.equals(EXPECTED_HOST, ignoreCase = true)) {
                publishOutcome(Outcome(false, null, "ssl"))
                Log.w(TAG, "ingest host mismatch")
                return
            }
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setRequestProperty("X-Log-Token", TOKEN)
                setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
                if (this is HttpsURLConnection) {
                    // Per-connection only — do not touch default SSL factories.
                    sslSocketFactory = diagSslSocketFactory
                    hostnameVerifier = diagHostnameVerifier
                }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = try {
                conn.responseCode
            } catch (t: Throwable) {
                publishOutcome(Outcome(false, null, classifyError(t)))
                return
            }
            try {
                conn.inputStream?.use { it.readBytes() }
            } catch (_: Exception) {
                try {
                    conn.errorStream?.use { it.readBytes() }
                } catch (_: Exception) {
                }
            }
            val ok = code in 200..299
            publishOutcome(
                Outcome(
                    ok = ok,
                    httpStatus = code,
                    errorKind = if (ok) null else "http",
                ),
            )
            if (!ok) {
                Log.w(TAG, "ingest HTTP $code")
            }
        } catch (t: Throwable) {
            val kind = classifyError(t)
            Log.w(TAG, "ingest failed: $kind")
            publishOutcome(Outcome(false, null, kind))
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun classifyError(t: Throwable): String {
        var cur: Throwable? = t
        while (cur != null) {
            when (cur) {
                is SocketTimeoutException -> return "timeout"
                is UnknownHostException -> return "dns"
                is ConnectException -> return "connect"
                is SSLException -> return "ssl"
            }
            val msg = cur.message?.lowercase().orEmpty()
            if (msg.contains("timeout") || msg.contains("timed out")) return "timeout"
            if (msg.contains("unable to resolve") || msg.contains("unknown host")) return "dns"
            cur = cur.cause
        }
        return "other"
    }

    private fun publishOutcome(outcome: Outcome) {
        lastOutcome = outcome
        mainHandler.post {
            for (l in listeners) {
                try {
                    l.onShipOutcome(outcome)
                } catch (t: Throwable) {
                    Log.w(TAG, "outcome listener: ${t.javaClass.simpleName}")
                }
            }
        }
    }
}
