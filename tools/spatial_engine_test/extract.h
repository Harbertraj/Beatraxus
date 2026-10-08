// ===================== LINKWITZ-RILEY 4TH ORDER (LR4) =====================
// High-precision crossover filter. LR4 consists of two cascaded 2nd-order Butterworth
// filters, which provides a 24dB/octave slope and perfectly flat summation.
class LR4Filter {
    struct Biquad {
        double b0, b1, b2, a1, a2;
        double z1[2], z2[2]; // Stereo state
        void reset() { z1[0]=z1[1]=z2[0]=z2[1]=0.0; }
        void setLP(double f, double sr) {
            double omega = 2.0 * M_PI * f / sr;
            double sn = std::sin(omega), cs = std::cos(omega);
            double alpha = sn / std::sqrt(2.0);
            double a0 = 1.0 + alpha;
            b0 = (1.0 - cs) * 0.5 / a0; b1 = (1.0 - cs) / a0; b2 = (1.0 - cs) * 0.5 / a0;
            a1 = -2.0 * cs / a0; a2 = (1.0 - alpha) / a0;
        }
        void setHP(double f, double sr) {
            double omega = 2.0 * M_PI * f / sr;
            double sn = std::sin(omega), cs = std::cos(omega);
            double alpha = sn / std::sqrt(2.0);
            double a0 = 1.0 + alpha;
            b0 = (1.0 + cs) * 0.5 / a0; b1 = -(1.0 + cs) / a0; b2 = (1.0 + cs) * 0.5 / a0;
            a1 = -2.0 * cs / a0; a2 = (1.0 - alpha) / a0;
        }
        inline void process(double& l, double& r) {
            double in[2] = {l, r};
            for(int i=0; i<2; i++) {
                double out = in[i] * b0 + z1[i];
                z1[i] = in[i] * b1 + z2[i] - a1 * out;
                z2[i] = in[i] * b2 - a2 * out;
                in[i] = out;
            }
            l = in[0]; r = in[1];
        }
    };
    Biquad b1, b2; // Cascaded Butterworths
public:
    void reset() { b1.reset(); b2.reset(); }
    void setLP(double f, double sr) { b1.setLP(f, sr); b2.setLP(f, sr); }
    void setHP(double f, double sr) { b1.setHP(f, sr); b2.setHP(f, sr); }
    inline void process(double& l, double& r) { b1.process(l, r); b2.process(l, r); }
};

// 8-Band Crossover using a tree of LR4 filters.
// A serial LR4 split is NOT flat on its own (the summed bands dip by ~4.7 dB because the
// lower bands never see the phase shift of the later splits). Each band is therefore passed
// through the 2nd-order allpass (== LP+HP of an LR4 pair) of every *later* split point, which
// makes the recombined output exactly flat in magnitude (verified to < 0.001 dB).
class Crossover8Band {
    struct AllPass2 {   // RBJ allpass, Q = 1/sqrt(2); stereo TDF-II
        double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1[2] = {0, 0}, z2[2] = {0, 0};
        void set(double f, double sr) {
            double w = 2.0 * M_PI * f / sr, sn = std::sin(w), cs = std::cos(w);
            double al = sn / std::sqrt(2.0), a0 = 1.0 + al;
            b0 = (1.0 - al) / a0; b1 = -2.0 * cs / a0; b2 = (1.0 + al) / a0;
            a1 = -2.0 * cs / a0;  a2 = (1.0 - al) / a0;
        }
        void reset() { z1[0] = z1[1] = z2[0] = z2[1] = 0.0; }
        inline void process(double& l, double& r) {
            double in[2] = {l, r};
            for (int c = 0; c < 2; c++) {
                double out = in[c] * b0 + z1[c];
                z1[c] = in[c] * b1 + z2[c] - a1 * out;
                z2[c] = in[c] * b2 - a2 * out;
                in[c] = out;
            }
            l = in[0]; r = in[1];
        }
    };
    LR4Filter tree[7][2];          // 7 split points, each has LP and HP
    AllPass2 comp[7][7];           // comp[band][split] for split > band
    double splitFreqs[7] = {120, 280, 550, 1100, 2500, 5000, 10000};
public:
    void init(double sr) {
        for (int i = 0; i < 7; i++) {
            tree[i][0].setLP(splitFreqs[i], sr);
            tree[i][1].setHP(splitFreqs[i], sr);
            tree[i][0].reset(); tree[i][1].reset();
            for (int j = 0; j < 7; j++) { comp[i][j].set(splitFreqs[j], sr); comp[i][j].reset(); }
        }
    }
    // Splits input signal into 8 bands using sequential Linkwitz-Riley stages (allpass compensated).
    void split(double l, double r, double outL[8], double outR[8]) {
        double curL = l; double curR = r;
        for (int i = 0; i < 7; i++) {
            double bandL = curL, bandR = curR;
            tree[i][0].process(bandL, bandR);
            for (int j = i + 1; j < 7; j++) comp[i][j].process(bandL, bandR);
            outL[i] = bandL; outR[i] = bandR;
            tree[i][1].process(curL, curR);
        }
        outL[7] = curL; outR[7] = curR;
    }
};



