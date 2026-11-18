# Audio test plan

Goal: prove the sound quality with numbers, catch regressions, and find device-specific problems early.
Tool: `tools/audio_measure.py` (`gen` makes the test files, `thd` / `null` / `fr` analyse a recording, `selftest` checks the tool).

## Setup
1. `python tools/audio_measure.py gen --out testsig --rate 48000` (repeat with `--rate 44100` and `96000`). Copy the files to the phone.
2. Capture the output. Best to worst:
   - **Digital capture** of what the DAC receives (USB capture device, or an offline render). Needed for the bit-perfect tests.
   - **Analog loopback** through a good USB DAC/ADC. Shows the whole chain but includes the converters' own noise.
3. Record at the same sample rate as the test file, 24-bit or float, at least 10 s, without clipping.
4. Note: app version, phone model, Android version, output route, DSP settings. Keep the recordings with the results.

## Tests
| ID | What | How | Pass |
|----|------|-----|------|
| T1 | **Bit-perfect** | Bit-perfect mode on, play `sweep`, digital capture, `null ref rec` | gain 1.000000, bit-exact (or residual < -140 dB) |
| T2 | **THD+N per output path** | Play `sine_1000hz`, `thd` on AAudio, MMAP and HiFi paths, DSP off | THD+N < -100 dB digital, or at the converter's floor on analog |
| T3 | **Resampler aliasing** | Play `sine_19000hz` from 44.1 kHz material to a 48 kHz and a 96 kHz output at each SoXR quality level; read the spur list | strongest non-harmonic spur < -90 dB (Very High), < -70 dB (Quick) |
| T4 | **Stem mixer resampling** | Stem mixer on, all stems at 0 dB, 44.1 kHz stems to 48 kHz output; `thd` on `sine_10000hz` | THD+N < -80 dB (the old linear interpolation measures about -20 dB here) |
| T5 | **EQ accuracy** | Set +6 dB at 1 kHz Q 1, then -6 dB, play `sweep`, `fr` | within 0.3 dB of the setting, both phase modes |
| T6 | **EQ headroom** | +10 dB boost at 100 Hz on loud material, record, `thd` on a -3 dBFS tone | no digital clipping; note the output level so loudness loss is visible |
| T7 | **Limiter** | Tone at -0.5 dBFS with +6 dB preamp | output peak <= -1 dBFS ceiling, THD reported |
| T8 | **Noise floor and dither** | Play `silence`, record, check level (try each dither mode) | digital capture exactly 0 with dither off; shaped noise < -110 dBFS with it on |
| T9 | **Latency** | `fr` or `null` prints it for the linear-phase EQ and for stems | linear-phase about 21 ms at 48 kHz; the video path must compensate, together with the booster's automatic delay |
| T10 | **Seek with stems** | Seek 20 times during `sine_1000hz`, record, look for clicks | no clicks above -80 dB; stems fade back in within about 25 ms |
| T11 | **Soak** | 1 hour with EQ, reverb, stems and upscaler on | no underruns, no thermal throttling that causes dropouts |

## Device matrix
Run T1, T2, T4 and T9 on at least: one Snapdragon phone, one MediaTek phone (HiFi path), one Exynos phone, plus a USB DAC and a Bluetooth route. Record which output mode each device actually ends up using, since fallbacks happen silently.

## Reporting
Keep a table per release: test ID, device, route, result, pass/fail. Publish the headline numbers (T1, T2, T3) with the methods, because measured results are what convinces audiophile users.

## Known measurements so far (Python model of the stem resampler, not the device)
| Tone | Old: linear interpolation | New: windowed sinc |
|------|---------------------------|--------------------|
| 1 kHz | -28 dB | -89 dB |
| 5 kHz | -14 dB | -89 dB |
| 10 kHz | -7 dB | -89 dB |
| 15 kHz | -3 dB | -101 dB |

(44.1 kHz stems to a 48 kHz output, 1024-frame buffers, error relative to the tone. The old figures include the timing jump at every buffer boundary.) Confirm with T4 on a real device.
