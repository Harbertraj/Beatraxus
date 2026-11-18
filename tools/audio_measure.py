#!/usr/bin/env python3
"""
Beatraxus audio measurement tool.

  python tools/audio_measure.py gen  --out testsig/          # make test WAVs to play on the phone
  python tools/audio_measure.py thd  rec.wav --f0 1000        # THD, THD+N, SNR, spurs of a recorded tone
  python tools/audio_measure.py null ref.wav rec.wav          # latency + null (residual) test
  python tools/audio_measure.py fr   ref_sweep.wav rec.wav    # frequency response (1/6 octave) + latency
  python tools/audio_measure.py selftest                      # checks this tool against synthetic signals

Record the phone's output with a digital capture (USB DAC loopback / ADC) or a good analog loopback. For the bit-perfect
test, a pure digital capture (USB audio class capture of the DAC input, or the Android "internal audio" capture of an
offline render) is needed; an analog loopback can only show the noise floor of the loop.

Needs: numpy, scipy.
"""
import argparse
import os
import sys
import wave

import numpy as np
from scipy import signal
from scipy.io import wavfile

# ----------------------------------------------------------------------------------------------- io / generation

def read_wav(path):
    """Returns (rate, float64 array shaped (frames, channels)) scaled to +-1.0."""
    rate, d = wavfile.read(path)
    if d.dtype == np.int16:
        x = d / 32768.0
    elif d.dtype == np.int32:
        x = d / 2147483648.0
    elif d.dtype == np.uint8:
        x = (d - 128) / 128.0
    else:
        x = d.astype(np.float64)
    if x.ndim == 1:
        x = x[:, None]
    return rate, x


def write_wav_float(path, rate, x):
    wavfile.write(path, rate, x.astype(np.float32))


def write_wav_pcm24(path, rate, x):
    """24-bit PCM (scipy cannot write it)."""
    x = np.clip(x, -1.0, 1.0 - 1.0 / 8388608)
    ints = np.round(x * 8388607).astype(np.int32)
    if ints.ndim == 1:
        ints = ints[:, None]
    b = np.empty((ints.shape[0], ints.shape[1], 3), dtype=np.uint8)
    b[..., 0] = ints & 0xFF
    b[..., 1] = (ints >> 8) & 0xFF
    b[..., 2] = (ints >> 16) & 0xFF
    with wave.open(path, "wb") as w:
        w.setnchannels(ints.shape[1]); w.setsampwidth(3); w.setframerate(rate)
        w.writeframes(b.tobytes())


def make_sine(rate, f0, secs, level_db):
    n = int(rate * secs)
    t = np.arange(n) / rate
    return 10 ** (level_db / 20) * np.sin(2 * np.pi * f0 * t)


def make_sweep(rate, secs=10.0, f1=20.0, f2=20000.0, level_db=-6.0):
    n = int(rate * secs)
    t = np.arange(n) / rate
    x = signal.chirp(t, f1, secs, min(f2, rate / 2 * 0.99), method="logarithmic")
    fade = int(0.01 * rate)
    x[:fade] *= np.linspace(0, 1, fade); x[-fade:] *= np.linspace(1, 0, fade)
    return 10 ** (level_db / 20) * x


def cmd_gen(a):
    os.makedirs(a.out, exist_ok=True)
    lead = np.zeros(int(0.5 * a.rate))
    made = []
    for f0 in (1000, 10000, 15000, 19000):
        x = np.concatenate([lead, make_sine(a.rate, f0, 8.0, -3.0), lead])
        p = os.path.join(a.out, f"sine_{f0}hz_-3dBFS_{a.rate}.wav")
        write_wav_pcm24(p, a.rate, np.stack([x, x], 1)); made.append(p)
    x = np.concatenate([lead, make_sweep(a.rate), lead])
    p = os.path.join(a.out, f"sweep_20-20k_-6dBFS_{a.rate}.wav")
    write_wav_pcm24(p, a.rate, np.stack([x, x], 1)); made.append(p)
    # intermodulation-ish multitone and a silent file for the noise floor
    t = np.arange(int(a.rate * 8.0)) / a.rate
    mt = sum(np.sin(2 * np.pi * f * t) for f in (60, 440, 3000, 7000)) * 10 ** (-12 / 20) / 4
    p = os.path.join(a.out, f"multitone_-12dBFS_{a.rate}.wav")
    write_wav_pcm24(p, a.rate, np.stack([mt, mt], 1)); made.append(p)
    p = os.path.join(a.out, f"silence_{a.rate}.wav")
    write_wav_pcm24(p, a.rate, np.zeros((int(a.rate * 8), 2))); made.append(p)
    print("Wrote:"); [print("  ", m) for m in made]


