# FPS Booster (optical-flow frame interpolation)

Status: **implemented, unit-tested and shader-validated on a desktop GPU emulator; NOT yet built with Gradle or run on a phone.**
Read [Known limitations](#6-known-limitations) and [What is and is not done](#7-what-is-and-is-not-done) before trusting any number below.

Base: upstream `main` at `7eb3190`. Media3 1.5.0, minSdk 26, compileSdk 36.

---

## 1. Architecture assessment (from the actual source)

| Area | Finding | Where |
|---|---|---|
| Build | Single `:app` module, AGP 9.3.0, Kotlin 2.4.10, Compose BOM 2024.09, Media3 **1.5.0** (`exoplayer`, `ui`, `effect`, `session`, `hls`, `dash`, Jellyfin FFmpeg audio), `minSdk 26`, `compileSdk 36` | `app/build.gradle.kts` |
| Player | `VideoPlayerViewModel.setupPlayer()` builds one `ExoPlayer` with `VideoRenderersFactory` (decoder-mode filter, FFmpeg audio renderer, `AudioDelayRenderer` that shifts the media clock) and a custom `DefaultLoadControl` | `viewmodel/VideoPlayerViewModel.kt`, `engine/VideoRenderersFactory.kt`, `engine/AudioDelayRenderer.kt` |
| Output surface | `PlayerView` created in `VideoPlayerScreen` (`AndroidView`): normal `SurfaceView`, a `TextureView` variant for colour grading, or `player_view_none.xml` (no surface) + a custom `SurfaceView` added into `exo_content_frame` *below* the subtitle layer | `ui/screens/VideoPlayerScreen.kt`, `res/layout/player_view_*.xml` |
| Existing booster | A real GPU block-matching interpolator already existed (`motionboost` package: 1000-line `MotionBoostRenderer`, `MotionBoostSurfaceView`, `MotionBoostTiming`). ExoPlayer renders into a `SurfaceTexture`; a private GL thread presents at vsync. It is **not** frame duplication. | `motionboost/` |
| Settings | `SharedPreferences("beatraxus")`, UI state in `VideoPlayerUiState` | `VideoPlayerViewModel.kt` |
| Effects | `ColorGradeEffect` (Media3 `GlEffect`) and a `TextureView` ColorMatrix path for grading; the booster does its own grading in its shader | `colorgrade/` |
| A/V sync | Audio clock drives ExoPlayer; user audio delay is a clock offset in `AudioDelayRenderer` | `engine/` |
| Stray files | `BaseGlShaderProgram.java`, `SingleFrameGlShaderProgram.java` sit in the repo root (copies of Media3 sources, Apache-2.0). Not compiled; left untouched. | repo root |

### Why the old booster was weak (measured, see §4)
On the same synthetic ground-truth clips the old engine is no better than plain frame-repeat for pans of 3, 28 and 56 px/frame and for a diagonal pan, and worse than a simple cross-fade on real footage. Reading its shaders, this is consistent with: a coarse search on ¼-size luma with 2-texel (~8 px) steps; the fine search only runs when the coarse stage found non-zero motion (so motion below ~4–8 px is never resolved); vectors beyond ~24–34 px are distrusted; one direction of flow only; no forward/backward consistency test. These are readings of the code that match the measurements, not separately proven causes.

### Media3 effect pipeline (verified against the 1.5.0 source; a research agent read the files)
- Effects route: `MediaCodecVideoRenderer` → `PlaybackVideoGraphWrapper` → `DefaultVideoFrameProcessor` → effect chain → `FinalShaderProgramWrapper`.
- Input side is strictly one registered frame per decoded frame. `BaseGlShaderProgram` is 1:1 and keeps the input PTS; its Javadoc says extra frames must have the *same* timestamp.
- The output side is a PTS-keyed queue, so extra frames at new timestamps are *not prohibited*, but nothing exercises that, the code carries `TODO b/257464707 - Support extensively modified media`, and release timing is derived from input PTS. That is unsupported territory.
- Built-in effects do **not** do temporal interpolation.
- **Decision:** keep the existing, proven design (ExoPlayer → `setVideoSurface(SurfaceTexture surface)` → own GL thread → own vsync presenter). No Media3 upgrade, no replacement of the stock renderer.
- Unverified: the `SurfaceTexture.getTimestamp()` documentation (developer.android.com was unreachable from the sandbox). The existing code already guards against a timestamp that is not on the monotonic clock; the new renderer keeps that guard.

---

## 2. Files

### Created
| File | Purpose |
|---|---|
| `fpsbooster/FpsBoosterSettings.kt` | Off/On/Auto, target (30/45/60/90/120), engine, quality, adaptive, battery saver, stats toggle; parsing + legacy migration |
| `fpsbooster/FpsBoosterStore.kt` | SharedPreferences persistence (+ one-time migration of the old setting) |
| `fpsbooster/FpsBoosterPlanner.kt` | Pure decision logic: settings + device + video → what actually runs, and why not |
| `fpsbooster/FramePacing.kt` | Pure timestamp/schedule maths (24→30, 24→60, seeks) |
| `fpsbooster/AdaptiveQualityController.kt` | Pure state machine: quality ladder, thermal, fallback to normal playback |
| `fpsbooster/BoosterStats.kt` | Stats model + per-second accumulator |
| `fpsbooster/OpticalFlowRenderer.kt` | GL engine (EGL, SurfaceTexture input, pyramid, flow, interpolation, vsync presenter) |
| `fpsbooster/FpsBoosterSurfaceView.kt` | `SurfaceView` host (surface create/destroy safe, display-mode hints) |
| `fpsbooster/FpsBoosterManager.kt` | Per-session glue: controller, thermal listener, stats, fallback |
| `fpsbooster/GlCapabilityProbe.kt` | One-off 1×1 pbuffer probe: ES3 + float render targets; panel rates |
| `assets/fpsbooster/*.frag` (6) | `luma_work`, `pyramid_down`, `flow_search`, `flow_consistency`, `cut_detect`, `flow_interp` |
| `ui/screens/FpsBoosterScreen.kt` | Settings sheet + live statistics |
| `test/.../fpsbooster/*Test.kt` (6 files, 51 tests) | Unit tests |
| `tools/fpsbooster_harness/` | Headless GLES harness: runs the shipped GLSL on ground-truth scenes |
| `docs/FPS_BOOSTER.md` | This file |

### Modified
| File | Change |
|---|---|
| `viewmodel/VideoPlayerViewModel.kt` | Replaced the four `motionBoost*` state fields/functions with `fpsBooster`, `fpsBoosterPlan`, `fpsBoosterStats`, `fpsBoosterSuspended`, `sourceFps`; device probe; plan recomputation on video / size / track changes |
| `ui/screens/VideoPlayerScreen.kt` | Picks the surface by plan/engine (optical flow → `FpsBoosterSurfaceView`; classic → existing `MotionBoostSurfaceView`); tool-button state; sheet content. The old `MotionBoostSheetContent` was removed (replaced by `FpsBoosterSheetContent`). |

**Untouched:** the `motionboost/` package (the Classic engine), the audio pipeline, `VideoRenderersFactory`, layouts, Gradle files.

## 3. Gradle dependency changes

**None.** Everything uses the Android framework (EGL14, GLES30, SurfaceTexture, PowerManager) and the dependencies already present.

---

## 4. Implementation details

### Pipeline (per decoded frame pair, on one dedicated GL thread)
1. ExoPlayer renders into the renderer's `SurfaceTexture`. Each frame is copied into one of **3 fixed slots** (bounded queue; a slow GPU drops source frames, it never queues).
2. Luma at the *work* size (640 / 960 / 1280 px longest side by quality), then a 2×2-box pyramid (≤5 levels, coarsest ≤128 px).
3. **Hierarchical block search, both directions** (A→B and B→A): full ±R search at the coarsest level, then per level: candidates from the coarser level + neighbours, two integer refinement rings, and (Balanced/Quality, finest level) ½ and ¼ pixel refinement; one propagation pass per level. 4 px block pitch with overlapping 8×8 windows; cost regularised toward the coarse prediction.
4. **Forward–backward consistency** at the finest level marks occluded / unreliable blocks.
5. **Scene-cut detector** on the coarsest field (mean cost + share of poor matches), evaluated entirely on the GPU (no read-back).
6. **Interpolation** at each due vsync: two independent warps (one from each field), each trusted only where its field is self-consistent *and* the two warped samples agree; warps are blended by trust; where neither is trusted the **nearest real frame** is shown (judder instead of ghosting); on a cut the nearest real frame everywhere.
7. Presentation: Choreographer vsync, frame due-time scheduling (`MotionBoostTiming.isDue/nextDue`, reused), alpha from the SurfaceTexture timestamps, a hold-back so the next source frame has arrived.

### Timestamps / sync
- Output phase `alpha` is computed from the source frames' SurfaceTexture timestamps, so **playback-speed changes** and the audio-driven release schedule are followed automatically; sources that already run at ≥95 % of the target are bypassed.
- A **seek, pause, > 1 s gap or new clip** breaks the pair (`MotionBoostTiming.isContinuous`); the stale flow is discarded; nothing is interpolated across it.
- The presenter holds video back by `hold` ms (≈ source interval − decoder lead + one vsync + 4 ms). From the formula this is **expected to be roughly 25–35 ms at 24 fps; not measured on a device.** It is shown in the stats as *Video delay*. The audio pipeline was deliberately **not** modified to compensate; the existing *Audio delay* control can absorb it, and 25–35 ms is below the usual A/V detectability threshold.

### Settings and stats
- Mode Off/On/Auto; target 30/45/60/90/120 (a target is only offered if some panel mode is an integer multiple of it, otherwise it is greyed out; the planner steps down to the next usable one and says so); Engine Auto/Optical flow/Classic; Quality Performance/Balanced/Quality; Adaptive resolution; Battery saver; Live statistics.
- **Default quality is Performance** (640 px motion search), per the brief to start light and raise quality only after benchmarking.
- **Not offered, because not implemented:** an AI engine and a "motion strength" slider (the algorithm has no such parameter).
- Statistics shown: source FPS, target, **generated** frames/s, **rendered** FPS (frames handed to the display), dropped frames, processing latency (ms per frame pair), processing size, video delay, and — separately labelled — the **display refresh rate** (not a generated rate).
- *Processing latency* is measured by `glFinish()` around the motion estimate on **every 4th pair** (stalls only the booster's own GL thread). It is wall-clock GPU+CPU time, not a GPU-timer query.
- A 24→60 conversion shows a decoded frame only every 5th output frame, so **48 of 60 output frames are synthesised** (80 %); 24→30 and 24→48 are 80 % and 50 %. (`FramePacing.generatedShare`.)

### Performance and fallback
- `AdaptiveQualityController` ladder: Quality 1280 → Balanced 960 → Performance 640 → Minimal 480. Starts at the user's preset, never goes above it; battery saver caps at Performance. Overloaded window (estimate > 70 % of the source interval, or > 5 % dropped, or > 15 % missed output) → one step down (cooldown 2 s). Overloaded for 4 windows at the lowest rung (or at the user's rung when adaptive is off) → **fallback to standard playback**. 10 calm windows → one step back up; a failed upgrade blocks upgrades for 30 s. Moderate thermal status forces one step down; severe → immediate fallback.
- Pause/seek/stall windows (< 3 source frames) are ignored by the controller. So are the first 3 windows of a session and the first 2 after a quality step or a target-FPS change (resource reallocation and re-pacing look like overload for a moment; judging them caused spurious "could not keep up" messages right after changing a setting).
- GPU failure of any kind (no ES3, no float render targets, shader/link error, incomplete framebuffer, swap failure) → one error callback → the screen drops back to normal playback and shows why. The booster stays off for that video; a new video gets a fresh attempt.
- Surface destruction: ExoPlayer is detached first, the renderer is released and awaited (500 ms), a new renderer is built on the next `surfaceCreated`.
- HDR sources are excluded by the planner (the pipeline is 8-bit SDR with no tone mapping). **No HDR support is claimed.**

### Original resolution
The frame that is stored and shown is always at the video's original resolution (safety cap 3840 px on the long side); the quality presets (and adaptive resolution) only change the size of the *motion-analysis* image (640 / 960 / 1280 px, Minimal 480). Only the adaptive "Minimal" rung, reachable only with Adaptive resolution on, caps the frame at 1280 px.

### Memory (arithmetic, not measured)
Three RGBA8 frame slots plus an output target: 1080p ≈ 8.3 MB per frame (~33 MB for four); 4K (3840×2160) ≈ 33 MB per frame (~130 MB). Luma pyramids, flow fields (RGBA16F at ¼ resolution) and the cut texture add a few MB.

### GPU cost (op-count estimate, NOT a measurement)
Counting texture fetches in `flow_search.frag` per fragment and the grid sizes: **≈ 42 M fetches per frame pair at Performance** (≈ 1.0 G/s at 24 pairs/s) and **≈ 160 M per pair at Balanced** (≈ 3.8 G/s). Whether a Mali-G68 sustains these is unknown, which is why Performance is the default and the adaptive controller exists. Likely first optimisations if needed: the `float a[64]` window array is dynamically indexed in the stride-2 path and may spill on mobile GPUs; unrolling to two specialised loops is the first thing to try.

---

## 5. Measured results (desktop harness, Mesa llvmpipe)

These measure **image quality and shader correctness only**. llvmpipe is a CPU rasteriser; none of this says anything about speed on a phone.

Scenes are rendered from a continuous-time model so the true in-between frame is exact. 1280×720, mean PSNR (dB) over the 24→60 / 24→30 phases (0.2/0.4/0.6/0.8). Real footage: OpenCV's `vtest.avi` (pedestrians, mostly static camera), frame *k* rebuilt from *k−1* and *k+1*, 40 triples.

| Scene | Repeat | Cross-fade | Old engine | **New (Balanced)** |
|---|---|---|---|---|
| pan 3 px/frame | 26.5 | 28.8 | 26.5 | **43.6** |
| pan 10 px/frame | 18.7 | 19.9 | 22.7 | **42.0** |
| pan 28 px/frame | 14.6 | 15.8 | 14.7 | **30.0** |
| pan 56 px/frame | 12.6 | 13.6 | 12.6 | **26.6** |
| pan 20,12 px/frame | 13.9 | 15.1 | 13.9 | **27.4** |
| zoom + rotate | 25.2 | 26.4 | 26.7 | **40.5** |
| disc over panning background (occlusion) | 21.9 | 23.5 | 22.4 | **33.5** |
| hard scene cut (clean = no mix of two shots) | 99 | 17.3 | 99 | **99** |
| real footage (vtest), SSIM | 0.972 | 0.975 | 0.974 | **0.979** |
| real footage (vtest), PSNR | 26.9 | **29.5** | 27.1 | 29.1 |

On the real clip the new engine **ties** cross-fading on PSNR (cross-fading averages noise, which flatters PSNR) and is slightly ahead on SSIM. That clip is nearly static, so it says little about film content.

Flow accuracy on Middlebury *RubberWhale* (real image, ground-truth flow, mean end-point error, px): zero flow 1.24; **new Performance 0.54, Balanced/Quality 0.43**; OpenCV Farneback (CPU reference) 0.37.

Reproduce: §8.

---

## 6. Known limitations

- **Never built with Gradle, never run on a device.** The Maven hosts were blocked in the build sandbox. What was checked instead: the whole `fpsbooster` package **type-checks against the real Android API 36 `android.jar`** (with two tiny stand-ins for `Player`/`VideoSize`); the edited ViewModel/Screen/Sheet files are **syntax-checked only** (Compose/Media3 jars unavailable); 51 unit tests pass under a local JUnit stand-in. Expect to fix small compile errors on first Gradle build.
- The GLSL runs on llvmpipe (ES 3.2). Mali-G68 compiler behaviour, precision and performance are unverified. The renderer requires `EXT_color_buffer_float` or `EXT_color_buffer_half_float` and verifies an RGBA16F framebuffer at runtime; if either fails it falls back.
- Test scenes are synthetic (pure translation/zoom/rotation, one occlusion disc) or low-motion surveillance footage. **No film material, no compression noise/grain, no subtitles-over-video, no HDR** was evaluated.
- Thin fast-moving edges (the occlusion disc rim) still show errors; very fast, complex or non-rigid motion falls back to the nearest frame (judder).
- Subtitles are drawn by `PlayerView` above the booster surface exactly as with the Classic engine; letterboxing/aspect use the same frame layout. Not tested on a device.
- The plan can flip from active to inactive shortly after a video starts (when the stream's frame rate becomes known), which recreates the video surface once.
- The Classic engine's "Extreme" option is now "Performance quality"; the old 45/60/90/120 buttons are the Target FPS chips (30 added). Classic at 30 FPS is untested.
- Battery, thermal behaviour, CPU/GPU load, memory use were **not measured**.
- No instrumentation tests exist. Benchmarks must be run on the device (§9).

## 7. What is and is not done

| Item | Status |
|---|---|
| Hierarchical bidirectional optical flow, occlusion handling, cut detection, interpolation shaders | **Implemented**; validated on llvmpipe against ground truth |
| Settings model, persistence, legacy migration, planner, frame-pacing maths, adaptive controller, stats accumulator | **Implemented and unit-tested (51 tests pass locally)** |
| `OpticalFlowRenderer`, `FpsBoosterSurfaceView`, `FpsBoosterManager`, `GlCapabilityProbe` | **Implemented; type-checked against Android API 36; not run** |
| ViewModel / Screen / settings sheet integration | **Implemented; syntax-checked only; not compiled or run** |
| Bounded frame queue, GPU resource reuse, seek/shutdown cancellation, surface recreation, GPU-failure fallback, thermal/perf-aware quality | **Implemented (logic unit-tested; GL paths not run)** |
| Live statistics | **Implemented** (latency = sampled `glFinish`, not GPU timers) |
| AI / RIFE engine | **Not implemented** (assessment below) |
| HDR support | **Not implemented** (excluded by design) |
| Motion-strength control | **Not implemented** (no such algorithm parameter) |
| Automatic A/V compensation for the hold-back | **Not implemented** (deliberate: audio pipeline untouched) |
| Instrumentation / on-device benchmark tests; battery, thermal, memory, CPU/GPU measurements | **Not done** |
| Gradle build and `testDebugUnitTest` run | **Not done** |

## 8. Build and test commands

```bash
# App (needs local.properties: LASTFM_API_KEY, LASTFM_SECRET, RELEASE_STORE_FILE ... as app/build.gradle.kts requires)
./gradlew :app:assembleDebug

# Unit tests for this feature
./gradlew :app:testDebugUnitTest --tests "com.beatraxus.app.fpsbooster.*"
# (and the existing timing tests)
./gradlew :app:testDebugUnitTest --tests "com.beatraxus.app.motionboost.*"

# Shader / quality harness (Linux with Mesa EGL + GLES2: libEGL.so.1, libGLESv2.so.2; python3 with numpy + opencv-python)
cd tools/fpsbooster_harness
./fetch_data.sh                                              # downloads OpenCV sample media + Middlebury RubberWhale (not committed)
python3 run_eval.py --engines repeat,blend,legacy,new --size 1280x720 --save   # table above; out/*.png = output | truth | 4x difference
python3 check_regression.py                                  # pass/fail quality gate
python3 flow_eval.py                                         # flow end-point error on RubberWhale
```

## 9. Device testing guide (OPPO Reno 10 5G; Dimensity 7050, Mali-G68 MC4, 1080×2412 @ up to 120 Hz per GSMArena)

1. Build and install the debug APK. If it fails to compile, fix and tell me; expect small issues (§6).
2. Settings → player → FPS Booster sheet. Confirm only Off/On/Auto, targets, Engine (Auto/Optical flow/Classic), Quality, switches and stats are shown, and that targets the panel cannot pace are greyed.
3. Set the phone to **High/120 Hz refresh**. Play a **24 fps 1080p SDR** file (slow camera pan). Choose On, 60, Engine *Optical flow*, Quality *Performance*, Live statistics on.
4. Check the stats: Source ≈ 24; Rendered ≈ 60; Generated ≈ 48 per second; Dropped ≈ 0; Processing latency well under 29 ms (= 70 % of the 41.7 ms source interval); note Video delay. Compare with Off by eye (pan smoothness, ghosting at edges).
5. Raise Quality to *Balanced*, then *Quality*; watch latency, dropped frames, and whether the adaptive controller steps the processing size down (stats show size and quality label). Record at which setting it stops keeping up.
6. 24 → 30 (target 30) and 24 → 60 first; then 45/90/120.
7. Behaviours: seek (no smearing across the jump), pause/resume, speed 0.5×/1.5×/2×, rotate and full-screen toggle (video must resume, not black), background and return, subtitles (embedded + external; readable, in sync), aspect/zoom modes, an HDR file (booster must stay off and say so), a local file and a streaming/add-on source, a clip with scene cuts (no cross-dissolve at the cut).
8. A/V sync: use a clap/flash test clip; if the picture lags, use the existing Audio delay and report how many ms were needed.
9. Sustained run: 30 min at your chosen setting. Note battery drop, device temperature, and whether a "too hot / could not keep up" fallback toast appears (that is the intended behaviour). Optional: `adb shell dumpsys gfxinfo`, `adb shell dumpsys thermalservice`, `adb shell dumpsys batterystats`, Perfetto/Snapdragon-style GPU profiling is not available on MediaTek, so rely on the in-app latency figure.
10. Send me: device log (`adb logcat -s FpsBoosterGL FpsBoosterView FpsBoosterMgr`), the stats at each quality level, and any artifacts as screenshots.

## 10. AI interpolation (RIFE): assessment — **not implemented**

Researched from primary sources by a read-only agent; **nothing was downloaded and no Android GPU support is claimed.**

- **Licence:** RIFE (ECCV2022-RIFE) code is MIT (Megvii); Practical-RIFE code is MIT. The Practical-RIFE README says its model links are "under the same MIT license". That is the author's statement; the weights are hosted on Google Drive/Baidu without a separate licence file, and training-data licences (e.g. Vimeo90K) were not examined, so there is residual provenance risk. `nihui/rife-ncnn-vulkan` is MIT, ncnn is BSD-3, MNN is Apache-2.0. Redistribution is permitted if notices are kept; re-host weights yourself rather than linking Drive.
- **Size:** ncnn-converted `flownet.bin`: rife-v4 10.3 MB, rife-v4.6 10.6 MB, rife-HD 26.9 MB, rife-v2.3 30.6 MB (probably fp32; unchecked).
- **Ops:** the v4.6 graph has 40 convolutions, 4 deconvolutions, 4 PixelShuffle, 8 resizes and 8 custom `Warp` (= `grid_sample`) layers. `grid_sample`, deconvolution and dynamic resize are the mobile-runtime problem ops.
- **Runtimes:** ONNX Runtime NNAPI EP lists no GridSample/ConvTranspose (not viable); XNNPACK EP is CPU-only and falls back for GridSample; the TFLite GPU delegate source has no GridSample (grep only; treat as unsupported); ncnn has a GridSample op and Android Vulkan builds; MNN has Vulkan/OpenCL GridSample sources. **None of these is verified on a Mali-G68.**
- **Existing ports/benchmarks:** no Android RIFE port or published mobile latency was found (search was not exhaustive; github.com HTML pages were blocked).
- **Estimate (labelled as such):** 24→48 fps needs ~24 inferences/s (~40 ms each including read-back); 24→60 needs more than one per source frame. That is very likely too heavy for a 4-core mid-range Mali at 720p; 540p or below with a lite model is plausible but unproven. Frame memory is small (fp16 RGB 540p ≈ 3 MB, 720p ≈ 5.4 MB, 1080p ≈ 12 MB) but feature maps dominate and were not quantified.
- **Recommendation: do not ship it in the base app. If pursued, deliver it as an optional downloadable model pack (hash-checked, MIT notice kept), behind an on-device benchmark gate (p95 < ~35 ms at the chosen resolution, sustained and thermal-tested).** First step would be a throwaway Android app that runs rife-v4.6 and a lite model with ncnn+Vulkan (MNN as the fallback runtime) at 540p/720p, fp16 on/off, for 5+ minutes, recording latency, memory and temperature. Only then design the Media3/SurfaceTexture integration. The engine setting already has an enum to extend (`BoosterEngine`), but no AI option is shown today.

For the supported device range, AI interpolation is **not practical today** on mid-range SoCs; it could be an opt-in extra on flagship GPUs after the benchmark above.
