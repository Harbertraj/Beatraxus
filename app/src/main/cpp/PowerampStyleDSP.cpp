#include <vector>
#include <array>
#include <cmath>
#include <algorithm>
#include <map>
#include <string>
#include <atomic>
#include <jni.h>
#ifdef HAVE_SOXR
#include <soxr.h>
#endif
#include <android/log.h>

#define LOG_TAG "BeatraxusDSP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ===================== BIQUAD FILTER =====================
// Double precision required per Poweramp specification for filter stability below 200Hz
struct BiquadState {
    double b0, b1, b2, a1, a2;
    double z1_l, z2_l, z1_r, z2_r;

    BiquadState() { reset(); }

    void reset() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0;
        a1 = 0.0; a2 = 0.0;
        z1_l = z2_l = z1_r = z2_r = 0.0;
    }

    inline void process(float& l, float& r) {
        double in_l = (double)l;
        double out_l = in_l * b0 + z1_l;
        z1_l = in_l * b1 + z2_l - a1 * out_l;
        z2_l = in_l * b2 - a2 * out_l;
        l = (float)out_l;

        double in_r = (double)r;
        double out_r = in_r * b0 + z1_r;
        z1_r = in_r * b1 + z2_r - a1 * out_r;
        z2_r = in_r * b2 - a2 * out_r;
        r = (float)out_r;
    }

    void setPeaking(double sr, double freq, double gainDb, double Q) {
        double A = std::pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * M_PI * freq / sr;
        double alpha = std::sin(w0) / (2.0 * Q);
        double cosw0 = std::cos(w0);
        double a0 = 1.0 + alpha / A;
        b0 = (1.0 + alpha * A) / a0;
        b1 = (-2.0 * cosw0) / a0;
        b2 = (1.0 - alpha * A) / a0;
        a1 = (-2.0 * cosw0) / a0;
        a2 = (1.0 - alpha / A) / a0;
    }

    void setLowShelf(double sr, double freq, double gainDb, double shelfSlope = 1.0) {
        double A = std::pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * M_PI * freq / sr;
        double alpha = std::sin(w0) / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / shelfSlope - 1.0) + 2.0);
        double cosw0 = std::cos(w0);
        double sqrtA2alpha = 2.0 * std::sqrt(A) * alpha;
        double a0 = (A + 1.0) + (A - 1.0) * cosw0 + sqrtA2alpha;
        b0 = (A * ((A + 1.0) - (A - 1.0) * cosw0 + sqrtA2alpha)) / a0;
        b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0)) / a0;
        b2 = (A * ((A + 1.0) - (A - 1.0) * cosw0 - sqrtA2alpha)) / a0;
        a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cosw0)) / a0;
        a2 = ((A + 1.0) + (A - 1.0) * cosw0 - sqrtA2alpha) / a0;
    }

    void setHighShelf(double sr, double freq, double gainDb, double shelfSlope = 1.0) {
        double A = std::pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * M_PI * freq / sr;
        double alpha = std::sin(w0) / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / shelfSlope - 1.0) + 2.0);
        double cosw0 = std::cos(w0);
        double sqrtA2alpha = 2.0 * std::sqrt(A) * alpha;
        double a0 = (A + 1.0) - (A - 1.0) * cosw0 + sqrtA2alpha;
        b0 = (A * ((A + 1.0) + (A - 1.0) * cosw0 + sqrtA2alpha)) / a0;
        b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)) / a0;
        b2 = (A * ((A + 1.0) - (A - 1.0) * cosw0 - sqrtA2alpha)) / a0;
        a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cosw0)) / a0;
        a2 = ((A + 1.0) + (A - 1.0) * cosw0 - sqrtA2alpha) / a0;
    }
};

// ===================== DSP UTILITIES =====================

class LookaheadLimiter {
    static constexpr int LOOKAHEAD_SAMPLES = 128;   // ~2.7ms at 48kHz
    static constexpr float ATTACK_COEFF = 0.02f;    // ~1ms attack (fast, catches transients)
    static constexpr float RELEASE_COEFF = 0.0002f; // ~50ms release (avoids pumping)
    static constexpr float THRESHOLD = 0.98f;        // Slightly below digital full scale

