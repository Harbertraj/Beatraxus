#include <cmath>
#include <vector>
#include <algorithm>
#include <cstdio>
#include <cstdlib>

#include "extract.h"

static int fails = 0;

static double rms(const std::vector<double>& v, int start, int end) {
    double sum = 0.0;
    for (int i = start; i < end; i++) sum += v[i] * v[i];
    return std::sqrt(sum / (end - start));
}

static void settle(Audio3DStageEngine& engine, int samples = 48000) {
    for (int i = 0; i < samples; i++) {
        double l = 0.0, r = 0.0;
        engine.process(l, r);
    }
}

int main() {
    const int SR = 48000;
    const double testFreqs[] = {50.0, 100.0, 250.0, 500.0, 1000.0, 1500.0, 3000.0, 6000.0, 10000.0, 20000.0};
    const int numFreqs = sizeof(testFreqs) / sizeof(testFreqs[0]);

    const double hrtfModes[] = {0.0, 1.0, 2.0, 3.0};
    const double distScales[] = {0.5, 1.0, 2.0};
    const double spkAngles[] = {15.0, 30.0, 60.0};

    printf("======================================================================\n");
    printf("CLASSIC SPATIAL MODE MONO TRANSPARENCY TEST\n");
    printf("======================================================================\n\n");

    printf("%-5s | %-6s | %-6s | ", "HRTF", "Dist", "Angle");
    for (int i = 0; i < numFreqs; i++) {
        printf("%5.0fHz ", testFreqs[i]);
    }
    printf("| %-8s\n", "Status");
    printf("------+--------+--------+-------------------------------------------------------------+----------\n");

    double maxDevDefault = 0.0;
    double maxDevAll = 0.0;

    for (int mIdx = 0; mIdx < 4; mIdx++) {
        int mode = (int)hrtfModes[mIdx];
        for (int dIdx = 0; dIdx < 3; dIdx++) {
            double distScale = distScales[dIdx];
            for (int aIdx = 0; aIdx < 3; aIdx++) {
                double spkAngle = spkAngles[aIdx];

                Audio3DStageEngine engine;
                engine.init(SR);
                engine.setEnabled(true);
                engine.setSpatialUiMode(1); // Classic mode
                engine.setHrtfMode(mode);
                engine.setDistanceScale(distScale);
                engine.setSpatialIntensity(1.0); // 100% wet
                engine.setAudio3DStageParams(1.0, 1.0, 0.0, distScale, 0.0, 0.0);

                engine.setBandPosition(0, 360.0 - spkAngle, 0.0, 2.0);
                engine.setBandPosition(1, spkAngle, 0.0, 2.0);

                settle(engine, SR / 2);

                printf("  %1d   |   %3.1f  |  %2.0f deg | ", mode, distScale, spkAngle);

                bool rowPass = true;
                double targetTolerance = (mode == 0 && distScale == 1.0 && spkAngle == 30.0) ? 1.5 : 2.5;

                for (int fIdx = 0; fIdx < numFreqs; fIdx++) {
                    double fq = testFreqs[fIdx];
                    int N = SR;
                    std::vector<double> in(N), outL(N), outR(N);

                    for (int i = 0; i < N; i++) {
                        double x = 0.4 * std::sin(2.0 * M_PI * fq * i / SR);
                        in[i] = x;
                        double l = x, r = x;
                        engine.process(l, r);
                        outL[i] = l;
                        outR[i] = r;
                    }

                    double inRms = rms(in, N / 2, N);
                    double outRmsL = rms(outL, N / 2, N);
                    double gainDbL = 20.0 * std::log10(outRmsL / inRms);
                    double devL = std::abs(gainDbL);

                    if (mode == 0 && distScale == 1.0 && spkAngle == 30.0) {
                        if (devL > maxDevDefault) maxDevDefault = devL;
                    }
                    if (devL > maxDevAll) maxDevAll = devL;

                    printf("%+5.2f ", gainDbL);

                    if (devL > targetTolerance) {
                        rowPass = false;
                    }
                }

                if (!rowPass) {
                    fails++;
                    printf("| FAIL (exceeds %0.1fdB)\n", targetTolerance);
                } else {
                    printf("| PASS\n");
                }
            }
        }
    }

    printf("======================================================================\n");
    printf("Max Deviation at Defaults (HRTF 0, Dist 1.0, 30 deg): %+.3f dB (Target <= 1.5 dB)\n", maxDevDefault);
    printf("Max Deviation Across All Combinations:               %+.3f dB (Target <= 2.5 dB)\n", maxDevAll);
    printf("======================================================================\n\n");

    if (maxDevDefault > 1.5) {
        printf("FAIL: Default max deviation (%0.3f dB) exceeded 1.5 dB limit!\n", maxDevDefault);
        fails++;
    }
    if (maxDevAll > 2.5) {
        printf("FAIL: Overall max deviation (%0.3f dB) exceeded 2.5 dB limit!\n", maxDevAll);
        fails++;
    }

    if (fails == 0) {
        printf("ALL TESTS PASSED SUCCESSFULLY!\n");
        return 0;
    } else {
        printf("TEST SUITE FAILED WITH %d FAILURES!\n", fails);
        return 1;
    }
}
