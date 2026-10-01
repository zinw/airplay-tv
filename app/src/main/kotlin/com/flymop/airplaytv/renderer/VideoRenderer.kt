package com.flymop.airplaytv.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.flymop.airplaytv.renderer.DecoderSelector.Companion.videoCaps
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VideoRenderer(ctx: Context) {

    private val lock = Object()
    private val ptsLock = Object()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false
    /**
     * After flush/start/drop-desync, discard non-IDR coded slices until the next IDR/CRA.
     * Parameter-set AUs (SPS/PPS/VPS) are still fed — dropping them causes black screens
     * when AirPlay sends CSD separately from the IDR.
     *
     * Do **not** suppress Surface renders while waiting: that over-corrected mosaic into
     * a fully black picture on Honor / OEM decoders (v1.0.3).
     */
    private var awaitingIdr = true
    /** Wall-clock when we entered awaitingIdr; used for picture-first timeout escape. */
    private var awaitingIdrSinceElapsedMs = 0L
    /** Consecutive mid-GOP queue failures before forcing await-IDR (avoid one-drop blackout). */
    private var consecutiveMidGopDrops = 0
    private var consecutiveCodecErrors = 0
    private var loggedFirstFrame = false
    /** Bumped on soft flush for QA logcat correlation. */
    private val codecEpoch = AtomicInteger(0)

    private val codecThread = HandlerThread("video-codec").also { it.start() }
    private val codecHandler = Handler(codecThread.looper)
    private val freeInputs = ArrayBlockingQueue<Int>(64)
    private val codecAlive = AtomicBoolean(false)

    /**
     * Direct scratch filled by native `_video_process` before [feedFrame] runs.
     * Sized for 4K I-frames without per-frame Java heap allocations.
     */
    val nativeInputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(NATIVE_INPUT_CAPACITY)

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265) selector.hevc(avcDecoder, w, h, fps) else null
        maxFps = fps
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> maxOf(w1, w2) to maxOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) {
        _resetStats()
        loggedFirstFrame = false
        // Pre-create AVC decoder so the first IDR does not pay create+configure on the RTP thread.
        if (codec == null && videoWidth > 0 && videoHeight > 0 && avcDecoder != null) {
            try {
                startCodec(h265 = false)
                _enterAwaitIdr("VIDEO_PREWARM AVC codec ahead of first IDR")
            } catch (e: Exception) {
                Log.w(TAG, "codec prewarm failed (will start on first IDR)", e)
                stopCodec()
            }
        } else {
            _enterAwaitIdr("VIDEO_MIRROR_START awaiting IDR")
        }
    }

    fun stopSession() = synchronized(lock) { stopCodec() }

    /**
     * RAOP video_flush / discontinuity: soft-flush codec state and wait for the next IDR.
     *
     * Async [MediaCodec.Callback] mode **requires** [MediaCodec.start] after [MediaCodec.flush]
     * or the codec stays Flushed and never requests new input — that manifests as mosaic /
     * stale tiles on devices like Honor Smart Screen.
     */
    fun flushSession() = synchronized(lock) {
        val c = codec
        if (c == null) {
            firstFrameQueued = false
            _enterAwaitIdr("VIDEO_FLUSH idle (awaiting IDR)")
            return@synchronized
        }
        try {
            freeInputs.clear()
            val epoch = codecEpoch.incrementAndGet()
            c.flush()
            // Async mode: must start() again or input callbacks never resume.
            c.start()
            freeInputs.clear()
            firstFrameQueued = false
            consecutiveCodecErrors = 0
            synchronized(ptsLock) {
                _ptsBaseUs = Long.MIN_VALUE
                _wallBaseNs = 0L
            }
            _frameIntervalIdx = 0
            _frameIntervalCount = 0
            _lastOutputFrameNs = 0L
            _enterAwaitIdr("VIDEO_FLUSH soft+start epoch=$epoch (awaiting IDR; render not suppressed)")
        } catch (e: Exception) {
            Log.w(TAG, "VIDEO_FLUSH soft failed; hard restart", e)
            stopCodec()
        }
    }

    private fun _enterAwaitIdr(reason: String) {
        awaitingIdr = true
        awaitingIdrSinceElapsedMs = SystemClock.elapsedRealtime()
        consecutiveMidGopDrops = 0
        Log.i(TAG, reason)
    }

    private fun _clearAwaitIdr(reason: String) {
        if (awaitingIdr) {
            Log.i(TAG, reason)
        }
        awaitingIdr = false
        awaitingIdrSinceElapsedMs = 0L
        consecutiveMidGopDrops = 0
    }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight} awaitIdr=$awaitingIdr"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    /**
     * Feed a frame already written into [nativeInputBuffer] (first [size] bytes).
     * Called synchronously from the RAOP mirror thread — must not block for long.
     * Output drain runs on [codecHandler] via [MediaCodec.Callback].
     */
    fun feedFrame(size: Int, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            if (size <= 0 || size > nativeInputBuffer.capacity()) return
            _updateStats(size)
            if (videoWidth == 0 || videoHeight == 0) return

            val isIdr = VideoNalUtils.containsIdr(nativeInputBuffer, size, isH265)
            val isParam = VideoNalUtils.containsParamSets(nativeInputBuffer, size, isH265)
            val recoverable = isIdr || isParam

            // Picture-first escape: if we waited too long for IDR, resume feeding so the
            // Surface is not stuck black (occasional mosaic preferred over no picture).
            if (awaitingIdr && !isIdr && awaitingIdrSinceElapsedMs > 0) {
                val waited = SystemClock.elapsedRealtime() - awaitingIdrSinceElapsedMs
                if (waited >= AWAIT_IDR_TIMEOUT_MS) {
                    _clearAwaitIdr("VIDEO_AWAIT_IDR_TIMEOUT after ${waited}ms — resume feed (picture first)")
                }
            }

            if (codec == null || isH265 != currentH265) {
                // Restart needs IDR or at least param sets to seed CSD before IDR.
                if (!recoverable) {
                    if (codec != null && isH265 != currentH265) {
                        Log.i(TAG, "VIDEO_AWAIT_IDR codec switch (non-recovery while mime mismatch)")
                    }
                    return
                }
                if (codec != null) stopCodec()
            } else if (awaitingIdr && !recoverable) {
                return
            }

            try {
                if (codec == null) startCodec(isH265)
                val queued = _feedToCodec(size, ntpTimeNs, isIdr)
                if (queued && isIdr) {
                    _clearAwaitIdr("VIDEO_IDR_RESUME size=$size h265=$isH265")
                    consecutiveCodecErrors = 0
                } else if (queued && isParam && awaitingIdr) {
                    Log.i(TAG, "VIDEO_FEED_PARAMSET size=$size h265=$isH265 (still awaiting IDR)")
                } else if (!queued && !isIdr && !awaitingIdr) {
                    consecutiveMidGopDrops++
                    // Require a few failures — a single drop under load should not blank the screen.
                    if (consecutiveMidGopDrops >= MID_GOP_DROP_THRESHOLD) {
                        _enterAwaitIdr(
                            "VIDEO_DROP_DESYNC mid-GOP drops=$consecutiveMidGopDrops → awaiting IDR",
                        )
                    }
                } else if (!queued && isIdr) {
                    Log.w(TAG, "VIDEO_DROP_IDR failed to queue recovery frame (drops=$droppedFrames)")
                } else if (queued) {
                    consecutiveMidGopDrops = 0
                }
                Unit
            } catch (e: Exception) {
                consecutiveCodecErrors++
                Log.w(TAG, "Codec error, resetting (n=$consecutiveCodecErrors)", e)
                stopCodec()
            }
        }
    }

    private fun _feedToCodec(size: Int, ntpTimeNs: Long, isIdr: Boolean): Boolean {
        val c = codec ?: return false
        // Prefer not to block the RAOP thread; give IDR a slightly longer poll so recovery
        // frames are not lost on slower Honor / OEM decoders.
        val waitMs = when {
            isIdr && !firstFrameQueued -> FIRST_FEED_POLL_MS
            isIdr -> IDR_FEED_POLL_MS
            firstFrameQueued -> FEED_POLL_MS
            else -> FIRST_FEED_POLL_MS
        }
        val retries = when {
            isIdr -> IDR_FEED_RETRIES
            firstFrameQueued -> FEED_RETRIES
            else -> FIRST_FEED_RETRIES
        }
        repeat(retries) {
            val idx = freeInputs.poll(waitMs, TimeUnit.MILLISECONDS) ?: return@repeat
            val buf = try {
                c.getInputBuffer(idx)
            } catch (e: Exception) {
                Log.w(TAG, "getInputBuffer($idx) failed", e)
                return false
            } ?: return false
            buf.clear()
            if (buf.remaining() < size) {
                Log.w(TAG, "Codec input buffer too small (${buf.remaining()} < $size); dropping")
                c.queueInputBuffer(idx, 0, 0, 0, 0)
                droppedFrames++
                return false
            }
            nativeInputBuffer.position(0)
            nativeInputBuffer.limit(size)
            buf.put(nativeInputBuffer)
            nativeInputBuffer.clear()
            val flags = if (isIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(idx, 0, size, ntpTimeNs / 1000, flags)
            firstFrameQueued = true
            return true
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames idr=$isIdr")
        return false
    }

    private fun startCodec(h265: Boolean) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        awaitingIdr = true
        awaitingIdrSinceElapsedMs = SystemClock.elapsedRealtime()
        consecutiveMidGopDrops = 0
        freeInputs.clear()
        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName) async-callback")
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.setCallback(codecCallback, codecHandler)
            c.configure(format, surface, null, 0)
            codecAlive.set(true)
            c.start()
        } catch (e: Exception) {
            codecAlive.set(false)
            try { c.setCallback(null) } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!codecAlive.get()) return
            if (!freeInputs.offer(index)) {
                // Queue full — release empty buffer so the codec does not stall.
                try { codec.queueInputBuffer(index, 0, 0, 0, 0) } catch (_: Exception) {}
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            if (!codecAlive.get()) {
                try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                return
            }
            try {
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (isConfig) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }

                // Always render decoded frames. v1.0.3 suppressed until post-IDR output and
                // produced a fully black mirror when IDR/CSD gating never completed.
                if (!loggedFirstFrame) {
                    loggedFirstFrame = true
                    Log.i(TAG, "VIDEO_FIRST_FRAME ptsUs=${info.presentationTimeUs}")
                }

                _recordOutputFrameTime()
                if (scheduledOutputBufferRelease) {
                    val ptsUs = info.presentationTimeUs
                    val renderNs: Long
                    synchronized(ptsLock) {
                        if (_ptsBaseUs == Long.MIN_VALUE) {
                            _ptsBaseUs = ptsUs
                            _wallBaseNs = System.nanoTime()
                        }
                        renderNs = _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L
                    }
                    codec.releaseOutputBuffer(index, renderNs)
                } else {
                    codec.releaseOutputBuffer(index, true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "releaseOutputBuffer failed", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "MediaCodec async error: ${e.diagnosticInfo}", e)
            consecutiveCodecErrors++
            codecHandler.post {
                synchronized(lock) { stopCodec() }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "output format: $format")
        }
    }

    private fun stopCodec() {
        codecAlive.set(false)
        freeInputs.clear()
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        synchronized(ptsLock) {
            _ptsBaseUs = Long.MIN_VALUE
            _wallBaseNs = 0L
        }
        firstFrameQueued = false
        awaitingIdr = true
        awaitingIdrSinceElapsedMs = SystemClock.elapsedRealtime()
        consecutiveMidGopDrops = 0
        codec?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.setCallback(null) } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        codec = null
        freeInputs.clear()
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        codecThread.quitSafely()
        _resetStats()
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        /** 4MB covers large 4K IDR bursts without reallocating. */
        private const val NATIVE_INPUT_CAPACITY = 4 * 1024 * 1024
        /** Non-blocking poll of callback-fed input slots. */
        private const val FEED_POLL_MS = 0L
        private const val FEED_RETRIES = 12
        /** First keyframe may need a brief wait while the decoder warms up. */
        private const val FIRST_FEED_POLL_MS = 2L
        private const val FIRST_FEED_RETRIES = 50
        /** IDR recovery on slow OEM decoders — slightly more patient than mid-GOP. */
        private const val IDR_FEED_POLL_MS = 1L
        private const val IDR_FEED_RETRIES = 40
        /** Prefer picture over indefinite black if IDR never arrives. */
        private const val AWAIT_IDR_TIMEOUT_MS = 1_200L
        /** Single queue-full under load must not blank the mirror. */
        private const val MID_GOP_DROP_THRESHOLD = 3
    }
}