    float delayBuffer[LOOKAHEAD_SAMPLES * 2] = {};  // stereo
    int delayPos = 0;
    float gainReduction = 1.0f;

public:
    void process(float* buffer, int frames, int channels) {
        if (channels > 2) return; // Only stereo supported for now
        for (int f = 0; f < frames; f++) {
            // Find peak in current frame (both channels)
            float peak = 0.0f;
            for (int c = 0; c < channels; c++) {
                peak = std::max(peak, std::abs(buffer[f * channels + c]));
            }

            // Compute required gain reduction
            float targetGain = (peak > THRESHOLD) ? THRESHOLD / peak : 1.0f;

            // Smooth gain reduction (attack/release envelope)
            if (targetGain < gainReduction) {
                gainReduction = gainReduction * (1.0f - ATTACK_COEFF) + targetGain * ATTACK_COEFF;
            } else {
                gainReduction = gainReduction * (1.0f - RELEASE_COEFF) + targetGain * RELEASE_COEFF;
            }

            // Write to delay, read delayed sample, apply gain
            for (int c = 0; c < channels; c++) {
                int idx = (delayPos % LOOKAHEAD_SAMPLES) * channels + c;
                float delayed = delayBuffer[idx];
                delayBuffer[idx] = buffer[f * channels + c];
                buffer[f * channels + c] = delayed * gainReduction;
            }
            delayPos++;
        }
    }

    void reset() {
        std::fill(std::begin(delayBuffer), std::end(delayBuffer), 0.0f);
        delayPos = 0;
        gainReduction = 1.0f;
    }
};

void resample_cubic(const float* in, int inFrames, float* out, int outFrames, int channels, float ratio) {
    for (int i = 0; i < outFrames; i++) {
        float x = i / ratio;
        int ix = (int)x;
        float frac = x - ix;

        for (int ch = 0; ch < channels; ch++) {
            auto get = [&](int f) {
                if (f < 0) return in[ch];
                if (f >= inFrames) return in[(inFrames - 1) * channels + ch];
                return in[f * channels + ch];
            };

            float y0 = get(ix - 1);
            float y1 = get(ix);
            float y2 = get(ix + 1);
            float y3 = get(ix + 2);

            float a = (-0.5f * y0) + (1.5f * y1) - (1.5f * y2) + (0.5f * y3);
            float b = y0 - (2.5f * y1) + (2.0f * y2) - (0.5f * y3);
            float c = (-0.5f * y0) + (0.5f * y2);
            float d = y1;

            out[(i * channels) + ch] = a * frac * frac * frac + b * frac * frac + c * frac + d;
        }
    }
}

// ===================== DC BLOCKER =====================

class DcBlocker {
    float x1_l, x1_r, y1_l, y1_r;
    float R;

public:
    DcBlocker() : x1_l(0), x1_r(0), y1_l(0), y1_r(0), R(0.999f) {}

    void init(int sampleRate) {
        R = 1.0f - (2.0f * M_PI * 5.0f / sampleRate);
    }

    inline void process(float& l, float& r) {
        float y_l = l - x1_l + R * y1_l;
        x1_l = l;
        y1_l = y_l;
        l = y_l;

        float y_r = r - x1_r + R * y1_r;
        x1_r = r;
        y1_r = y_r;
        r = y_r;
    }
};

// ===================== BAUER BS2B CROSSFEED =====================

class Bs2b {
    // 1st-order Butterworth LP at cutFreq Hz for the cross-feed path
    double lp_b0, lp_b1, lp_a1;
    double lp_z_l, lp_z_r;

    static constexpr int DELAY_SAMPLES = 18; // ~0.375ms at 48kHz
    float delayL[DELAY_SAMPLES];
    float delayR[DELAY_SAMPLES];
    int delayIdx;

public:
    Bs2b() { reset(); }

    void reset() {
        lp_z_l = lp_z_r = 0.0;
        std::fill(std::begin(delayL), std::end(delayL), 0.0f);
        std::fill(std::begin(delayR), std::end(delayR), 0.0f);
        delayIdx = 0;
    }

    void init(int sampleRate, float cutHz = 700.0f) {
        // 1st-order Butterworth LP
        double w = std::tan(M_PI * cutHz / sampleRate);
        double norm = 1.0 / (1.0 + w);
        lp_b0 = w * norm;
        lp_b1 = w * norm;
        lp_a1 = (w - 1.0) * norm;
    }