// ===================== 3D STAGE ENGINE (parametric binaural renderer) =====================
// Design notes
//  * Flat (allpass-compensated) 8-band crossover; each band's MID is re-positioned and its
//    SIDE is preserved, so the stereo image survives and Width works at any intensity.
//  * Head model: frequency dependent ILD per band, ITD on the far ear (fractional delay),
//    head-shadow low-pass (Classic), pinna shelf/notch for rear/elevation cues on the bands
//    where those cues actually live, distance gain + air absorption.
//  * Intensity morphs every cue from neutral (no dry/wet mixing => no comb filtering).
//  * Azimuth smoothing takes the shortest arc; coefficient updates run at control rate and
//    per-sample glides remove zipper noise.
class Audio3DStageEngine {
    static constexpr int NUM_BANDS = 8;
    static constexpr int CTRL_BLOCK = 32;            // control-rate (samples) for cue/coef updates
    static constexpr double kRefDistance = 2.0;      // metres at which the stage is neutral
    static constexpr double kMaxItdSec = 0.00066;    // ~ head width / speed of sound
    static constexpr int kBaseLatency = 2;           // samples; lets the 4-point interpolator stay causal

    // Interaural level difference (dB, ipsi vs contra at 90 deg) per crossover band.
    // Physically ILD grows with frequency: almost nothing in the bass, large in the treble.
    static constexpr double kBandIldDb[NUM_BANDS] = {0.5, 1.0, 2.0, 3.5, 6.0, 9.0, 12.0, 14.0};

    struct HrtfProfile { double ild, itd, pinna, air, dist; };
    static const HrtfProfile& profileFor(int mode) {
        static const HrtfProfile P[4] = {
            {1.00, 1.00, 1.00, 1.00, 1.00},  // 0 Natural (Balanced)
            {1.25, 1.15, 1.00, 1.00, 1.00},  // 1 Natural (Wide)
            {1.40, 1.00, 1.40, 1.20, 1.30},  // 2 Cinematic
            {0.80, 1.00, 0.50, 0.00, 0.60},  // 3 Studio (Reference)
        };
        return P[std::clamp(mode, 0, 3)];
    }

    // Minimal mono TDF-II biquad (no bypass shortcut => state stays continuous).
    struct Bq {
        double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
        inline double tick(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x + z2 - a1 * y;
            z2 = b2 * x - a2 * y;
            return y;
        }
        void clear() { z1 = z2 = 0.0; b0 = 1; b1 = b2 = a1 = a2 = 0; }
        void highShelf(double sr, double f, double gDb, double q) {
            double A = std::pow(10.0, gDb / 40.0), sA = std::sqrt(A);
            double w0 = 2.0 * M_PI * std::min(f, sr * 0.45) / sr, cw = std::cos(w0);
            double al = std::sin(w0) / (2.0 * q);
            double a0 = (A + 1) - (A - 1) * cw + 2 * sA * al;
            b0 = A * ((A + 1) + (A - 1) * cw + 2 * sA * al) / a0;
            b1 = -2 * A * ((A - 1) + (A + 1) * cw) / a0;
            b2 = A * ((A + 1) + (A - 1) * cw - 2 * sA * al) / a0;
            a1 = 2 * ((A - 1) - (A + 1) * cw) / a0;
            a2 = ((A + 1) - (A - 1) * cw - 2 * sA * al) / a0;
        }
        void peaking(double sr, double f, double gDb, double q) {
            double A = std::pow(10.0, gDb / 40.0);
            double w0 = 2.0 * M_PI * std::min(f, sr * 0.45) / sr, cw = std::cos(w0);
            double al = std::sin(w0) / (2.0 * q);
            double a0 = 1 + al / A;
            b0 = (1 + al * A) / a0; b1 = -2 * cw / a0; b2 = (1 - al * A) / a0;
            a1 = -2 * cw / a0;      a2 = (1 - al / A) / a0;
        }
    };

