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

## 2. Codec Selection & Keyframe Detection

* **Dynamic Codec Selection (`DecoderSelector.kt`)**: Probes Android's `MediaCodecList` for hardware decoder capabilities (`MediaCodecInfo.CodecCapabilities`). Automatically enables **HEVC/H.265** when supported, reducing stream bandwidth by ~40% while preserving sharpness.
* **Fast Keyframe Scanning (`VideoRenderer.kt`)**: Capped to inspect the first 4KB of NAL unit headers (`NAL_IDR_SLICE` / `NAL_VPS` / `NAL_SPS`), ensuring instant keyframe detection without scanning large payload bodies.

---

## 3. Dynamic Display & Resolution Matching

* **Auto Resolution Mode**: Reads the panel's physical resolution via `Display.Mode` and `WindowManager.maximumWindowMetrics` (prioritizing 4K/1080p native display modes) and reports matching dimensions to the AirPlay sender via binary plist negotiation (`width`, `height`, `refreshRate`).
* **Aspect Ratio Preservation (`AspectFrameLayout.kt`)**: Automatically adapts to sender aspect ratios (e.g. 19.5:9 on iPhone, 4:3 on iPad, 16:9 on Mac) without distortion or stretching.

---

## 4. Modern OS Compatibility: 16KB Page Alignment

Configured with `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` in CMake and Gradle to ensure full compatibility with modern Android 15+ kernels that enforce **16KB memory page alignment**.
