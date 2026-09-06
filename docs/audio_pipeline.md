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

## 3. Volume & DACP Control

* **AirPlay Decibel Curve**: AirPlay sends volume in decibels from `-144.0 dB` (mute) to `0.0 dB` (max).
* **Linear Android Conversion**:
  $$\text{Volume Frac} = \frac{\text{dB} + 30.0}{30.0} \quad (\text{clamped between } 0.0 \text{ and } 1.0)$$
* **Bi-directional DACP Sync**: Changing volume using the TV remote or Android TV volume keys sends DACP updates back to the iOS device, keeping the volume sliders synchronized.
