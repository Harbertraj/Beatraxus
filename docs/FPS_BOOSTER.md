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

### Device test (first run) and recommended settings

Opening the booster sheet for the first time runs a ~3 s CPU + GPU test (`BoosterBenchmarkRunner`) and applies
the result (`BoosterRecommender`); it can be repeated with "Test again". Playback pauses while it runs.

- GPU: the real `flow_search.frag` motion search, forward + backward, over the pyramid of each quality preset
  (640 / 960 / 1280 px, 16:9), then one 1080p warp-style output pass. The numbers are ms per frame pair and ms per
  output frame on this device; the GPU score is motion-search throughput (1000 points = 10 ms per megapixel of
  analysed image).
- CPU: a 0.45 s multi-threaded float loop (informational, plus a cap at 60 FPS for very slow CPUs).
- Recommendation: the best quality whose pair time fits half a 30 FPS source interval (16.7 ms), then the highest
  target the panel can pace evenly with 30 pair estimations + one warp per displayed frame under 650 ms of GPU
  time per second. If even Performance does not fit, or the GPU cannot render float textures, Classic is chosen.
  The thresholds are conservative engineering estimates, not calibrated on a device farm.
- The result is stored (`fpsb_bench`); a skipped first run is not started again automatically.
- Quality presets are greyed out when the Classic engine is selected (they only apply to Optical flow).

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

---

## 10. Fast-motion fix, round 1 (superseded by §11) (glitchy / patchy picture on fast pans)

Symptom: on fast motion (roughly 90+ px/frame at 720p) the interpolated frames showed blocky patches of the unmoved
frame. Measured with the harness (Performance preset, 1280x720): pan 90 px/frame 19.3 dB -> 22.9, pan 140 -> 11.0 (same as
frame repeat) -> 17.7, pan 60,30 -> 17.8 -> 21.8; slow scenes unchanged.

- Pyramid goes down to 64 px (was 128) with up to 6 levels, so the +-6 coarse search reaches about twice as far.
  `COARSEST_EDGE` / `MAX_LEVELS` are mirrored in `OpticalFlowRenderer`, `BoosterBenchmarkRunner` and `new_engine.py`.
  (Going to 32 px was tried and made mid-speed motion worse: aliasing at the tiniest level.)
- `flow_interp.frag`: the forward/backward and refined-vector tolerances grow with the vector length. Fixed tolerances
  rejected most of a fast picture and showed the unmoved frame in patches.
- Hue / colour temperature (`uColorDelta`) are applied in the booster shaders too.

---

## 11. Fast-motion fix, round 2: "Quality is 10x worse" (glitch + lag)

Two separate causes, fixed separately. All numbers: desktop harness (llvmpipe), image quality and fetch counts only;
**nothing here was run on a phone.**

### 11.1 Picture (shaders + pyramid)
- `flow_search.frag` (see the commit message of the WIP patch): unrolled 16/64-tap SAD, static-block early-out,
  `uFullCost`, dominant-motion candidate (`uGlobal`, `uGlobalScale`). On top of that:
  - the coarsest full search now uses the exact 64-tap cost (`totalExact`). With a range of 10 the 16-tap cost found
    false matches in the aliased coarsest image; the grid is tiny, so the exact cost is cheap there;
  - sub-pixel stays a half- then quarter-pixel ring but on the 16-tap cost, with one exact 64-tap evaluation of the
    result. A parabola / V fit through the integer costs was tried and rejected (-4 dB on slow pans: SAD is V-shaped
    around its minimum, so the fit recovers only part of the offset).
- `global_motion.frag` runs on the coarsest field of each direction (12x8 lattice, `uMaxCost` 0.08) and its 1x1
  result is bound as `uGlobal` for every finer level with `uGlobalScale = levelW[l] / levelW[coarsest]`.
  `uFullCost = 1` only on level 0 and the coarsest level (the only costs read later: consistency, cut, dominant motion).
  It is run once more on the final forward field into a per-slot 1x1 texture (`Slot.glob`) for the interpolator.
