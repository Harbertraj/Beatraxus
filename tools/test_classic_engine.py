#!/usr/bin/env python3
"""
Offline regression and accuracy test for Beatraxus Audio3DStageEngine Classic Mode.
Measures per-ear gain across frequencies for mono sine input across HRTF modes,
distance scales, and speaker angles.
"""

import sys, os, math, cmath

def db_to_lin(db):
    return 10.0 ** (db / 20.0)

def profile_for(mode):
    P = [
        (1.00, 1.00, 1.00, 1.00, 1.00), # 0 Natural Balanced
        (1.25, 1.15, 1.00, 1.00, 1.00), # 1 Natural Wide
        (1.40, 1.00, 1.40, 1.20, 1.30), # 2 Cinematic
        (0.80, 1.00, 0.50, 0.00, 0.60), # 3 Studio
    ]
    return P[min(max(mode, 0), 3)]

def run_classic_sim(sr=48000, mode=0, dist_scale=1.0, spk_angle=30.0, depth=1.0, height=0.0, room_ref=0.0):
    hp = profile_for(mode)
    k = 1.0; ref_dist = 2.0

    # Speaker 0 (Left)
    az0 = (360.0 - spk_angle) * math.pi / 180.0
    p0 = math.sin(az0) * k
    cwL0 = max(0.0, p0); cwR0 = max(0.0, -p0)
    ipL0 = cwR0; ipR0 = cwL0
    ild0 = 10.0 * hp[0]
    tgL0 = db_to_lin(ild0 * (0.25 * ipL0 - 0.75 * cwL0))
    tgR0 = db_to_lin(ild0 * (0.25 * ipR0 - 0.75 * cwR0))

    # Speaker 1 (Right)
    az1 = spk_angle * math.pi / 180.0
    p1 = math.sin(az1) * k
    cwL1 = max(0.0, p1); cwR1 = max(0.0, -p1)
    ipL1 = cwR1; ipR1 = cwL1
    ild1 = 10.0 * hp[0]
    tgL1 = db_to_lin(ild1 * (0.25 * ipL1 - 0.75 * cwL1))
    tgR1 = db_to_lin(ild1 * (0.25 * ipR1 - 0.75 * cwR1))

    # Distance gain: capped at 1.0 for Classic
    dEff = max(0.3, ref_dist * dist_scale)
    dGain = min(1.0, math.pow(ref_dist / dEff, 0.35 * k * hp[4]))

    gL0 = tgL0 * dGain; gR0 = tgR0 * dGain
    gL1 = tgL1 * dGain; gR1 = tgR1 * dGain

    # Reduced ITD for crosstalk (0.25x)
    itdMax = 0.00066 * hp[1] * 0.25
    tau1_far = cwL1 * itdMax

    # Far-ear LP cutoff
    fc_far = max(200.0, min(4000.0, 700.0 * (0.5 + 0.5 * depth)))

    # Frequency-aware normalisation gains
    normLowL = 1.0 / (gL0 + gL1)
    normHighL = 1.0 / gL0

    freqs = [50, 100, 250, 500, 1000, 1500, 3000, 6000, 10000, 20000]
    res = {}

    w_norm = 2 * math.pi * 600.0
    w_far = 2 * math.pi * fc_far

    for f in freqs:
        w = 2 * math.pi * f
        H_far = 1.0 / (1.0 + 1j * (w / w_far))

        H_near_L = gL0
        H_crosstalk_L = gL1 * H_far * cmath.exp(-1j * w * tau1_far)
        H_wet_L = H_near_L + H_crosstalk_L

        if room_ref > 0.001:
            w_hp300 = 2 * math.pi * 300.0
            H_hp300 = (1j * (w / w_hp300)) / (1.0 + 1j * (w / w_hp300))
            H_er = H_hp300 * (room_ref * 0.20) * cmath.exp(-1j * w * 0.012)
            H_wet_L += H_er

        # 1st order LP/HP crossover split for normalisation
        H_lp_norm = 1.0 / (1.0 + 1j * (w / w_norm))
        H_hp_norm = 1.0 - H_lp_norm

        H_total_L = H_wet_L * (H_lp_norm * normLowL + H_hp_norm * normHighL)
        gain_db = 20 * math.log10(abs(H_total_L))
        res[f] = gain_db
    return res

def main():
    print("=" * 115)
    print("BEATRAXUS CLASSIC SPATIAL MODE MONO TRANSPARENCY TEST")
    print("=" * 115)
    print(f"{'Mode':<5} | {'Dist':<5} | {'Angle':<8} | " + " | ".join([f"{f:>6}Hz" for f in [50, 100, 250, 500, 1000, 1500, 3000, 6000, 10000, 20000]]) + " | Status")
    print("-" * 115)

    fails = 0
    max_def = 0.0
    max_all = 0.0

    freqs = [50, 100, 250, 500, 1000, 1500, 3000, 6000, 10000, 20000]

    for mode in range(4):
        for dist_scale in [0.5, 1.0, 2.0]:
            for spk_angle in [15.0, 30.0, 60.0]:
                r = run_classic_sim(mode=mode, dist_scale=dist_scale, spk_angle=spk_angle)
                row_str = " | ".join([f"{r[f]:>+6.2f}" for f in freqs])

                target = 1.5 if (mode == 0 and dist_scale == 1.0 and spk_angle == 30.0) else 2.5
                row_max_dev = max([abs(r[f]) for f in freqs])

                if mode == 0 and dist_scale == 1.0 and spk_angle == 30.0:
                    max_def = max(max_def, row_max_dev)
                max_all = max(max_all, row_max_dev)

                status = "PASS" if row_max_dev <= target else "FAIL"
                if row_max_dev > target:
                    fails += 1

                tag = " <-- DEFAULT" if (mode == 0 and dist_scale == 1.0 and spk_angle == 30.0) else ""
                print(f"{mode:<5} | {dist_scale:<5.1f} | {spk_angle:<2.0f} deg   | {row_str} | {status}{tag}")

    print("-" * 115)
    print(f"Max Deviation at Defaults (HRTF 0, Dist 1.0, 30 deg): {max_def:+.3f} dB (Target <= 1.5 dB)")
    print(f"Max Deviation Across All Combinations:               {max_all:+.3f} dB (Target <= 2.5 dB)")
    print("=" * 115)

    if max_def > 1.5 or max_all > 2.5 or fails > 0:
        print(f"TEST FAILED ({fails} row failures)")
        sys.exit(1)
    else:
        print("ALL TESTS PASSED SUCCESSFULLY!")
        sys.exit(0)

if __name__ == "__main__":
    main()
