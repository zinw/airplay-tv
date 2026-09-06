# Video Rendering Pipeline

The video pipeline in **AirPlay TV** is designed for ultra-low latency, decoupled rendering, and hardware-accelerated decoding of 1080p60 and 4K streams.

---

## 1. Decoupled Rendering Architecture

In standard Android media players, binding `MediaCodec` directly to a visible `SurfaceView` causes the codec to restart and drop frames whenever the Activity surface changes (e.g., when opening an overlay or switching focus).

**AirPlay TV** uses a **decoupled OpenGL ES pipeline**:

```
[Native RTP Video Stream (H.264 / H.265)]
                    |
                    v (ByteArray + PTS)
        [VideoRenderer.kt]
                    |
                    v (queueInputBuffer)
    [Android Hardware MediaCodec]
   (c2.amlogic.hevc.decoder / avc)
                    |
                    v (releaseOutputBuffer to Surface)
          [SurfaceTexture (OES)]
                    |
                    v (updateTexImage on GL Thread)
        [VideoPipeline.kt (EGL)]
  - Dedicated HandlerThread (`video_gl`)
  - Elevated Priority: THREAD_PRIORITY_URGENT_DISPLAY
  - VBO-backed full-screen Quad Shader
                    |
                    v (eglSwapBuffers)
        [Visible SurfaceView / TV Screen]
```

### Key Advantages:
1. **Decoder Stability**: `MediaCodec` always outputs to an off-screen `SurfaceTexture`. It never resets or stalls when the UI changes.
2. **Low Render Overhead**: VBOs (Vertex Buffer Objects) pre-allocate geometry on the GPU, eliminating CPU-GPU memory copy overhead during frame blits.
3. **No Redundant Clears**: `glClear(GL_COLOR_BUFFER_BIT)` is skipped during full-screen texture blitting to save GPU fill rate on low-power TV chipsets (like Mali-G31).

---

## 2. Codec Selection & TV SoC Profiles

### Dynamic Codec Selection (`DecoderSelector.kt`)
`DecoderSelector.kt` dynamically inspects the Android `MediaCodecList` at runtime to detect hardware-accelerated H.264 (AVC) and H.265 (HEVC) decoders:

* **Hardware vs. Software Codec Filtering**:
  - Filters out slow software decoders (`OMX.google.h264.decoder`, `c2.android.avc.decoder`) in favor of SoC vendor hardware blocks (`c2.amlogic.hevc.decoder`, `OMX.MTK.VIDEO.DECODER.HEVC`, `c2.qti.hevc.decoder`).
* **HEVC / H.265 Bandwidth Optimization**:
  - When H.265 decoding is available and enabled, it reduces network bandwidth consumption by ~40% for 4K streaming while maintaining superior image sharpness compared to H.264.

### SoC Compatibility Matrix
| SoC Vendor / Chipset | Hardware Decoder Name | Supported Max Resolution | Notes |
| :--- | :--- | :--- | :--- |
| **Amlogic (S905X3 / S905X4 / S905D3)** | `c2.amlogic.hevc.decoder` | 4K @ 60fps (HEVC / AVC) | Standard on Chromecast with Google TV & Xiaomi TV Boxes |
| **MediaTek (MT9611 / Pentonic)** | `OMX.MTK.VIDEO.DECODER.HEVC` | 4K @ 60fps / 120fps | Common in Sony Bravia & Philips Android TVs |
| **Realtek (RTD2871 / RTD298x)** | `c2.realtek.hevc.decoder` | 4K @ 60fps | Found in TCL and budget Android Smart TVs |
| **Qualcomm (Snapdragon / QCS)** | `c2.qti.hevc.decoder` | 4K @ 60fps | Found in premium commercial smart panels |

---

## 3. Fast Keyframe Detection & Header Parsing

* **Fast Keyframe Scanning (`VideoRenderer.kt`)**: Instead of scanning whole 100KB+ video frame payloads in Kotlin, the keyframe detector inspects only the first 4KB of NAL unit headers (`NAL_IDR_SLICE` = 5, `NAL_VPS` = 32, `NAL_SPS` = 33).
* **Instant Recovery**: When a client starts streaming or when minor network drops occur, the renderer buffers packets until the first keyframe arrives, preventing distorted visual artifacts.

---

## 4. Dynamic Display & Resolution Matching

* **Auto Resolution Mode**: Reads the panel's physical resolution via `Display.Mode` and `WindowManager.maximumWindowMetrics` (prioritizing 4K/1080p native display modes) and reports matching dimensions to the AirPlay sender via binary plist negotiation (`width`, `height`, `refreshRate`).
* **Aspect Ratio Preservation (`AspectFrameLayout.kt`)**: Automatically adapts to sender aspect ratios (e.g. 19.5:9 on iPhone, 4:3 on iPad, 16:9 on Mac) without distortion or stretching.

---

## 5. Modern OS Compatibility: 16KB Page Alignment

Configured with `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` in CMake and Gradle to ensure full compatibility with modern Android 15+ kernels that enforce **16KB memory page alignment**.