- `flow_interp.frag`: (1) static early-out: a pixel whose A and B are identical and whose flow is ~0 skips all six warp
  fetches; (2) where neither per-pixel warp is trusted, the dominant motion is a third warp that is blended in only
  where the two frames agree under it (`length(ga - gb)`), weighted by how much of the picture supports it and by
  `1 - conf`. Moving objects fail the colour check and keep the nearest-frame fallback.
- Pyramid depth: `FlowPyramid` (Kotlin, unit tested; mirrored in `new_engine.level_sizes`). Halve while the longest edge
  is above `40 * sqrt(2)`, i.e. the coarsest level is the one closest to 40 px: Performance 640 -> 40, Balanced
  960 -> **30** (was 60), Quality 1280 -> 40. Coarsest search range 6/8 -> **10** for all presets.
  Measured trap: range 10 on the *old* 60 px Balanced pyramid is worse than range 6 (more false matches); the
  deeper pyramid is what makes the wide range pay off.
- `BoosterBenchmarkRunner` uses the same pyramid / passes, so its timings match the renderer.

Interior-only PSNR (dB), Balanced, 1280x720; border of |v|+16 px excluded (`eval_interior.py`). Columns: pans of
10 / 28 / 56 / 90 / 140 / 200 px, diagonal pan 60,30, fast layers (disc -80 px/f over a +16 px/f background), zoom+rotation.

| | 10 | 28 | 56 | 90 | 140 | 200 | 60,30 | layers | zoom |
|---|---|---|---|---|---|---|---|---|---|
| baseline Balanced | 51.2 | 44.5 | 33.2 | 37.7 | 16.6 | 11.4 | 14.1 | 25.4 | 42.3 |
| **new Performance** | 52.6 | 44.5 | 44.8 | 52.4 | 49.1 | 40.2 | 36.8 | 26.0 | 40.6 |
| **new Balanced** | 59.2 | 44.5 | 44.7 | 60.2 | 64.9 | 61.5 | 50.9 | 26.0 | 42.3 |
| **new Quality** | 74.8 | 44.6 | 44.8 | 73.4 | 62.4 | 65.7 | 49.7 | 26.2 | 42.1 |

Whole-frame regression gate (`check_regression.py`) still passes (the interior floors for fast pans were added to it);
real footage (vtest) 29.28 dB / SSIM 0.979 (unchanged), scene cut stays clean (99 dB). Honest caveats: synthetic,
pure-translation pans are the best case for a dominant-motion candidate; the layers scene (independent object motion)
improves only 0.6-0.8 dB, which is what to expect when the picture has no single dominant motion.

### 11.2 Texture fetches per pair (search passes, both directions; exact, from an instrumented shader)
`count_fetches.py`, pan 56 px/f at 1280x720. Early-outs make the number scene dependent: a fully static scene is shown too.

| preset | old pipeline | new, pan 56 | new, static scene |
|---|---|---|---|
| Performance | 41.9 M | 39.0 M | 6.4 M |
| Balanced | 160.6 M | 99.1 M | 14.5 M |
| Quality | 284.4 M | 175.3 M | 25.7 M |

(The old pipeline has no early-out, so its count does not depend on the scene.) Fetches are not milliseconds; the
real cost on a Mali-G68 is unmeasured. Not included: `global_motion` (~9 k fetches per call, 3 per pair),
`flow_consistency` (2 per block), the interpolation pass (per output frame).

### 11.3 Scheduling (the main cause of "Quality is 10x worse")
Symptom analysis: with a heavy preset the estimate of a pair takes longer than the source interval. The old renderer
queued a new estimate for **every** arriving frame, so the GPU backlog grew; every later estimate *and every
presented frame* waited behind it (glitch = frames shown with no / stale flow; lag = latency growing without bound).
The vsync tick could also start an estimate itself (`!b.hasFlow`) on top of that. The "10x" is consistent with this
(a backlog is unbounded; a slower search is merely proportional), but it is an inference from the code, not a
device measurement.
- At most one estimate in flight: a new one starts only when the previous fence has signalled (`gpuBusy()`),
  otherwise the pair is marked `flowSkipped` and shown as real frames. The tick's fall-back estimate also requires an
  idle GPU and a pair that was not skipped.