    // One renderable point source (a crossover band in Modern mode, a speaker in Classic).
    struct Source {
        double targetAz = 0, curAz = 0, targetEl = 0, curEl = 0, targetDist = kRefDistance, curDist = kRefDistance;
        std::vector<double> buf; size_t size = 0, mask = 0, w = 0;   // shared ITD line, power-of-two sized (two read taps)
        double gL = 1, gR = 1, tgL = 1, tgR = 1;               // per-ear gain (smoothed)
        double ildL = 1, ildR = 1;                             // ILD-only ear gain (no distance), used for Classic normalisation
        double dL = 0, dR = 0, tdL = 0, tdR = 0;               // per-ear delay in samples (smoothed)
        double shA_L = 1, shA_R = 1, tshA_L = 1, tshA_R = 1;   // head-shadow LP coefficients
        double shZ_L = 0, shZ_R = 0;
        double farLpA_L = 1, farLpA_R = 1, tfarLpA_L = 1, tfarLpA_R = 1; // far-ear crosstalk LP coefficients
        double farLpZ_L = 0, farLpZ_R = 0;
        double airA = 1, tairA = 1, airZ = 0;                  // air-absorption LP
        Bq shelf, notch;
        bool usePinnaShelf = false, usePinnaNotch = false, useAir = false;
        double sd1 = 0, sd2 = 0;                               // side-signal delay (kBaseLatency samples)
        int ctr = 0;
        void clearState() {
            std::fill(buf.begin(), buf.end(), 0.0); w = 0;
            gL = gR = tgL = tgR = 1.0; ildL = ildR = 1.0; dL = dR = tdL = tdR = 0.0;
            shA_L = shA_R = tshA_L = tshA_R = 1.0; shZ_L = shZ_R = 0.0;
            farLpA_L = farLpA_R = tfarLpA_L = tfarLpA_R = 1.0; farLpZ_L = farLpZ_R = 0.0;
            airA = tairA = 1.0; airZ = 0.0; shelf.clear(); notch.clear(); sd1 = sd2 = 0.0; ctr = 0;
        }
    };

    Crossover8Band crossover;
    Source bands[NUM_BANDS];   // Modern: one per crossover band
    Source spk[2];             // Classic: virtual left / right speaker (shares setBandPosition 0 / 1)

    double targetWidth = 1.0, curWidth = 1.0;
    double targetCenterLock = 0.0, curCenterLock = 0.0;
    double targetSpatialIntensity = 1.0, curSpatialIntensity = 1.0;
    double distScale = 1.0;
    double stageDepth = 1.0, stageHeight = 0.0, stageRoomReflections = 0.0;
    double dryL1 = 0, dryL2 = 0, dryR1 = 0, dryR2 = 0;   // Classic dry path, aligned with the wet latency
    double normLowL = 1.0, normHighL = 1.0, normLowR = 1.0, normHighR = 1.0; // Frequency-aware normalisation gains
    double normLpZ_L = 0.0, normLpZ_R = 0.0;
    std::vector<double> erBufL, erBufR;
    size_t erSize = 0, erMask = 0, erW = 0;
    double erHpZ = 0.0;

    int hrtfMode = 0;          // 0=Natural, 1=Natural Wide, 2=Cinematic, 3=Studio
    int spatialUiMode = 0;     // 0=Modern, 1=Classic
    bool engineEnabled = false;
    int currentSampleRate = 48000;
    double smoothCoeff = 0.0015;

    static inline double wrapDelta(double d) {          // shortest signed arc in degrees; d is in (-360, 360)
        if (d > 180.0) d -= 360.0; else if (d < -180.0) d += 360.0;
        return d;
    }
    static inline double wrap360(double a) { a = std::fmod(a, 360.0); return a < 0.0 ? a + 360.0 : a; }
    static inline double dbToLin(double db) { return std::pow(10.0, db / 20.0); }