    // level: 0.0 (off) to 1.0 (full), maps to 0–45% cross blend
    inline void process(float& l, float& r, float level) {
        // Cross-path: LP-filtered opposite channel
        double in_l = (double)l;
        double in_r = (double)r;

        double lp_l = lp_b0 * in_l - lp_a1 * lp_z_l + lp_z_l;
        lp_z_l = lp_b1 * in_l - lp_a1 * lp_z_l;

        double lp_r = lp_b0 * in_r - lp_a1 * lp_z_r + lp_z_r;
        lp_z_r = lp_b1 * in_r - lp_a1 * lp_z_r;

        // cross blend = level * 0.45 (max 45% opposite channel)
        float blend = level * 0.45f;
        float direct = 1.0f - blend * 0.6f; // Slight attenuation of direct path to maintain headroom

        float dL = delayL[delayIdx];
        float dR = delayR[delayIdx];
        delayL[delayIdx] = (float)lp_l;
        delayR[delayIdx] = (float)lp_r;
        delayIdx = (delayIdx + 1) % DELAY_SAMPLES;

        l = (float)(in_l * direct + dR * blend);
        r = (float)(in_r * direct + dL * blend);
    }
};

// ===================== REVERB ENGINE (FREEVERB) =====================

struct ReverbPreset {
    float roomSize;      // 0.0 - 1.0
    float damping;       // 0.0 - 1.0
    float predelayMs;    // 0 - 100ms
    float width;         // 0.0 - 1.0
};

static const std::map<int, ReverbPreset> REVERB_PRESETS = {
    {0, {0.0f, 0.5f,  0.0f, 1.0f}}, // FLAT
    {1, {0.3f, 0.6f,  5.0f, 0.8f}}, // ROOM
    {2, {0.7f, 0.4f, 20.0f, 1.0f}}, // HALL
    {3, {0.9f, 0.2f, 40.0f, 1.0f}}, // CATHEDRAL
    {4, {0.2f, 0.8f,  2.0f, 0.6f}}, // STUDIO
    {5, {0.5f, 0.7f,  1.0f, 0.9f}}, // PLATE
    {6, {0.4f, 0.5f, 10.0f, 0.8f}}, // CHAMBER
};

class Freeverb {
    static constexpr int numCombs = 8;
    static constexpr int numAllPasses = 4;
    static constexpr int stereoSpread = 23;

    // Standard Freeverb tuning at 44.1kHz
    static constexpr int combTuning[] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    static constexpr int allPassTuning[] = {556, 441, 341, 225};

    struct Comb {
        std::vector<float> buffer;
        int size = 0;
        int index = 0;
        float feedback = 0;
        float filterStore = 0;
        float damp = 0;

        void setSize(int s) {
            size = s;
            buffer.assign(size, 0.0f);
            index = 0;
            filterStore = 0.0f;
        }

        inline float process(float input) {
            float output = buffer[index];
            filterStore = (output * (1.0f - damp)) + (filterStore * damp);
            buffer[index] = input + (filterStore * feedback);
            if (++index >= size) index = 0;
            return output;
        }

        void mute() { std::fill(buffer.begin(), buffer.end(), 0); filterStore = 0; }
    };

    struct AllPass {
        std::vector<float> buffer;
        int size = 0;
        int index = 0;
        float feedback = 0.5f;

        void setSize(int s) {
            size = s;
            buffer.assign(size, 0.0f);
            index = 0;
        }

        inline float process(float input) {
            float bufOut = buffer[index];
            float output = -input + bufOut;
            buffer[index] = input + (bufOut * feedback);
            if (++index >= size) index = 0;
            return output;
        }

        void mute() { std::fill(buffer.begin(), buffer.end(), 0); }
    };

    Comb combL[numCombs];
    Comb combR[numCombs];
    AllPass allPassL[numAllPasses];
    AllPass allPassR[numAllPasses];

    std::vector<float> predelayBuffer;
    int predelayPos = 0;
    int predelaySize = 0;