# ---------------------------------------------------------------------------------------------- analysis helpers

def db(x):
    return 20 * np.log10(max(x, 1e-20))


def pick_channel(x, ch):
    return x[:, min(ch, x.shape[1] - 1)]


def analyze_tone(x, rate, f0=None, bw_hz=20000.0, trim_s=1.0):
    """Windowed-FFT analysis of a steady tone. Returns a dict (dB relative to the fundamental unless stated)."""
    n_trim = int(trim_s * rate)
    y = x[n_trim:-n_trim] if len(x) > 3 * n_trim else x
    n = len(y)
    w = signal.windows.blackmanharris(n)
    S = np.abs(np.fft.rfft(y * w)) ** 2
    fr = np.fft.rfftfreq(n, 1 / rate)
    df = fr[1]
    lobe = int(np.ceil(4 * 1.0 / (n / rate) / df)) + 4      # blackman-harris main lobe is +-4 bins
    if f0 is None:
        f0 = fr[np.argmax(S[int(30 / df):]) + int(30 / df)]
    b0 = int(round(f0 / df))
    sig = S[b0 - lobe:b0 + lobe + 1].sum()
    f0_meas = fr[b0 - lobe + np.argmax(S[b0 - lobe:b0 + lobe + 1])]
    rms_dbfs = db(np.sqrt(np.mean(y ** 2)) * np.sqrt(2))  # peak-equivalent of the tone

    band = (fr >= 20) & (fr <= min(bw_hz, rate / 2 * 0.999))
    mask = band.copy()
    mask[max(0, b0 - lobe):b0 + lobe + 1] = False
    noise_total = S[mask].sum()

    harm = 0.0
    harm_db = {}
    for h in range(2, 11):
        fh = f0_meas * h
        if fh > min(bw_hz, rate / 2 * 0.99):
            break
        bh = int(round(fh / df))
        p = S[bh - lobe:bh + lobe + 1].sum()
        harm += p
        harm_db[h] = 10 * np.log10(max(p / sig, 1e-30))

    # spurs: strongest non-harmonic peaks (aliasing products show up here)
    m2 = mask.copy()
    for h in range(2, 11):
        bh = int(round(f0_meas * h / df))
        m2[max(0, bh - lobe):bh + lobe + 1] = False
    Sm = np.where(m2, S, 0.0)
    spurs = []
    for _ in range(5):
        i = int(np.argmax(Sm))
        if Sm[i] <= 0:
            break
        lo, hi = max(0, i - lobe), i + lobe + 1
        spurs.append((fr[i], 10 * np.log10(Sm[lo:hi].sum() / sig)))   # same lobe-sum as the fundamental
        Sm[lo:hi] = 0.0

    return {
        "f0_hz": f0_meas,
        "level_dbfs": rms_dbfs,
        "thd_db": 10 * np.log10(max(harm / sig, 1e-30)),
        "thd_n_db": 10 * np.log10(max(noise_total / sig, 1e-30)),
        "harmonics": harm_db,
        "spurs": spurs,
    }