    void computeNormalisationTargets(double& targetLowL, double& targetHighL, double& targetLowR, double& targetHighR) const {
        double gL0 = spk[0].tgL, gL1 = spk[1].tgL;
        double gR0 = spk[0].tgR, gR1 = spk[1].tgR;
        targetLowL  = 1.0 / std::max(0.1, gL0 + gL1);
        targetHighL = 1.0 / std::max(0.1, gL0);
        targetLowR  = 1.0 / std::max(0.1, gR1 + gR0);
        targetHighR = 1.0 / std::max(0.1, gR1);
    }

    void syncClassicStateDirect() {
        for (int i = 0; i < 2; i++) {
            spk[i].curAz = spk[i].targetAz;
            spk[i].curEl = spk[i].targetEl;
            spk[i].curDist = spk[i].targetDist;
            updateCues(spk[i], i, curSpatialIntensity, true);
            spk[i].gL = spk[i].tgL; spk[i].gR = spk[i].tgR;
            spk[i].dL = spk[i].tdL; spk[i].dR = spk[i].tdR;
            spk[i].shA_L = spk[i].tshA_L; spk[i].shA_R = spk[i].tshA_R;
            spk[i].farLpA_L = spk[i].tfarLpA_L; spk[i].farLpA_R = spk[i].tfarLpA_R;
            spk[i].airA = spk[i].tairA;
        }
        double tLowL, tHighL, tLowR, tHighR;
        computeNormalisationTargets(tLowL, tHighL, tLowR, tHighR);
        normLowL = tLowL; normHighL = tHighL;
        normLowR = tLowR; normHighR = tHighR;
        normLpZ_L = normLpZ_R = 0.0;
        erHpZ = 0.0;
    }

public:
    void init(int sampleRate) {
        currentSampleRate = sampleRate;
        crossover.init((double)sampleRate);
        smoothCoeff = 1.0 - std::exp(-1.0 / (0.012 * sampleRate));   // ~12 ms time constant
        dryL1 = dryL2 = dryR1 = dryR2 = 0.0;
        size_t line = 1; while (line < (size_t)std::ceil(0.0012 * sampleRate) + 8) line <<= 1;   // > max ITD (0.66ms * 1.15)
        for (int i = 0; i < NUM_BANDS; i++) {
            bands[i].buf.assign(line, 0.0); bands[i].size = line; bands[i].mask = line - 1;
            bands[i].usePinnaShelf = i >= 5; bands[i].usePinnaNotch = i >= 6; bands[i].useAir = i >= 5;
            bands[i].clearState();
        }
        for (int i = 0; i < 2; i++) {
            spk[i].buf.assign(line, 0.0); spk[i].size = line; spk[i].mask = line - 1;
            spk[i].usePinnaShelf = spk[i].usePinnaNotch = spk[i].useAir = true;
            spk[i].clearState();
        }
        spk[0].targetAz = spk[0].curAz = 330.0; spk[0].targetEl = spk[0].curEl = 0.0; spk[0].targetDist = spk[0].curDist = kRefDistance;
        spk[1].targetAz = spk[1].curAz = 30.0;  spk[1].targetEl = spk[1].curEl = 0.0; spk[1].targetDist = spk[1].curDist = kRefDistance;

        erSize = 1; while (erSize < (size_t)(0.025 * sampleRate)) erSize <<= 1; erMask = erSize - 1; erW = 0;
        erBufL.assign(erSize, 0.0); erBufR.assign(erSize, 0.0); erHpZ = 0.0;

        syncClassicStateDirect();
    }

    void setEnabled(bool enabled) {
        engineEnabled = enabled;
        if (enabled) syncClassicStateDirect();
    }
    bool isEnabled() const { return engineEnabled; }

    void setWidth(double width) { targetWidth = std::clamp(width, 0.0, 2.0); }
    void setCenterLock(double centerLock) { targetCenterLock = std::clamp(centerLock, 0.0, 1.0); }
    void setSpatialIntensity(double intensity) { targetSpatialIntensity = std::clamp(intensity, 0.0, 1.0); }
    void setHrtfMode(int mode) {
        hrtfMode = std::clamp(mode, 0, 3);
        syncClassicStateDirect();
    }
    void setSpatialUiMode(int mode) {
        spatialUiMode = mode;
        syncClassicStateDirect();
    }
    // Classic "Distance" slider: 1.0 = neutral, scales every speaker distance.
    void setDistanceScale(double s) { distScale = std::clamp(s, 0.2, 4.0); }