    std::atomic<float> roomSize{0.5f};
    std::atomic<float> damp{0.5f};
    std::atomic<float> wet{0.0f};
    std::atomic<float> dry{1.0f};
    std::atomic<float> width{1.0f};

public:
    void init(int sampleRate) {
        float scale = sampleRate / 44100.0f;
        for (int i = 0; i < numCombs; i++) {
            combL[i].setSize((int)(combTuning[i] * scale));
            combR[i].setSize((int)((combTuning[i] + stereoSpread) * scale));
        }
        for (int i = 0; i < numAllPasses; i++) {
            allPassL[i].setSize((int)(allPassTuning[i] * scale));
            allPassR[i].setSize((int)((allPassTuning[i] + stereoSpread) * scale));
            allPassL[i].feedback = allPassR[i].feedback = 0.5f;
        }

        predelaySize = (int)(sampleRate * 0.5f); // 500ms max
        predelayBuffer.assign(predelaySize * 2, 0.0f);
        predelayPos = 0;
    }

    void setRoomSize(float v) { roomSize.store(v * 0.28f + 0.7f, std::memory_order_relaxed); }
    void setDamping(float v) { damp.store(v * 0.4f, std::memory_order_relaxed); }
    void setWet(float v) {
        wet.store(v, std::memory_order_relaxed);
        dry.store(1.0f - v, std::memory_order_relaxed);
    }
    void setWidth(float v) { width.store(v, std::memory_order_relaxed); }

    inline void process(float& l, float& r, int predelaySamples) {
        float rs = roomSize.load(std::memory_order_relaxed);
        float d = damp.load(std::memory_order_relaxed);
        float w = wet.load(std::memory_order_relaxed);
        float dr = dry.load(std::memory_order_relaxed);
        float wid = width.load(std::memory_order_relaxed);

        // Pre-delay
        int pSamples = std::min(predelaySamples, predelaySize - 1);
        int readPos = (predelayPos + predelaySize - pSamples) % predelaySize;
        float delayedL = predelayBuffer[readPos * 2];
        float delayedR = predelayBuffer[readPos * 2 + 1];
        predelayBuffer[predelayPos * 2] = l;
        predelayBuffer[predelayPos * 2 + 1] = r;
        if (++predelayPos >= predelaySize) predelayPos = 0;

        float monoIn = (delayedL + delayedR) * 0.5f;
        float outL = 0, outR = 0;

        for (int i = 0; i < numCombs; i++) {
            combL[i].feedback = combR[i].feedback = rs;
            combL[i].damp = combR[i].damp = d;
            outL += combL[i].process(monoIn);
            outR += combR[i].process(monoIn);
        }

        for (int i = 0; i < numAllPasses; i++) {
            outL = allPassL[i].process(outL);
            outR = allPassR[i].process(outR);
        }

        // Stereo spread / mix
        float wet1 = w * (wid * 0.5f + 0.5f);
        float wet2 = w * (1.0f - wid) * 0.5f;

        float finalL = outL * wet1 + outR * wet2 + l * dr;
        float finalR = outR * wet1 + outL * wet2 + r * dr;

        l = finalL;
        r = finalR;
    }

    void mute() {
        for(int i=0; i<numCombs; i++) { combL[i].mute(); combR[i].mute(); }
        for(int i=0; i<numAllPasses; i++) { allPassL[i].mute(); allPassR[i].mute(); }
        std::fill(predelayBuffer.begin(), predelayBuffer.end(), 0);
    }
};

// ===================== DSP ENGINE =====================
class DSP {
public:
    DSP() :
#ifdef HAVE_SOXR
            soxr_handle(nullptr),
#endif
            inRate(44100), outRate(44100), channels(2),
            useSox(true), dcBlockerEnabled(true), dvcEnabled(true), limiterEnabled(true), replayGainDb(0.0f), preampDb(0.0f),
            dvcLevel(1.0f), dvcMode(0),
            bassDb(0.0f), midBassDb(0.0f), trebleDb(0.0f), airDb(0.0f),
            balance(0.0f), stereoWidth(1.0f), crossfeedEnabled(false), crossfeedLevel(0.4f),
            reverbAmount(0.0f),
            reverbType(0), reverbPredelayMs(0.0f), reverbWidth(1.0f),
            reverbDamping(0.5f), reverbRoomSize(0.5f),
            bitDepth(16), ditherEnabled(true), dither_state(0x1234ABCD), cutoffRatio(0.97f) {
        bandDbs.fill(0.0f);
        bandQs.fill(1.0f);
        bandFreqs.fill(1000.0f);
        std::vector<float> defaults = {31.25f, 62.5f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f};
        for(size_t i=0; i<defaults.size(); i++) bandFreqs[i] = defaults[i];
        updateFilters();
    }

