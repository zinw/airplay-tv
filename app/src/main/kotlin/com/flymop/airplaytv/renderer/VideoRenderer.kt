package com.flymop.airplaytv.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.flymop.airplaytv.renderer.DecoderSelector.Companion.videoCaps
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mirror video path.
 *
 * Recovery / keyframe / flush semantics follow the pre-perf upstream (flymop) baseline
 * that painted a live picture: only gate on keyframe when (re)starting the codec, treat
 * SPS/VPS as keyframes, hard-restart on RAOP flush, and always render decoder output.
 *
 * Kept from later perf work: direct input buffer + async [MediaCodec.Callback] drain so
 * the RAOP thread is not blocked in dequeueOutputBuffer. Soft-flush, await-IDR-only,
 * mid-GOP desync gates, and suppress-render-until-IDR (PR #7/#8) are intentionally gone —
 * those caused mosaic then full black on Honor Smart Screen.
 *
 * Mosaic / stale tiles (v1.0.1–1.0.4): PR #3 zero-wait (and still-short) feed polls drop
 * mid-GOP NALs; continuing to feed P-frames paints HW decoder corruption as tiles. We use
 * a modest poll budget (still << upstream 20ms×10) and **hard-stop** the codec after any
 * feed drop so the next SPS|IDR restarts cleanly — picture may freeze briefly, never blank.
 *
 * Rename → reconnect black (v1.0.4): [AirPlayService.restartServer] used to call [release],
 * which quit the codec HandlerThread and dropped the GL display bind. Force-stop fixed it
 * because a new process rebuilt everything. Use [resetForServerRestart] instead.
 */
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
    private var loggedFirstFrame = false

    // Must stay alive across server rename/restart — release() quit the thread once and
    // left MediaCodec.Callback dead until process death (Honor rename→reconnect black).
    private var codecThread = HandlerThread("video-codec").also { it.start() }
    private var codecHandler = Handler(codecThread.looper)
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
        ensureCodecThreadAlive()
        // Clean slate for a new mirror — do not blank the Surface (no suppress-render).
        stopCodec()
        rebindDisplaySurfaceLocked()
        _resetStats()
        loggedFirstFrame = false
        Log.i(TAG, "VIDEO_MIRROR_START displayBound=${displaySurface?.isValid == true}")
    }

    fun stopSession() = synchronized(lock) { stopCodec() }

    /**
     * Server rename / settings restart: tear down codec + GL pipeline but keep this
     * renderer usable. [release] must only run on service destroy — it quit the codec
     * HandlerThread and left rename→reconnect mirror black until force-stop.
     */
    fun resetForServerRestart() = synchronized(lock) {
        stopCodec()
        val display = displaySurface
        pipeline.release()
        ensureCodecThreadAlive()
        freeInputs.clear()
        loggedFirstFrame = false
        _resetStats()
        if (display != null && display.isValid) {
            displaySurface = display
            pipeline.setDisplaySurface(display)
        } else {
            displaySurface = null
            pipeline.setDisplaySurface(null)
        }
        Log.i(TAG, "VIDEO_SERVER_RESET displayValid=${display?.isValid == true}")
    }

    /**
     * RAOP video_flush / discontinuity.
     *
     * Hard-restart like pre-soft-flush (PR #3 / upstream picture path). Soft flush +
     * await-IDR / suppress-render from PR #7/#8 produced mosaic then full black on Honor.
     */
    fun flushSession() = synchronized(lock) {
        Log.i(TAG, "VIDEO_FLUSH hard restart (await next keyframe to start codec)")
        stopCodec()
    }

    private fun ensureCodecThreadAlive() {
        if (codecThread.isAlive) return
        Log.w(TAG, "VIDEO_CODEC_THREAD recreate (was quit)")
        codecThread = HandlerThread("video-codec").also { it.start() }
        codecHandler = Handler(codecThread.looper)
    }

    private fun rebindDisplaySurfaceLocked() {
        val display = displaySurface
        if (display != null && display.isValid) {
            pipeline.setDisplaySurface(display)
        } else if (display != null) {
            displaySurface = null
            pipeline.setDisplaySurface(null)
        }
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
            "res=${videoWidth}x${videoHeight}"
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

            val isKeyframe = VideoNalUtils.isKeyframe(nativeInputBuffer, size, isH265)

            // Upstream rule: only wait for a keyframe when (re)starting the codec.
            // No continuous await-IDR / suppress-render (those blanked the Surface on Honor).
            if (codec == null || isH265 != currentH265) {
                if (!isKeyframe) {
                    if (codec != null) {
                        Log.i(TAG, "VIDEO_AWAIT_KEYFRAME mime mismatch; stopping until keyframe")
                        stopCodec()
                    }
                    return
                }
                Log.i(TAG, "VIDEO_KEYFRAME_START size=$size h265=$isH265")
                stopCodec()
            }

            try {
                if (codec == null) startCodec(isH265)
                val queued = _feedToCodec(size, ntpTimeNs, isKeyframe)
                if (!queued) {
                    // A gap mid-GOP leaves the HW decoder with broken refs → mosaic tiles.
                    // Hard-stop (same as flush); next SPS|IDR restarts. Always keep rendering
                    // whatever is already on the Surface — do not suppress to black.
                    Log.w(
                        TAG,
                        "VIDEO_DROP_RESTART drops=$droppedFrames keyframe=$isKeyframe " +
                            "(hard stop; await next keyframe)",
                    )
                    stopCodec()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    /** @return true if the AU was queued; false if dropped (caller must recover). */
    private fun _feedToCodec(size: Int, ntpTimeNs: Long, isKeyframe: Boolean): Boolean {
        val c = codec ?: return false
        // Patient vs PR #3's zero-wait (mosaic source), still << upstream 20ms×10 sync block.
        // Keyframes after restart get a slightly longer budget so recovery AUs are not lost.
        val waitMs = when {
            isKeyframe && !firstFrameQueued -> KEYFRAME_FEED_POLL_MS
            firstFrameQueued -> FEED_POLL_MS
            else -> FIRST_FEED_POLL_MS
        }
        val retries = when {
            isKeyframe && !firstFrameQueued -> KEYFRAME_FEED_RETRIES
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
            c.queueInputBuffer(idx, 0, size, ntpTimeNs / 1000, 0)
            firstFrameQueued = true
            return true
        }
        droppedFrames++
        Log.w(
            TAG,
            "Decoder input queue full; dropping frame. drops=$droppedFrames keyframe=$isKeyframe",
        )
        return false
    }

    private fun startCodec(h265: Boolean) {
        ensureCodecThreadAlive()
        rebindDisplaySurfaceLocked()
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
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
                // Always render — no suppress-until-IDR (v1.0.3 black-screen over-correct).
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }
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
        /**
         * Steady-state feed budget ≈ 60ms (5ms×12). Upstream used 20ms×10 sync (~200ms);
         * PR #3 used 0×12. 60ms cuts mid-GOP drops without pinning the RAOP thread that long.
         */
        private const val FEED_POLL_MS = 5L
        private const val FEED_RETRIES = 12
        private const val FIRST_FEED_POLL_MS = 5L
        private const val FIRST_FEED_RETRIES = 40
        /** First keyframe after (re)start — give async input callbacks time to refill. */
        private const val KEYFRAME_FEED_POLL_MS = 8L
        private const val KEYFRAME_FEED_RETRIES = 40
    }
}