- A pair without flow shows the **nearest real frame by alpha** (`alpha < 0.5 ? a : b`), not always `b`.
- `HoldController.update(..., estimateNs)` adds the smoothed measured latency (`EstimateLatency`: rises fast,
  decays slowly; capped at 100 ms) to the hold, via `MotionBoostTiming.holdNs(..., estimateNs)`.
  Cost: Quality adds its latency to "Video delay" (the stat shows it; use Audio delay to compensate if needed).
- Latency is now timed on **every** pair (the fence is polled with `glClientWaitSync(.., 0)`, no `glFinish`), measured
  from the arrival of the second frame to "flow ready".
- `WindowStats.skippedEstimates` counts skipped pairs; `AdaptiveQualityController` treats > 5 % skipped as overload
  and does not count a window with any skip as calm.

### 11.4 Timestamp regularisation (`TimestampRegularizer`)
ExoPlayer releases frames on a vsync, so `SurfaceTexture` stamps are snapped to the panel grid (24 fps on 60 Hz is
33/50/33/50 ms). The interpolation phase comes from those stamps, so the quantisation shows up as periodic judder in
the generated frames. The class fits the interval by least squares over 24 stamps and pulls each stamp towards the
prediction (1/8 per frame), so the clock still follows the raw one (unit-tested: raw 24 / 25 / 23.976 fps jitter
>= 15 ms -> < 4 ms, never more than one vsync from the raw stamp, strictly increasing, a seek / pause / lost frame
resets it). Used in `OpticalFlowRenderer.onFrameAvailable` after the gap / seek tests, which still use raw stamps.
**Not verified on a device** (the premise, that ExoPlayer's stamps are vsync-snapped, is from the platform behaviour
described in the task, not measured here). If it misbehaves, it is one line to bypass (`ts = tsRegularizer.next(...)`).

### 11.5 Tests / verification run
- Kotlin 2.4.10 compiler + Android API 34 `android.jar` (from GitHub release assets / Sable/android-platforms): the
  `fpsbooster` package, its dependencies and `MotionBoostTiming` compile with no errors (`FpsBoosterSurfaceView`
  excluded: needs Media3).
- 100 JUnit tests pass (JUnit 4.13.2, run with `JUnitCore` outside Gradle): all `fpsbooster` tests, `MotionBoostTimingTest`,
  new `FlowPyramidTest`, `TimestampRegularizerTest`, `HoldEstimateLatencyTest`, controller / stats tests, and the updated
  `ShaderAssetsTest` (new `global_motion.frag`, `uGlobal`, `uGlobalScale`, `uFullCost`).
- **Not done:** Gradle build, any on-device run, GPU timing, thermal / battery. The GL orchestration in
  `OpticalFlowRenderer.kt` (fence gating, skipped pairs, per-slot textures) type-checks and mirrors the harness, but its
  runtime behaviour is untested.

## 12. AI-Powered FPS Booster (RIFE 4.26 Implementation)
Status: **Implemented with ONNX Runtime Mobile (`RifeAiEngine.kt`)**

- **Model & Pipeline**: Uses RIFE 4.26 ONNX model (`rife_v426.onnx`) with local inference via `com.microsoft.onnxruntime:onnxruntime-android:1.18.0`.
- **Acceleration**: NNAPI and XNNPACK CPU/GPU execution providers enabled with multi-threaded tensor processing.
- **Engine Selection**: Added `AI_RIFE` ("RIFE AI") to `BoosterEngine` enum, fully integrated into `FpsBoosterPlanner`, `FpsBoosterSettings`, `FpsBoosterScreen`, and `VideoPlayerScreen`.
- **Target Frame Rates**: Supports 48, 60, 90, and 120 FPS targets with timestep-aware frame synthesis ($t \in (0, 1)$) and Choreographer vsync scheduling.
- **Motion Artifact Fixes**: Applied strict photometric color-difference checks (`smoothstep(0.03, 0.10, length(fa - fb))`) in `flow_interp.frag` to eliminate edge tearing, ghosting, and body-stretching distortions during fast movement.