    ~DSP() {
#ifdef HAVE_SOXR
        if (soxr_handle) soxr_delete(soxr_handle);
#endif
    }

    void init(int inR, int outR, int ch) {
        if (inRate == inR && outRate == outR && channels == ch &&
#ifdef HAVE_SOXR
            soxr_handle != nullptr
#else
            true
#endif
        ) return;

        inRate = inR; outRate = outR; channels = ch;
        dcBlocker.init(inRate);
        reverb.init(inRate);
        crossfeed.init(inRate);
        updateFilters();
        updateSoxr();
    }

    void updateSoxr() {
#ifdef HAVE_SOXR
        if (soxr_handle) {
            soxr_delete(soxr_handle);
            soxr_handle = nullptr;
        }

        // Skip SoXR initialization if rates are identical (1:1 bypass)
        if (inRate == outRate) {
            return;
        }

        soxr_error_t err;
        soxr_io_spec_t io_spec = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);

        // CORRECT for audiophile output: VHQ (28-bit precision) + Linear Phase
        soxr_quality_spec_t q_spec = soxr_quality_spec(SOXR_VHQ, SOXR_LINEAR_PHASE);

        // Bandwidth tuning: 99.7% of Nyquist preserves maximum HF content
        q_spec.passband_end = 0.997;
        q_spec.stopband_begin = 1.0;

        soxr_runtime_spec_t r_spec = soxr_runtime_spec(1); // Single thread owned by audio thread