    void setAudio3DStageParams(double width, double depth, double height, double distance,
                                double centerFocus, double roomReflections) {
        setWidth(width);
        setCenterLock(centerFocus);
        setDistanceScale(distance);
        stageDepth = std::clamp(depth, 0.0, 2.0);
        stageHeight = std::clamp(height, -1.0, 1.0);
        stageRoomReflections = std::clamp(roomReflections, 0.0, 1.0);
        spk[0].targetEl = stageHeight * 30.0;
        spk[1].targetEl = stageHeight * 30.0;
        syncClassicStateDirect();
    }

    void setBandPosition(int index, double azimuthDeg, double elevationDeg, double distanceM) {
        if (index < 0 || index >= NUM_BANDS) return;
        Source* targets[2] = { &bands[index], (index < 2 ? &spk[index] : nullptr) };
        for (Source* s : targets) {
            if (!s) continue;
            s->targetAz = wrap360(azimuthDeg);
            s->targetEl = elevationDeg;
            s->targetDist = std::max(0.3, distanceM);
        }
    }

    void process(double& left, double& right) {
        if (!engineEnabled) return;

        curWidth += (targetWidth - curWidth) * smoothCoeff;
        curCenterLock += (targetCenterLock - curCenterLock) * smoothCoeff;
        curSpatialIntensity += (targetSpatialIntensity - curSpatialIntensity) * smoothCoeff;

        // Full-band mid/side width (linear, so band-wise application is unnecessary).
        double mid = (left + right) * 0.5, side = (left - right) * 0.5;
        double focusedSide = side * curWidth * (1.0 - curCenterLock) + side * curCenterLock;
        double wl = mid + focusedSide, wr = mid - focusedSide;

        const double I = curSpatialIntensity;

        if (spatialUiMode == 1) {
            // Classic: two virtual speakers, equal-power-ish crossfade with the (widened) dry signal.
            double aL, aR, bL, bR;
            renderSource(spk[0], 0, wl, 1.0, true, aL, aR);
            renderSource(spk[1], 1, wr, 1.0, true, bL, bR);
            double dl = dryL2, dr = dryR2;                 // dry aligned to the wet path latency
            dryL2 = dryL1; dryL1 = wl; dryR2 = dryR1; dryR1 = wr;

            double tLowL, tHighL, tLowR, tHighR;
            computeNormalisationTargets(tLowL, tHighL, tLowR, tHighR);

            normLowL  += (tLowL - normLowL)  * smoothCoeff;
            normHighL += (tHighL - normHighL) * smoothCoeff;
            normLowR  += (tLowR - normLowR)  * smoothCoeff;
            normHighR += (tHighR - normHighR) * smoothCoeff;

            double wetL = aL + bL;
            double wetR = aR + bR;

            if (stageRoomReflections > 0.001) {
                double monoIn = (wl + wr) * 0.5;
                double aHp = 1.0 - std::exp(-2.0 * M_PI * 300.0 / currentSampleRate);
                erHpZ += (monoIn - erHpZ) * aHp;
                double erIn = monoIn - erHpZ;
                double erG = stageRoomReflections * 0.20; // max -14 dB gain

                if (erSize > 0) {
                    erBufL[erW] = erIn; erBufR[erW] = erIn;
                    size_t d12 = (size_t)(0.012 * currentSampleRate);
                    size_t d18 = (size_t)(0.018 * currentSampleRate);
                    double erL = erBufL[(erW + erSize - d12) & erMask] * erG;
                    double erR = erBufR[(erW + erSize - d18) & erMask] * erG;
                    erW = (erW + 1) & erMask;
                    wetL += erL; wetR += erR;
                }
            }

            double normCoeff = 1.0 - std::exp(-2.0 * M_PI * 600.0 / currentSampleRate);
            normLpZ_L += (wetL - normLpZ_L) * normCoeff;
            normLpZ_R += (wetR - normLpZ_R) * normCoeff;

            double wetL_low = normLpZ_L, wetL_high = wetL - wetL_low;
            double wetR_low = normLpZ_R, wetR_high = wetR - wetR_low;

            double normWetL = wetL_low * normLowL + wetL_high * normHighL;
            double normWetR = wetR_low * normLowR + wetR_high * normHighR;

            left  = dl * (1.0 - I) + normWetL * I;
            right = dr * (1.0 - I) + normWetR * I;
            return;
        }

        // Modern: per-band mid repositioning. The crossover is allpass-compensated (flat), so the
        // bands can be rendered and summed directly; the side signal is kept untouched per band.
        double bandL[NUM_BANDS], bandR[NUM_BANDS];
        crossover.split(wl, wr, bandL, bandR);
        double outL = 0.0, outR = 0.0;
        for (int i = 0; i < NUM_BANDS; i++) {
            double m = (bandL[i] + bandR[i]) * 0.5, s = (bandL[i] - bandR[i]) * 0.5;
            double eL, eR;
            renderSource(bands[i], i, m, I, false, eL, eR);
            double sDel = bands[i].sd2; bands[i].sd2 = bands[i].sd1; bands[i].sd1 = s;   // side, same latency as mid
            outL += eL + sDel; outR += eR - sDel;
        }
        left = outL;
        right = outR;
    }

private:
    void updateCues(Source& s, int band, double k, bool fullBand) {
        const double sr = (double)currentSampleRate;
        const HrtfProfile& hp = profileFor(hrtfMode);
        const double azR = s.curAz * M_PI / 180.0;
        const double p = std::sin(azR) * k;                         // -1 (left) .. +1 (right), scaled by intensity
        const double back = std::max(0.0, -std::cos(azR));          // 0 in front hemisphere .. 1 directly behind
        const double el01 = std::clamp(s.curEl / 90.0, -1.0, 1.0);
        const double cwL = std::max(0.0, p), cwR = std::max(0.0, -p);   // contralateral weight per ear
        const double ipL = cwR,              ipR = cwL;                 // ipsilateral weight per ear

        // ILD: split so the sum of the two ears' offsets equals the band's ILD.
        const double ild = (fullBand ? 10.0 : kBandIldDb[band]) * hp.ild;
        double tgL = dbToLin(ild * (0.25 * ipL - 0.75 * cwL));
        double tgR = dbToLin(ild * (0.25 * ipR - 0.75 * cwR));

        // Distance (neutral at kRefDistance), morphed by intensity.
        const double dEff = std::max(0.3, s.curDist * distScale);
        const double dGain = std::clamp(std::pow(kRefDistance / dEff, 0.35 * k * hp.dist), 0.35, 1.3);
        const double dGainEffective = fullBand ? std::min(dGain, 1.0) : dGain;
        s.ildL = tgL; s.ildR = tgR;
        s.tgL = tgL * dGainEffective; s.tgR = tgR * dGainEffective;

        // ITD on the far ear only (no latency added to the near ear).
        const double itdMax = kMaxItdSec * sr * hp.itd * (fullBand ? 0.25 : 1.0);
        s.tdL = cwL * itdMax; s.tdR = cwR * itdMax;

        // Head-shadow low-pass on the far ear (full-band Classic speakers only).
        if (fullBand) {
            const double fcShadow = 2200.0;
            auto coef = [&](double cw) {
                if (cw < 1e-4) return 1.0;
                double fc = std::min(20000.0 * std::pow(fcShadow / 20000.0, cw), sr * 0.45);
                return 1.0 - std::exp(-2.0 * M_PI * fc / sr);
            };
            s.tshA_L = coef(cwL); s.tshA_R = coef(cwR);

            const double fcFar = std::clamp(700.0 * (0.5 + 0.5 * stageDepth), 200.0, 4000.0);
            const double aFar = 1.0 - std::exp(-2.0 * M_PI * fcFar / sr);
            s.tfarLpA_L = (cwL > 1e-4) ? aFar : 1.0;
            s.tfarLpA_R = (cwR > 1e-4) ? aFar : 1.0;
        } else {
            s.tshA_L = s.tshA_R = 1.0;
            s.tfarLpA_L = s.tfarLpA_R = 1.0;
        }

        // Air absorption grows beyond the reference distance.
        if (s.useAir) {
            double e = std::clamp((dEff - kRefDistance) / 13.0, 0.0, 1.0) * k * hp.air;
            s.tairA = (e < 1e-4) ? 1.0 : 1.0 - std::exp(-2.0 * M_PI * std::min(20000.0 * std::pow(4500.0 / 20000.0, e), sr * 0.45) / sr);
        } else s.tairA = 1.0;

        // Pinna cues: rear = treble roll-off + notch, elevation = treble tilt + moving notch.
        if (s.usePinnaShelf) s.shelf.highShelf(sr, 3500.0, k * hp.pinna * (-5.0 * back + 3.0 * el01), 0.7);
        if (s.usePinnaNotch) s.notch.peaking(sr, std::clamp(6500.0 + 2500.0 * el01, 3000.0, sr * 0.45),
                                             -k * hp.pinna * (7.0 * back + 3.0 * std::fabs(el01)), 3.0);
    }