## 13. AI fusion for the optical-flow engine (work in progress, part 1: groundwork)

Why: the hand-tuned "is this warp trustworthy?" logic in `flow_interp.frag` is the weak part of the optical-flow engine.
Measured on the desktop harness (960x540, Balanced), the per-pixel best of the SAME candidate pictures the shader already
computes (nearest frame, forward / backward flow warps, dominant-motion warp, plus the two warps of the farther frame)
would score far above the shipping logic. This is an upper bound (an oracle that sees the truth), not a result:

| scene | shipping logic | oracle choice of 6 candidates |
|---|---|---|
| pan 10 px/f | 42.2 dB | 83.8 |
| pan 56 px/f | 27.1 | 45.1 |
| layers (disc over pan) | 30.4 | 40.9 |
| layers, fast (bg 16 / fg -80) | 24.5 | 42.4 |
| vtest.avi (real) | 27.9 | 34.2 |
| Megamind.avi (real) | 34.2 | 37.9 |
| big_buck_bunny.mp4 (real) | 26.1 | 32.4 |

(zoom + rotation: the oracle is *lower* than the shipping logic, 35.2 vs 40.9, because the shader's blend of two warps
beats picking one; the learned fusion blends, it does not pick.)

The plan is a small network (22 inputs, ~600 weights) that decides the blend weights of the six candidates per pixel,
computed at motion-search size and compiled into the shader as plain GLSL (no runtime, no model file, no APK growth).

Part 1 (this change) contains only inert groundwork - **the app behaves exactly as before**:
- `flow_interp.frag`: optional `AI_FUSION` / `FUSE_PASS` / `AI_DUMP` programs behind `#define`s (all default 0).
  The default program is the original shader.
- `tools/fpsbooster_harness`: multi-render-target support, `new_engine.py` can run the AI path and dump training records,
  `ai/gen_data.py` (training data from the shipped shaders on synthetic scenes + real video), `ai/train_fusion.py`
  (JAX trainer + GLSL exporter), `ai/fetch_train_media.sh`.

Part 2 (follow-up change): first trained weights, Kotlin integration, still **off by default and no UI**:
- Fixed two groundwork bugs found while training: `NewEngine.dump()` flipped the rows of the readback (candidates were
  upside-down against the ground truth: every candidate scored ~10 dB and nothing could be learned), and the GLSL exporter
  emitted `x[22]` for the last input group (compile error: array index must be < 22).
- `app/src/main/assets/fpsbooster/ai_fusion.glsl`: 22 -> 16 -> 10 -> 6 network, 604 parameters, generated by `ai/train_fusion.py`.
  Trained on only ~1000 (A, B, t) records (synthetic + real video, held-out media excluded), 30 epochs on a CPU.
  **Preliminary:** retrain on far more data before shipping.
- `InterpShaderSource.kt` (+ `InterpShaderSourceTest`): builds the three programs; mirror of `interp_source` in `new_engine.py`.
- `OpticalFlowRenderer.setAiFusion(Boolean)`: fusion pass (2 float attachments, at motion-search size, capped at 960 px)
  + AI composite. Any compile / allocation failure keeps the hand-tuned path.
- `run_eval.py --engines new,new_ai` compares both on the harness (640x360, balanced, desktop llvmpipe):

| scene | hand-tuned (shipping) | AI fusion |
|---|---|---|
| pan 3 px/f | 45.2 dB | 47.3 |
| pan 10 px/f | 35.7 | 50.1 |
| pan 28 px/f | 28.1 | 42.0 |
| pan 56 px/f | 21.2 | 42.8 |
| pan 20,12 px/f | 25.8 | 36.8 |
| zoom + rotation | 43.3 | 47.0 |
| layers (disc over pan) | 26.6 | 33.8 |
| real footage (vtest.avi, held out) | 28.6 | 32.1 |

Caveat: the synthetic eval scenes are the same family as part of the training data (vtest.avi is the only held-out real clip).