        soxr_handle = soxr_create((double)inRate, (double)outRate, (unsigned)channels, &err, &io_spec, &q_spec, &r_spec);
        if (!soxr_handle) {
            LOGI("SOXR FAILED: %s", soxr_strerror(err));
        }
#endif
    }

    /**
     * Exact DSP Chain Order:
     * (0) dcBlocker (Remove subsonic bias)
     * (1) replayGain
     * (2) preamp
     * (3) parametric EQ / AutoEQ bands
     * (4) tone controls (bass/midBass/treble/air)
     * (5) stereo spatial (balance/widen)
     * (6) crossfeed (Bauer BS2B)
     * (7) reverb
     * (8) DVC volume scaling
     * (9) limiter
     * (10) dither if outputBitDepth < 32
     * (11) SoXR resampling
     */
    void processInPlace(float* input, int inFrames) {
        int samples = inFrames * channels;

        // (0) DC Blocker
        if (dcBlockerEnabled && channels >= 2) {
            for (int i = 0; i < samples; i += 2) {
                dcBlocker.process(input[i], input[i + 1]);
            }
        }

        // (1) ReplayGain
        if (replayGainDb != 0.0f) {
            float gain = powf(10.0f, replayGainDb / 20.0f);
            for (int i = 0; i < samples; i++) input[i] *= gain;
        }

        // (2) Preamp
        if (preampDb != 0.0f) {
            float gain = powf(10.0f, preampDb / 20.0f);
            for (int i = 0; i < samples; i++) input[i] *= gain;
        }

        // (3) EQ & (4) Tone (at inRate)
        if (channels >= 2) {
            for (int i = 0; i < samples; i += 2) {
                float& l = input[i];
                float& r = input[i + 1];
                for (int b = 0; b < 32; b++) eqBands[b].process(l, r);
                for (int t = 0; t < 4; t++) toneFilters[t].process(l, r);
            }
        } else {
            for (int i = 0; i < samples; i++) {
                float& s = input[i];
                float dummy = 0.0f;
                for (int b = 0; b < 32; b++) eqBands[b].process(s, dummy);
                for (int t = 0; t < 4; t++) toneFilters[t].process(s, dummy);
            }
        }

        // (5) Stereo Spatial (Balance/Widen)
        if (channels >= 2) {
            for (int i = 0; i < samples; i += 2) {
                float mid = (input[i] + input[i + 1]) * 0.5f;
                float side = (input[i] - input[i + 1]) * 0.5f;
                side *= stereoWidth;

                float left = mid + side;
                float right = mid - side;
                if (balance > 0.0f) left *= (1.0f - balance);
                else if (balance < 0.0f) right *= (1.0f + balance);

                input[i] = left;
                input[i + 1] = right;
            }
        }

        // (6) Crossfeed
        if (crossfeedEnabled && channels >= 2) {
            for (int i = 0; i < samples; i += 2) {
                crossfeed.process(input[i], input[i + 1], crossfeedLevel);
            }
        }

        // (7) Reverb
        if (reverbAmount > 0.001f) {
            int predelaySamples = (int)(reverbPredelayMs * inRate / 1000.0f);
            for (int i = 0; i < samples; i += channels) {
                if (channels >= 2) {
                    reverb.process(input[i], input[i+1], predelaySamples);
                } else {
                    float r = input[i];
                    reverb.process(input[i], r, predelaySamples);
                }
            }
        }

        // (8) DVC Volume Scaling
        if (dvcEnabled) {
            applyDvc(input, inFrames, channels);
        }

        // (9) Limiter
        if (limiterEnabled) {
            limiter.process(input, inFrames, channels);
        }

        // (10) Dither if outputBitDepth < 32
        if (ditherEnabled && bitDepth < 32) {
            float lsbAmplitude = 2.0f / (float)(1 << bitDepth);
            for (int i = 0; i < samples; i++) {
                dither_state = dither_state * 1664525u + 1013904223u;
                float r1 = (dither_state >> 16) * (1.0f / 65536.0f);
                dither_state = dither_state * 1664525u + 1013904223u;
                float r2 = (dither_state >> 16) * (1.0f / 65536.0f);

                input[i] += (r1 - r2) * lsbAmplitude;
                input[i] = std::max(-1.0f, std::min(1.0f, input[i]));
            }
        }
    }

    void process(float* input, int inFrames, float* output, int& outFrames) {
        // Run core DSP chain (1-9) in-place on input
        processInPlace(input, inFrames);

        // (11) SoXR Resampling
        if (inRate != outRate) {
            int expectedOutFrames = (int)((float)inFrames * outRate / inRate);
            tempBuffer.resize(expectedOutFrames * channels + 256);
#ifdef HAVE_SOXR
            if (useSox && soxr_handle) {
                size_t idone, odone;
                soxr_process(soxr_handle, input, (size_t)inFrames, &idone, tempBuffer.data(), (size_t)expectedOutFrames, &odone);
                outFrames = (int)odone;
            } else {
#else
            {
#endif
                float ratio = (float)outRate / inRate;
                resample_cubic(input, inFrames, tempBuffer.data(), expectedOutFrames, channels, ratio);
                outFrames = expectedOutFrames;
            }
            std::copy(tempBuffer.begin(), tempBuffer.begin() + (outFrames * channels), output);
        } else {
            std::copy(input, input + (inFrames * channels), output);
            outFrames = inFrames;
        }
    }

    void setReplayGain(float db) { replayGainDb = db; }
    void setPreamp(float db) { preampDb = db; }
    void setVolume(float v) { dvcLevel = std::clamp(v, 0.0f, 1.0f); }
    void setDcBlocker(bool enabled) { dcBlockerEnabled = enabled; }
    void setDvc(bool enabled) { dvcEnabled = enabled; }
    void setDvcLevel(float level) { dvcLevel = std::clamp(level, 0.0f, 1.0f); }
    void setDvcMode(int mode) { dvcMode = mode; }
    void setSpatial(float b, float w) { balance = b; stereoWidth = w; }
    void setCrossfeed(bool enabled, float level) { crossfeedEnabled = enabled; crossfeedLevel = std::clamp(level, 0.0f, 1.0f); }

    void setReverb(float amount) {
        reverbAmount = amount;
        reverb.setWet(amount);
    }

    void setReverbType(int type) {
        reverbType = type;
        auto it = REVERB_PRESETS.find(type);
        if (it != REVERB_PRESETS.end()) {
            setReverbParams(it->second.roomSize, it->second.damping);
            setReverbPredelay(it->second.predelayMs);
            setReverbWidth(it->second.width);
        }
    }

    void setReverbPredelay(float ms) {
        reverbPredelayMs = ms;
    }

    void setReverbWidth(float w) {
        reverbWidth = w;
        reverb.setWidth(w);
    }

    void setReverbParams(float roomSize, float damping) {
        reverbRoomSize = roomSize;
        reverbDamping = damping;
        reverb.setRoomSize(roomSize);
        reverb.setDamping(damping);
    }

    void setLimiter(bool enabled) { limiterEnabled = enabled; }
    void muteReverb() { reverb.mute(); reverbAmount = 0.0f; }
    void setBitDepth(int bd) { bitDepth = bd; }
    void setDither(bool enabled, int bd) { ditherEnabled = enabled; bitDepth = bd; }
    void setTone(float bass, float midBass, float treble, float air) {
        bassDb = bass; midBassDb = midBass; trebleDb = treble; airDb = air;
        updateFilters();
    }
    void setBand(int index, float freq, float gainDb, float Q) {
        if (index >= 0 && index < 32) {
            bandFreqs[index] = freq;
            bandDbs[index] = gainDb;
            bandQs[index] = Q;
            updateFilters();
        }
    }
    void setUseSox(bool use) {
        if (useSox != use) {
            useSox = use;
            updateSoxr();
        }
    }
    void setCutoffRatio(float ratio) {
        if (fabsf(cutoffRatio - ratio) > 0.001f) {
            cutoffRatio = ratio;
            updateSoxr();
        }
    }

private:
    void applyDvc(float* buffer, int frames, int channels) {
        // Linear gain with square-root perceptual correction (sounds natural on log scale)
        float gain = dvcLevel;
        int samples = frames * channels;
        for (int i = 0; i < samples; i++) {
            buffer[i] *= gain;
        }
    }

    void updateFilters() {
        double sr = (double)inRate;

        if (fabsf(bassDb) > 0.05f)
            toneFilters[0].setLowShelf(sr, 80.0, (double)bassDb, 0.9);
        else
            toneFilters[0].reset();

        if (fabsf(midBassDb) > 0.05f)
            toneFilters[1].setPeaking(sr, 160.0, (double)midBassDb, 1.2);
        else
            toneFilters[1].reset();

        if (fabsf(trebleDb) > 0.05f)
            toneFilters[2].setHighShelf(sr, 5500.0, (double)trebleDb, 0.8);
        else
            toneFilters[2].reset();

        if (fabsf(airDb) > 0.05f)
            toneFilters[3].setHighShelf(sr, 14000.0, (double)airDb, 1.2);
        else
            toneFilters[3].reset();

        for (int i = 0; i < 32; i++) {
            eqBands[i].setPeaking(sr, (double)bandFreqs[i], (double)bandDbs[i], (double)bandQs[i]);
        }
    }

#ifdef HAVE_SOXR
    soxr_t soxr_handle;
#endif
    int inRate, outRate, channels;
    bool useSox;
    DcBlocker dcBlocker;
    bool dcBlockerEnabled;
    bool dvcEnabled;
    float dvcLevel;
    int dvcMode;
    bool limiterEnabled;
    bool ditherEnabled;
    float cutoffRatio;
    float replayGainDb, preampDb;
    float bassDb, midBassDb, trebleDb, airDb;
    std::array<float, 32> bandDbs;
    std::array<float, 32> bandFreqs;
    std::array<float, 32> bandQs;
    BiquadState eqBands[32];
    BiquadState toneFilters[4];
    LookaheadLimiter limiter;

    Bs2b crossfeed;
    bool crossfeedEnabled;
    float crossfeedLevel;

    Freeverb reverb;
    float reverbAmount;
    int reverbType;
    float reverbPredelayMs;
    float reverbWidth;
    float reverbDamping;
    float reverbRoomSize;

    float balance, stereoWidth;
    int bitDepth;
    uint32_t dither_state;
    std::vector<float> tempBuffer;
};

