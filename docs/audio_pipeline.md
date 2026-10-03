# Low-Latency Audio Pipeline

**AirPlay TV** features a zero-JNI, native C++ audio rendering pipeline powered by **Google Oboe** and **FFmpeg**.

---

## 1. Native Audio Pipeline Flow

```
[Encrypted RTP Audio Packets] (ALAC / AAC-ELD / AAC-LC)
                    |
                    v
          [AES CBC Decryption] (crypto.c)
                    |
                    v
    [Native Audio Engine (audio_engine.cpp)]
        - Decodes ALAC via FFmpeg (libavcodec)
        - Decodes AAC via native AMediaCodec / FFmpeg
                    |
                    v (PCM 44.1kHz 16-bit Stereo)
        [TimelineBuffer (timeline_buffer.cpp)]
        - Native Ring Buffer + Adaptive Jitter Cushion
        - Target percentile delay matching (80th - 99th percentile)
        - Dynamic Trim / Fill for Drift Compensation
                    |
                    v
            [Google Oboe Engine]
        - AAudio Native Path (API >= 27) with MMAP support
        - OpenSL ES Fallback (API < 27) with burst auto-tuning
                    |
                    v (Ultra-Low Latency Output: ~15ms - 25ms)
           [TV Speakers / Soundbar]
```

---

## 2. Audio Engine Components

### A. Zero-JNI Decoding
Unlike traditional Android AirPlay receivers that convert every audio frame into a Java `byte[]` and call JNI callbacks into Kotlin's `AudioTrack`, **AirPlay TV** decodes and queues audio entirely inside native C++ memory:
* **ALAC (Apple Lossless)**: 44.1kHz 16-bit stereo decoded in real-time via `libavcodec`.
* **AAC-ELD / AAC-LC**: Low-delay AAC formats decoded natively with hardware acceleration.

### B. Adaptive Jitter Cushioning (`TimelineBuffer.cpp`)
AirPlay RTP packets transmit timestamps (NTP/RTP). Network jitter can cause packets to arrive unevenly:
* If the buffer underflows, digital silence is safely inserted rather than blocking the audio thread.
* The buffer dynamically tunes its cushion size (target ~40ms default) based on network arrival statistics, balancing minimal latency against dropout prevention.

### C. Google Oboe Real-Time Output
* Uses `aaudio` with exclusive MMAP mode when available on Android TV hardware.
* Bypasses the standard Android Java Audio Framework mixing overhead, reducing latency from ~250ms down to **~20ms**.

---

## 3. ALAC Silence Frames, Endianness & Clock Drift

### ALAC Silence Frames & Compression
* **44-Byte Frame Structure**: When an iOS sender is paused or audio is muted, it continuously transmits 44-byte RTP packets containing a 32-byte compressed ALAC silence payload.
* **Continuous Dequeuing**: Filtering or skipping 44-byte frames breaks packet sequence number continuity in `raop_buffer.c`, causing the dequeue thread to wait for missing packet retransmissions and freezing audio. AirPlay TV processes all 44-byte silence frames to keep the timeline perfectly synced.

### RAOP FLUSH / next-episode (v1.0.12)
Screen-mirror continuous next-episode sends RTSP `FLUSH` with `RTP-Info: seq=N` while video keeps playing. Upstream UxPlay only invokes the `audio_flush` callback and **does not** call `raop_buffer_flush`; the RTP ring keeps the old `first_seqnum`. Dequeue then stalls on the unfilled hole until the 256-slot buffer fills (~2s of ALAC) — hard mute with picture already rolling. Seek-to-0 recovers because a large seq jump hits the enqueue auto-flush, or TEARDOWN empties the buffer. Patch `0009-flush-raop-buffer-on-rtsp-flush.patch` resets the ring to `next_seq` on FLUSH before the light codec/ring playthrough path.

### FLUSH diagnostics (v1.0.13)
Tag **`AirPlayAudio`**: ~3s window after each RTSP FLUSH logs RTP-Info / `raop_buffer` before·after / dequeue NULL vs OK / enqueue auto-flush / first PCM write / first non-silent Oboe callback. Capture with `adb logcat -s AirPlayAudio:I` around next-episode.

### Endianness on ARM Architectures
Apple Lossless encodes header metadata in Big-Endian format. In `EndianPortable.c`, defining `TARGET_RT_LITTLE_ENDIAN` for ARM/ARM64 targets is critical:
* Without explicit endianness flags, `ALACSpecificConfig` fields (`maxFrameBytes`, `avgBitRate`) are byte-swapped incorrectly, resulting in multi-gigabyte memory allocation failures (`calloc(2147745792)`).

### Clock Drift Compensation & Timeline Synchronization
Because the sender's crystal clock (iPhone) and receiver's hardware DAC clock (Android TV) drift slightly over time:
1. **Timestamp Anchoring**: The server maps RTP packet timestamps to local monotonic audio time.
2. **Percentile Delay Tracking**: `TimelineBuffer.cpp` tracks packet arrival times using an 80th-to-99th percentile statistical window.
3. **Dynamic Frame Trimming & Silence Injection**: If the buffer runs ahead due to sender drift, sub-sample frames are smoothly trimmed; if it falls behind, micro-silence frames are injected. This prevents audible clicks or cumulative delay drift over hours of continuous streaming.

---

## 4. Volume & DACP Control

* **AirPlay Decibel Curve**: AirPlay sends volume in decibels from `-144.0 dB` (mute) to `0.0 dB` (max).
* **Linear Android Conversion**:
  $$\text{Volume Frac} = \frac{\text{dB} + 30.0}{30.0} \quad (\text{clamped between } 0.0 \text{ and } 1.0)$$
* **Bi-directional DACP Sync**: Changing volume using the TV remote or Android TV volume keys sends DACP updates back to the iOS device, keeping the volume sliders synchronized.