def xcorr_delay(ref, rec, max_s=3.0, rate=48000):
    """Delay (samples) of rec relative to ref, from the cross-correlation peak (FFT based)."""
    n = len(ref) + len(rec)
    nfft = 1 << (n - 1).bit_length()
    c = np.fft.irfft(np.fft.rfft(rec, nfft) * np.conj(np.fft.rfft(ref, nfft)), nfft)
    maxlag = int(max_s * rate)
    c = np.concatenate([c[-maxlag:], c[:maxlag + 1]])
    return int(np.argmax(np.abs(c))) - maxlag


def align(ref, rec, rate):
    d = xcorr_delay(ref, rec, rate=rate)
    if d >= 0:
        r2 = rec[d:]
        r1 = ref[:len(r2)]
    else:
        r1 = ref[-d:]
        r2 = rec[:len(r1)]
    n = min(len(r1), len(r2))
    return r1[:n], r2[:n], d


# ------------------------------------------------------------------------------------------------------ commands

def cmd_thd(a):
    rate, x = read_wav(a.file)
    r = analyze_tone(pick_channel(x, a.channel), rate, a.f0, a.bw)
    print(f"File {a.file}  ({rate} Hz, channel {a.channel})")
    print(f"  Fundamental     {r['f0_hz']:.1f} Hz at {r['level_dbfs']:.2f} dBFS")
    print(f"  THD             {r['thd_db']:.1f} dB   ({100 * 10 ** (r['thd_db'] / 20):.5f} %)")
    print(f"  THD+N (20-{a.bw / 1000:.0f}k) {r['thd_n_db']:.1f} dB   ({100 * 10 ** (r['thd_n_db'] / 20):.5f} %)")
    print("  Harmonics       " + "  ".join(f"H{h}:{v:.0f}" for h, v in r["harmonics"].items()))
    print("  Strongest non-harmonic spurs (aliasing / images show up here):")
    for f, d in r["spurs"]:
        print(f"      {f:9.1f} Hz   {d:6.1f} dB")


def cmd_null(a):
    ra, ref = read_wav(a.ref)
    rb, rec = read_wav(a.rec)
    if ra != rb:
        sys.exit(f"Sample rates differ ({ra} vs {rb}); resample one first (that difference is itself a finding).")
    r1, r2, d = align(pick_channel(ref, a.channel), pick_channel(rec, a.channel), ra)
    # best gain match: a bit-perfect path should need gain exactly 1.0
    g = float(np.dot(r1, r2) / max(np.dot(r2, r2), 1e-30))
    res = r1 - r2 * g
    res_unity = r1 - r2
    print(f"Latency / offset  {d} samples = {1000 * d / ra:.3f} ms")
    print(f"Best-fit gain     {g:.6f}  ({db(g):+.4f} dB)   (1.000000 expected for bit-perfect)")
    print(f"Null residual     {db(np.sqrt(np.mean(res_unity ** 2)) / max(np.sqrt(np.mean(r1 ** 2)), 1e-20)):.1f} dB re reference (gain 1)")
    print(f"                  {db(np.sqrt(np.mean(res ** 2)) / max(np.sqrt(np.mean(r1 ** 2)), 1e-20)):.1f} dB re reference (best gain)")
    exact = np.array_equal(r1.astype(np.float32), r2.astype(np.float32))
    print(f"Bit-exact         {'YES' if exact else 'no'}  (max |diff| = {np.max(np.abs(r1 - r2)):.3e})")


def cmd_fr(a):
    ra, ref = read_wav(a.ref)
    rb, rec = read_wav(a.rec)
    if ra != rb:
        sys.exit("Sample rates differ; capture at the same rate as the sweep.")
    r1, r2, d = align(pick_channel(ref, a.channel), pick_channel(rec, a.channel), ra)
    n = len(r1)
    w = signal.windows.tukey(n, 0.05)
    R = np.fft.rfft(r1 * w)
    C = np.fft.rfft(r2 * w)
    fr = np.fft.rfftfreq(n, 1 / ra)
    H = np.abs(C) / np.maximum(np.abs(R), 1e-12)
    print(f"Latency {1000 * d / ra:.3f} ms")
    ref_band = (fr >= 900) & (fr <= 1100)
    norm = np.mean(H[ref_band])
    print("  Freq (Hz)   Level (dB re 1 kHz)")
    for fc in (25, 31.5, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 12500, 16000, 18000, 20000):
        if fc > ra / 2 * 0.98:
            continue
        lo, hi = fc / 2 ** (1 / 12), fc * 2 ** (1 / 12)
        m = (fr >= lo) & (fr <= hi)
        print(f"  {fc:8.1f}   {db(np.mean(H[m]) / norm):+7.2f}")