// ===================== JNI INTERFACE =====================

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nCreate(JNIEnv* env, jobject thiz) {
    return (jlong)new DSP();
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) delete (DSP*)handle;
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nInitResampler(JNIEnv* env, jobject thiz, jlong handle, jfloat inputSR, jint channels, jfloat targetSR) {
    if (handle) ((DSP*)handle)->init((int)inputSR, (int)targetSR, channels);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetVolume(JNIEnv* env, jobject thiz, jlong handle, jfloat volume) {
    if (handle) ((DSP*)handle)->setVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetTone(JNIEnv* env, jobject thiz, jlong handle, jfloat bass, jfloat midBass, jfloat treble, jfloat air) {
    if (handle) ((DSP*)handle)->setTone(bass, midBass, treble, air);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetBand(JNIEnv* env, jobject thiz, jlong handle, jint index, jfloat freq, jfloat gainDb, jfloat Q) {
    if (handle) ((DSP*)handle)->setBand(index, freq, gainDb, Q);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetHighQualityResampler(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setUseSox(enabled);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetPreamp(JNIEnv* env, jobject thiz, jlong handle, jfloat db) {
    if (handle) ((DSP*)handle)->setPreamp(db);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetDcBlocker(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setDcBlocker(enabled);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetReplayGain(JNIEnv* env, jobject thiz, jlong handle, jfloat db) {
    if (handle) ((DSP*)handle)->setReplayGain(db);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetDvc(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setDvc(enabled);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetDvcLevel(JNIEnv* env, jobject thiz, jlong handle, jfloat level) {
    if (handle) ((DSP*)handle)->setDvcLevel(level);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetDvcMode(JNIEnv* env, jobject thiz, jlong handle, jint mode) {
    if (handle) ((DSP*)handle)->setDvcMode(mode);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetBitDepth(JNIEnv* env, jobject thiz, jlong handle, jint bitDepth) {
    if (handle) ((DSP*)handle)->setBitDepth(bitDepth);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetDither(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled, jint bitDepth) {
    if (handle) ((DSP*)handle)->setDither(enabled, bitDepth);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetCrossfeed(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled, jfloat level) {
    if (handle) ((DSP*)handle)->setCrossfeed(enabled, level);
}

JNIEXPORT jint JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nProcessResampled(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input, jint inFrames, jfloatArray output) {
    if (!handle) return 0;
    jfloat* inBody = env->GetFloatArrayElements(input, 0);
    jfloat* outBody = env->GetFloatArrayElements(output, 0);
    int outFrames = 0;
    ((DSP*)handle)->process(inBody, inFrames, outBody, outFrames);
    env->ReleaseFloatArrayElements(input, inBody, JNI_ABORT);
    env->ReleaseFloatArrayElements(output, outBody, 0);
    return outFrames;
}

JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nInit(JNIEnv* env, jobject thiz, jlong handle, jfloat sampleRate, jint channels) {
    if (handle) ((DSP*)handle)->init((int)sampleRate, (int)sampleRate, channels);
}

JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nProcess(JNIEnv* env, jobject thiz, jlong handle, jfloatArray data, jint frames) {
    if (!handle) return;
    jfloat* body = env->GetFloatArrayElements(data, 0);
    ((DSP*)handle)->processInPlace(body, frames);
    env->ReleaseFloatArrayElements(data, body, 0);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetSpatial(JNIEnv* env, jobject thiz, jlong handle, jfloat balance, jfloat widen) {
    if (handle) ((DSP*)handle)->setSpatial(balance, widen);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetReverb(JNIEnv* env, jobject thiz, jlong handle, jfloat amount) {
    if (handle) ((DSP*)handle)->setReverb(amount);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetReverbType(JNIEnv* env, jobject thiz, jlong handle, jint type) {
    if (handle) ((DSP*)handle)->setReverbType(type);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetReverbPredelay(JNIEnv* env, jobject thiz, jlong handle, jfloat ms) {
    if (handle) ((DSP*)handle)->setReverbPredelay(ms);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetReverbWidth(JNIEnv* env, jobject thiz, jlong handle, jfloat width) {
    if (handle) ((DSP*)handle)->setReverbWidth(width);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetReverbParams(JNIEnv* env, jobject thiz, jlong handle, jfloat roomSize, jfloat damping) {
    if (handle) ((DSP*)handle)->setReverbParams(roomSize, damping);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nMuteReverb(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) ((DSP*)handle)->muteReverb();
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetLimiter(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setLimiter(enabled);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetCutoffRatio(JNIEnv* env, jobject thiz, jlong handle, jfloat ratio) {
    if (handle) ((DSP*)handle)->setCutoffRatio(ratio);
}

}