Still missing: Gradle build / unit tests, on-device run and timing (the pass must be benchmarked on phones), UI /
settings toggle and an on-device benchmark gate, larger training set.

### 13.x Part 3: settings, benchmark gate, presets and sizes

**Wiring.** `FpsBoosterSettings.aiFusion` (default off, stored as `fpsb_ai_fusion`) is shown in the FPS Booster sheet as
"AI fusion (experimental)". The player calls `FpsBoosterSurfaceView.setAiFusion(...)` with
`BoosterRecommender.aiFusionEnabled(settings, plan)`: user switch on and the Optical flow engine running. It is always
available: the device test no longer blocks it.

**Benchmark hint (advisory).** The device test (now `BenchmarkResult.VERSION = 2`, so old results are re-measured once) times the real
fusion shader writing two RGBA16F targets at the Balanced fusion-map size, worst case. `aiFusionVerdict` returns OK only if
`fuseMs <= 4 ms` and `30 * pairMs + target * (warpMs + fuseMs) <= 650 ms/s`. Otherwise a hint is shown under the switch
(not tested / GPU could not run it / heavy for this quality and target); the switch stays usable and the renderer falls back by itself.

**Renderer fix.** The hand-tuned program is never replaced any more; the AI composite is a separate program used only while
the fusion map exists. Any failure (compile, two float targets, map allocation) drops back to the hand-tuned picture.

**Orientation.** `check_orientation.py` renders all 8 rotations/mirrors with the real shaders (hand-tuned and AI) and
compares them with a pixel move of the unoriented frame: max difference 2 LSB. Its negative control (transposed mapping) fails at 255.

**Presets and sizes (harness, PSNR dB, hand-tuned -> AI).** The AI path is at or above the hand-tuned one on every scene
for Performance, Balanced and Quality at 640x360 and 1280x720 (e.g. 1280x720 Balanced: pan 10 42.1 -> 53.8, pan 56 29.1 -> 44.1,
layers 33.3 -> 41.7, real footage 28.6 -> 32.1), and Balanced at 1920x1080 (pan 56 34.3 -> 49.2, layers 37.3 -> 44.5).
`check_regression.py` now gates all of this (`--no-ai` for the old gate only).

**Retraining.** `train_fusion.py` has a numpy backend (gradient-checked against finite differences) for machines without JAX.
A run on 36 shards (11.7 M samples, 12 epochs) improved interior motion by 4-7 dB but lost 2-3.5 dB on the fastest pans, so the
shipped weights were kept. A better result needs more and more varied real video (the sandbox had only a few clips) and a
loss weighted towards the contested pixels of fast pans.

**Still open: on-device.** Shader compile on real GPUs, the actual `fuseMs` on phones, and thermal behaviour.

### 13.y Part 5: whip-pan patchwork (whole-frame safeguard)

Reported on a real film (24 FPS, dark, handheld, fast blurred pans and crossings): torn "peeling paint" patches. Reproduced in the
harness with `scenarios.whip` (a pan of 500 px/frame with a 130 px shutter blur): the flow fails across the whole picture, each
pixel is then decided on its own (motion-compensated or the unmoved nearest frame), and the result is ragged patches. Dropped frames
were ruled out: it happens at 45 FPS too, and on the harness there is no load at all.

Fix: `cut_detect.frag` now also writes, in `.a`, the share of the picture whose forward/backward inconsistency exceeds
`FAIL_BAD_PX` (2 px, speed-scaled), sampled on a 32x18 lattice. `flow_interp.frag` fades to a plain blend of the two real
frames between `FAIL_LO = 0.30` and `FAIL_HI = 0.60` of that share (both the hand-tuned and the AI composite). Measured shares:
normal scenes <= 0.15, whip 300 ~0.3, whip 500 ~0.8. Whip 500 goes from patchwork to a soft blend (14.4 dB, equal to blend); all
earlier regression floors are unchanged. Edge strips where new content enters the frame are not covered (no flow can know them).
Not verified on a phone: the real clip, and the cost of the extra 576-tap reduction (small: it replaces nothing and runs once per source pair).