# --------------------------------------------------------------------------------------------------------- selftest

def cmd_selftest(_):
    rate = 48000
    ok = True

    def check(name, cond, info=""):
        nonlocal ok
        ok &= bool(cond)
        print(f"  [{'PASS' if cond else 'FAIL'}] {name} {info}")

    print("selftest:")
    # 1. clean tone: THD+N must be extremely low (limited by float precision / window leakage only)
    x = make_sine(rate, 1000, 6, -3)
    r = analyze_tone(x, rate, 1000)
    check("clean tone THD+N < -120 dB", r["thd_n_db"] < -120, f"({r['thd_n_db']:.1f})")
    # 2. injected 3rd harmonic at -60 dB must be found within 1 dB
    t = np.arange(len(x)) / rate
    y = x + 10 ** (-3 / 20) * 10 ** (-60 / 20) * np.sin(2 * np.pi * 3000 * t)
    r = analyze_tone(y, rate, 1000)
    check("injected H3 -60 dB is measured", abs(r["harmonics"][3] + 60) < 1.0, f"(H3 {r['harmonics'][3]:.1f})")
    check("THD matches (-60 dB)", abs(r["thd_db"] + 60) < 1.0, f"({r['thd_db']:.1f})")
    # 3. injected non-harmonic spur (an 'alias') at -70 dB must appear in the spur list
    y = x + 10 ** (-3 / 20) * 10 ** (-70 / 20) * np.sin(2 * np.pi * 7777 * t)
    r = analyze_tone(y, rate, 1000)
    top = r["spurs"][0]
    check("injected spur found at 7777 Hz", abs(top[0] - 7777) < 5 and abs(top[1] + 70) < 1.5, f"({top[0]:.0f} Hz {top[1]:.1f} dB)")
    # 4. latency and gain measurement: delay by 1234 samples, gain -1 dB
    sw = make_sweep(rate, 4.0)
    ref = np.concatenate([np.zeros(500), sw, np.zeros(500)])
    rec = np.concatenate([np.zeros(1234), ref * 10 ** (-1 / 20)])[: len(ref)]
    r1, r2, d = align(ref, rec, rate)
    g = float(np.dot(r1, r2) / np.dot(r2, r2))
    check("latency 1234 samples", d == 1234, f"({d})")
    check("gain +1 dB recovered", abs(db(g) - 1.0) < 1e-3, f"({db(g):+.4f} dB)")
    # 5. a bit-exact copy nulls to exactly zero
    r1, r2, d = align(ref, ref.copy(), rate)
    check("identical signals are bit-exact", np.array_equal(r1, r2))
    print("ALL PASS" if ok else "FAILURES")
    sys.exit(0 if ok else 1)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("gen"); g.add_argument("--out", default="testsig"); g.add_argument("--rate", type=int, default=48000)
    t = sub.add_parser("thd"); t.add_argument("file"); t.add_argument("--f0", type=float); t.add_argument("--bw", type=float, default=20000.0); t.add_argument("--channel", type=int, default=0)
    n = sub.add_parser("null"); n.add_argument("ref"); n.add_argument("rec"); n.add_argument("--channel", type=int, default=0)
    f = sub.add_parser("fr"); f.add_argument("ref"); f.add_argument("rec"); f.add_argument("--channel", type=int, default=0)
    sub.add_parser("selftest")
    a = p.parse_args()
    {"gen": cmd_gen, "thd": cmd_thd, "null": cmd_null, "fr": cmd_fr, "selftest": cmd_selftest}[a.cmd](a)


if __name__ == "__main__":
    main()