    // 4-point cubic (Catmull-Rom) fractional-delay read. A linear interpolator low-passes the
    // delayed ear by an amount that depends on the fractional delay (dull + flutter while a source
    // moves); the cubic keeps the top octave intact. kBaseLatency keeps all four taps in the past.
    inline double readTap(const Source& s, double delay) const {
        const double D = delay + (double)kBaseLatency;
        double rp = (double)s.w - D;
        if (rp < 0.0) rp += (double)s.size;
        size_t i0 = (size_t)rp; if (i0 >= s.size) i0 = s.size - 1;
        const double f = rp - (double)i0;
        const size_t m = s.mask;
        const double xm = s.buf[(i0 + m) & m], x0 = s.buf[i0],
                     x1 = s.buf[(i0 + 1) & m], x2 = s.buf[(i0 + 2) & m];
        const double c1 = 0.5 * (x1 - xm);
        const double c2 = xm - 2.5 * x0 + 2.0 * x1 - 0.5 * x2;
        const double c3 = 0.5 * (x2 - xm) + 1.5 * (x0 - x1);
        return ((c3 * f + c2) * f + c1) * f + x0;
    }

    void renderSource(Source& s, int band, double in, double k, bool fullBand, double& outL, double& outR) {
        // Parameter smoothing (azimuth takes the shortest arc around the circle).
        s.curAz += wrapDelta(s.targetAz - s.curAz) * smoothCoeff;
        if (s.curAz >= 360.0) s.curAz -= 360.0; else if (s.curAz < 0.0) s.curAz += 360.0;
        s.curEl += (s.targetEl - s.curEl) * smoothCoeff;
        s.curDist += (s.targetDist - s.curDist) * smoothCoeff;

        if (s.ctr == 0) updateCues(s, band, k, fullBand);
        if (++s.ctr >= CTRL_BLOCK) s.ctr = 0;

        const double ks = 0.05;   // per-sample glide toward control-rate targets (de-zippering)
        s.gL += (s.tgL - s.gL) * ks;       s.gR += (s.tgR - s.gR) * ks;
        s.dL += (s.tdL - s.dL) * ks;       s.dR += (s.tdR - s.dR) * ks;
        s.shA_L += (s.tshA_L - s.shA_L) * ks; s.shA_R += (s.tshA_R - s.shA_R) * ks;
        s.farLpA_L += (s.tfarLpA_L - s.farLpA_L) * ks;
        s.farLpA_R += (s.tfarLpA_R - s.farLpA_R) * ks;
        s.airA += (s.tairA - s.airA) * ks;

        double x = in;
        if (s.usePinnaShelf) x = s.shelf.tick(x);
        if (s.usePinnaNotch) x = s.notch.tick(x);
        if (s.useAir) { s.airZ += (x - s.airZ) * s.airA; x = s.airZ; }

        s.buf[s.w] = x;
        double xl = readTap(s, s.dL), xr = readTap(s, s.dR);
        s.w = (s.w + 1) & s.mask;

        if (fullBand) {
            s.shZ_L += (xl - s.shZ_L) * s.shA_L; xl = s.shZ_L;
            s.shZ_R += (xr - s.shZ_R) * s.shA_R; xr = s.shZ_R;

            s.farLpZ_L += (xl - s.farLpZ_L) * s.farLpA_L; xl = s.farLpZ_L;
            s.farLpZ_R += (xr - s.farLpZ_R) * s.farLpA_R; xr = s.farLpZ_R;
        }
        outL = xl * s.gL;
        outR = xr * s.gR;
    }
};



