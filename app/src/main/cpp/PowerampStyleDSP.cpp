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
#include <aaudio/AAudio.h>

#define LOG_TAG "BeatraxusDSP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ===================== DITHER PROCESSOR =====================
class DitherProcessor {
    uint32_t lcgStateA_L, lcgStateA_R, lcgStateB_L, lcgStateB_R;
    double lastDitherErr[2] = {0.0, 0.0};
    double lastNoise[2] = {0.0, 0.0};
    int type = 1; // 1 = TPDF
    int bitDepth = 16;
    bool enabled = false;

public:
    DitherProcessor() { reset(); }

    void reset() {
        lcgStateA_L = 0x1234ABCD;
        lcgStateA_R = 0x5678EF01;
        lcgStateB_L = 0xDEADBEEF;
        lcgStateB_R = 0xCAFEBABE;
        std::fill(std::begin(lastDitherErr), std::end(lastDitherErr), 0.0);
        std::fill(std::begin(lastNoise), std::end(lastNoise), 0.0);
    }

    void setEnabled(bool e, int bd) {
        if (enabled != e || bitDepth != bd) {
            enabled = e;
            bitDepth = bd;
            // Clear history on state change to prevent DC offset pulse
            std::fill(std::begin(lastDitherErr), std::end(lastDitherErr), 0.0);
            std::fill(std::begin(lastNoise), std::end(lastNoise), 0.0);
        }
    }

    void setType(int t) {
        if (type != t) {
            type = t;
            // Clear history on type change
            std::fill(std::begin(lastDitherErr), std::end(lastDitherErr), 0.0);
            std::fill(std::begin(lastNoise), std::end(lastNoise), 0.0);
        }
    }

    template<typename T>
    void process(T* data, int frames, int channels) {
        if (!enabled || type == 0 || bitDepth >= 32) return;

        double scale = 1.0 / (double)(1LL << (bitDepth - 1));

        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < std::min(channels, 2); c++) {
                uint32_t* stateA = (c == 0) ? &lcgStateA_L : &lcgStateA_R;
                uint32_t* stateB = (c == 0) ? &lcgStateB_L : &lcgStateB_R;

                // Generator A — Knuth LCG
                *stateA = 1664525u * (*stateA) + 1013904223u;
                double r1 = ((*stateA) >> 16) * (1.0 / 65536.0);

                // Generator B — Numerical Recipes
                *stateB = 22695477u * (*stateB) + 1u;
                double r2 = ((*stateB) >> 16) * (1.0 / 65536.0);

                double noise = r1 - r2; // TPDF

                int idx = f * channels + c;
                double input = static_cast<double>(data[idx]);

                if (type == 2) { // SHAPED (Professional error-feedback 1st-order)
                    input += -0.9 * lastDitherErr[c];
                    double dithered = input + noise * scale;
                    // Mock quantization for error calculation
                    double quantized = std::floor(dithered / scale + 0.5) * scale;
                    lastDitherErr[c] = quantized - input;
                    data[idx] = static_cast<T>(quantized);
                } else if (type == 3) { // HIGHPASS
                    double hpNoise = noise - 0.5 * lastNoise[c];
                    lastNoise[c] = noise;
                    data[idx] = static_cast<T>(input + hpNoise * scale);
                } else { // TPDF
                    data[idx] = static_cast<T>(input + noise * scale);
                }
            }
        }
    }
};

// ===================== KISS FFT MINIMAL =====================
#include <complex>

struct kiss_fft_state {
    int nfft;
    int inverse;
    std::vector<int> factors;
    std::vector<std::complex<double>> twiddles;
};

void kiss_fft_alloc(kiss_fft_state& state, int nfft, int inverse) {
    state.nfft = nfft;
    state.inverse = inverse;
    state.twiddles.resize(nfft);
    for (int i = 0; i < nfft; ++i) {
        double phase = (inverse ? 2.0 : -2.0) * M_PI * i / nfft;
        state.twiddles[i] = std::complex<double>(std::cos(phase), std::sin(phase));
    }
}

void kiss_fft_work(const kiss_fft_state& state, std::complex<double>* out, const std::complex<double>* in, int n, int step) {
    if (n == 1) {
        *out = *in;
        return;
    }
    int m = n / 2;
    kiss_fft_work(state, out, in, m, step * 2);
    kiss_fft_work(state, out + m, in + step, m, step * 2);
    for (int i = 0; i < m; ++i) {
        std::complex<double> t = state.twiddles[i * step] * out[i + m];
        out[i + m] = out[i] - t;
        out[i] += t;
    }
}

void kiss_fft(const kiss_fft_state& state, const std::complex<double>* in, std::complex<double>* out) {
    kiss_fft_work(state, out, in, state.nfft, 1);
    if (state.inverse) {
        for (int i = 0; i < state.nfft; ++i) out[i] /= state.nfft;
    }
}

// ===================== BIQUAD FILTER =====================

enum class EqBandType : int {
    PEAKING = 0, LOW_SHELF = 1, HIGH_SHELF = 2,
    LOW_PASS = 3, HIGH_PASS = 4, NOTCH = 5,
    BAND_PASS = 6, ALL_PASS = 7
};

struct BiquadState {
    double b0, b1, b2, a1, a2;
    double z1_l, z2_l, z1_r, z2_r;
    bool enabled = true;
    EqBandType filterType = EqBandType::PEAKING;
    float freq, gain, q;

    BiquadState() { reset(); }

    void reset() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0;
        a1 = 0.0; a2 = 0.0;
        z1_l = z2_l = z1_r = z2_r = 0.0;
    }

    template<typename T>
    inline void process(T& l, T& r) {
        if (!enabled || (filterType <= (EqBandType)2 && std::abs(gain) < 0.001f)) return;

        double in_l = (double)l;
        double out_l = in_l * b0 + z1_l;
        z1_l = in_l * b1 + z2_l - a1 * out_l;
        z2_l = in_l * b2 - a2 * out_l;
        l = (T)out_l;

        double in_r = (double)r;
        double out_r = in_r * b0 + z1_r;
        z1_r = in_r * b1 + z2_r - a1 * out_r;
        z2_r = in_r * b2 - a2 * out_r;
        r = (T)out_r;
    }

    void update(double sr) {
        double safeFreq = std::min((double)freq, sr * 0.49);
        double w0 = 2.0 * M_PI * safeFreq / sr;
        double alpha = std::sin(w0) / (2.0 * q);
        double cosw0 = std::cos(w0);
        double A = std::pow(10.0, gain / 40.0);
        double a0 = 1.0;

        switch (filterType) {
            case EqBandType::PEAKING:
                a0 = 1.0 + alpha / A;
                b0 = (1.0 + alpha * A) / a0;
                b1 = (-2.0 * cosw0) / a0;
                b2 = (1.0 - alpha * A) / a0;
                a1 = (-2.0 * cosw0) / a0;
                a2 = (1.0 - alpha / A) / a0;
                break;
            case EqBandType::LOW_SHELF:
                {
                    double sqrtA = std::sqrt(A);
                    a0 = (A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha;
                    b0 = (A * ((A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha)) / a0;
                    b1 = (2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)) / a0;
                    b2 = (A * ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha)) / a0;
                    a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cosw0)) / a0;
                    a2 = ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha) / a0;
                }
                break;
            case EqBandType::HIGH_SHELF:
                {
                    double sqrtA = std::sqrt(A);
                    a0 = (A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha;
                    b0 = (A * ((A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha)) / a0;
                    b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)) / a0;
                    b2 = (A * ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha)) / a0;
                    a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cosw0)) / a0;
                    a2 = ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha) / a0;
                }
                break;
            case EqBandType::LOW_PASS:
                a0 = 1.0 + alpha;
                b0 = (1.0 - cosw0) / 2.0 / a0;
                b1 = (1.0 - cosw0) / a0;
                b2 = (1.0 - cosw0) / 2.0 / a0;
                a1 = (-2.0 * cosw0) / a0;
                a2 = (1.0 - alpha) / a0;
                break;
            case EqBandType::HIGH_PASS:
                a0 = 1.0 + alpha;
                b0 = (1.0 + cosw0) / 2.0 / a0;
                b1 = -(1.0 + cosw0) / a0;
                b2 = (1.0 + cosw0) / 2.0 / a0;
                a1 = (-2.0 * cosw0) / a0;
                a2 = (1.0 - alpha) / a0;
                break;
            case EqBandType::NOTCH:
                a0 = 1.0 + alpha;
                b0 = 1.0 / a0;
                b1 = -2.0 * cosw0 / a0;
                b2 = 1.0 / a0;
                a1 = -2.0 * cosw0 / a0;
                a2 = (1.0 - alpha) / a0;
                break;
            case EqBandType::BAND_PASS:
                a0 = 1.0 + alpha;
                b0 = alpha / a0;
                b1 = 0.0;
                b2 = -alpha / a0;
                a1 = -2.0 * cosw0 / a0;
                a2 = (1.0 - alpha) / a0;
                break;
            case EqBandType::ALL_PASS:
                a0 = 1.0 + alpha;
                b0 = (1.0 - alpha) / a0;
                b1 = -2.0 * cosw0 / a0;
                b2 = (1.0 + alpha) / a0;
                a1 = -2.0 * cosw0 / a0;
                a2 = (1.0 - alpha) / a0;
                break;
        }
    }

    void setLowShelf(double sr, float f, float g, float q_val) {
        filterType = EqBandType::LOW_SHELF;
        freq = f; gain = g; q = q_val;
        update(sr);
    }
    void setPeaking(double sr, float f, float g, float q_val) {
        filterType = EqBandType::PEAKING;
        freq = f; gain = g; q = q_val;
        update(sr);
    }
    void setHighShelf(double sr, float f, float g, float q_val) {
        filterType = EqBandType::HIGH_SHELF;
        freq = f; gain = g; q = q_val;
        update(sr);
    }

    std::complex<double> response(double f, double sr) {
        if (!enabled) return 1.0;
        if (filterType == EqBandType::PEAKING && std::abs(gain) < 0.001) return 1.0;

        double w = 2.0 * M_PI * f / sr;
        std::complex<double> ejw = std::polar(1.0, -w);
        std::complex<double> ejw2 = std::polar(1.0, -2.0 * w);
        return (b0 + b1 * ejw + b2 * ejw2) / (1.0 + a1 * ejw + a2 * ejw2);
    }
};

class EqEngine {
    static constexpr int FIR_LEN = 2047;
    static constexpr int FFT_SIZE = 8192;
    static constexpr int HOP_SIZE = FFT_SIZE - FIR_LEN + 1;

    std::array<BiquadState, 32> bands;
    bool enabled = false;
    bool linearPhase = false;
    std::atomic<bool> dirty{false};
    float lastSr = 48000;

    // FIR state
    std::vector<double> firFreq;
    std::vector<double> overlapL, overlapR;
    kiss_fft_state fftForward, fftInverse;

    // Internal buffers to avoid allocations in audio thread
    std::vector<std::complex<double>> specL, specR, timeL, timeR;

public:
    EqEngine() {
        kiss_fft_alloc(fftForward, FFT_SIZE, 0);
        kiss_fft_alloc(fftInverse, FFT_SIZE, 1);
        overlapL.resize(FFT_SIZE, 0.0);
        overlapR.resize(FFT_SIZE, 0.0);
        specL.resize(FFT_SIZE); specR.resize(FFT_SIZE);
        timeL.resize(FFT_SIZE); timeR.resize(FFT_SIZE);
    }

    void setEnabled(bool e) { enabled = e; }
    void setPhaseMode(bool lp) { if (linearPhase != lp) { linearPhase = lp; dirty = true; } }

    void setBand(int idx, float f, float g, float q, int type) {
        if (idx < 0 || idx >= 32) return;
        bands[idx].freq = f;
        bands[idx].gain = g;
        bands[idx].q = q;
        bands[idx].filterType = (EqBandType)type;
        bands[idx].enabled = (type > 2 || std::abs(g) > 0.001f);
        bands[idx].update(lastSr);
        dirty = true;
    }

    void init(float sr) {
        lastSr = sr;
        for (auto& b : bands) b.update(sr);
        dirty = true;
    }

    template<typename T>
    void process(T* buf, int frames, int channels) {
        if (!enabled) return;
        if (dirty.exchange(false)) {
            if (linearPhase) recomputeFir();
        }

        if (linearPhase) processFir(buf, frames, channels);
        else processIir(buf, frames, channels);
    }

    int getLatencyFrames() const {
        return linearPhase ? (FIR_LEN / 2) : 0;
    }

    void flush() {
        for (auto& b : bands) { b.z1_l = b.z2_l = b.z1_r = b.z2_r = 0.0; }
        std::fill(overlapL.begin(), overlapL.end(), 0.0);
        std::fill(overlapR.begin(), overlapR.end(), 0.0);
    }

private:
    void recomputeFir() {
        std::vector<std::complex<double>> H(FFT_SIZE);
        for (int i = 0; i <= FFT_SIZE / 2; ++i) {
            double f = (double)i * lastSr / FFT_SIZE;
            double mag = 1.0;
            for (auto& b : bands) {
                if (b.enabled) {
                    std::complex<double> resp = b.response(f, lastSr);
                    mag *= std::abs(resp);
                }
            }
            // Symmetric zero-phase response evaluation
            H[i] = std::complex<double>(mag, 0.0);
            if (i > 0 && i < FFT_SIZE / 2) H[FFT_SIZE - i] = std::complex<double>(mag, 0.0);
        }

        std::vector<std::complex<double>> imp(FFT_SIZE);
        kiss_fft(fftInverse, H.data(), imp.data());

        // Shift and window to create a causal linear-phase FIR
        std::vector<double> fir(FFT_SIZE, 0.0);
        int half = FIR_LEN / 2;
        for (int i = 0; i < FIR_LEN; ++i) {
            // imp[0] is the peak of the zero-phase IR. We shift it to make it causal.
            int idx = (i - half + FFT_SIZE) % FFT_SIZE;
            double w = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / (FIR_LEN - 1))); // Hann window
            fir[i] = imp[idx].real() * w;
        }

        // Zero-pad to FFT size and get frequency response for OLA multiplication
        std::vector<std::complex<double>> firComplex(FFT_SIZE, 0.0);
        for (int i = 0; i < FIR_LEN; ++i) firComplex[i] = fir[i];

        std::vector<std::complex<double>> firFreqComplex(FFT_SIZE);
        kiss_fft(fftForward, firComplex.data(), firFreqComplex.data());

        // Store interleaved real/imag for the audio thread
        this->firFreq.resize(FFT_SIZE * 2);
        for(int i=0; i<FFT_SIZE; ++i) {
            this->firFreq[i*2] = firFreqComplex[i].real();
            this->firFreq[i*2+1] = firFreqComplex[i].imag();
        }
    }

    template<typename T>
    void processIir(T* buf, int frames, int channels) {
        if (channels >= 2) {
            for (int f = 0; f < frames; f++) {
                double l = buf[f * 2], r = buf[f * 2 + 1];
                for (auto& b : bands) if (b.enabled) b.process(l, r);
                buf[f * 2] = (T)l; buf[f * 2 + 1] = (T)r;
            }
        } else {
            for (int f = 0; f < frames; f++) {
                double s = buf[f], dummy = 0.0;
                for (auto& b : bands) if (b.enabled) b.process(s, dummy);
                buf[f] = (T)s;
            }
        }
    }

    template<typename T>
    void processFir(T* buf, int frames, int channels) {
        int processed = 0;
        while (processed < frames) {
            int take = std::min(frames - processed, HOP_SIZE);

            std::fill(timeL.begin(), timeL.end(), 0.0);
            std::fill(timeR.begin(), timeR.end(), 0.0);
            if (channels >= 2) {
                for (int i = 0; i < take; i++) {
                    timeL[i] = (double)buf[(processed + i) * 2];
                    timeR[i] = (double)buf[(processed + i) * 2 + 1];
                }
            } else {
                for (int i = 0; i < take; i++) {
                    timeL[i] = (double)buf[processed + i];
                }
            }

            kiss_fft(fftForward, timeL.data(), specL.data());
            if (channels >= 2) kiss_fft(fftForward, timeR.data(), specR.data());

            for (int i = 0; i < FFT_SIZE; i++) {
                std::complex<double> f(firFreq[i*2], firFreq[i*2+1]);
                specL[i] *= f;
                if (channels >= 2) specR[i] *= f;
            }

            kiss_fft(fftInverse, specL.data(), timeL.data());
            if (channels >= 2) kiss_fft(fftInverse, specR.data(), timeR.data());

            if (channels >= 2) {
                for (int i = 0; i < take; i++) {
                    buf[(processed + i) * 2] = (T)(timeL[i].real() + overlapL[i]);
                    buf[(processed + i) * 2 + 1] = (T)(timeR[i].real() + overlapR[i]);
                }
            } else {
                for (int i = 0; i < take; i++) {
                    buf[processed + i] = (T)(timeL[i].real() + overlapL[i]);
                }
            }

            // Update overlap: shift and add the tails from current FFT convolution
            for (int i = 0; i < FFT_SIZE - take; i++) {
                overlapL[i] = (i + take < FFT_SIZE ? overlapL[i + take] : 0.0) + timeL[i + take].real();
                if (channels >= 2) {
                    overlapR[i] = (i + take < FFT_SIZE ? overlapR[i + take] : 0.0) + timeR[i + take].real();
                }
            }

            processed += take;
        }
    }
};

// ===================== DSP UTILITIES =====================

class LookaheadLimiter {
    std::vector<double> delayBuffer;
    size_t delaySize = 0;
    size_t writePos = 0;
    double gainReduction = 1.0;
    double threshold = 0.98;
    double attackCoeff = 0.9;
    double releaseCoeff = 0.9999;
    double kneeWidthDb = 6.0;
    int currentSampleRate = 48000;

public:
    void init(int sampleRate) {
        currentSampleRate = sampleRate;
        // Increased look-ahead delay to 20ms. This is critical for Bass.
        // 20ms is longer than a full cycle of 50Hz, preventing waveform distortion ("iraichal").
        delaySize = (size_t)std::max(1.0, std::ceil(0.020 * (double)sampleRate));
        delayBuffer.assign(delaySize * 2, 0.0);
        writePos = 0;
        gainReduction = 1.0;
    }

    void setParams(double thresholdDb, double attackMs, double releaseMs) {
        threshold = std::pow(10.0, thresholdDb / 20.0);
        kneeWidthDb = 12.0; // Very soft knee for transparent limiting
        // g(t) = target + (g(t-1) - target) * exp(-1 / (tau * fs))
        if (attackMs > 0)
            attackCoeff = std::exp(-1.0 / (attackMs * 0.001 * currentSampleRate));
        else
            attackCoeff = 0.0;

        if (releaseMs > 0)
            releaseCoeff = std::exp(-1.0 / (releaseMs * 0.001 * currentSampleRate));
        else
            releaseCoeff = 0.0;
    }

    template<typename T>
    void process(T* buffer, int frames, int channels) {
        if (channels != 2 || delaySize == 0) return;

        double currentThreshold = threshold;
        double currentAttack = attackCoeff;
        double currentRelease = releaseCoeff;

        for (int f = 0; f < frames; f++) {
            // 1. Peak detection (absolute peak of both channels)
            double lIn = (double)buffer[f * 2];
            double rIn = (double)buffer[f * 2 + 1];
            double peak = std::max(std::abs(lIn), std::abs(rIn));

            // 2. Gain Computer with 6dB Soft-Knee
            double targetGain = 1.0;
            if (peak > 1e-9) {
                double peakDb = 20.0 * std::log10(peak);
                double thresholdDb = 20.0 * std::log10(currentThreshold);

                double lowerKnee = thresholdDb - kneeWidthDb / 2.0;
                double upperKnee = thresholdDb + kneeWidthDb / 2.0;

                if (peakDb > lowerKnee) {
                    if (peakDb < upperKnee) {
                        // Soft-knee: quadratic interpolation in log domain
                        double diff = peakDb - lowerKnee;
                        double reductionDb = (diff * diff) / (2.0 * kneeWidthDb);
                        targetGain = std::pow(10.0, -reductionDb / 20.0);
                    } else {
                        // Hard limiting: scale peak to threshold ceiling
                        targetGain = currentThreshold / peak;
                    }
                }
            }

            // 3. Smoothing (Attack/Release)
            // If targetGain is lower than current, we are in the attack phase
            if (targetGain < gainReduction) {
                // ATTACK: Move fast to prevent clipping
                gainReduction = targetGain + (gainReduction - targetGain) * currentAttack;
            } else {
                // RELEASE: Move slowly back to 1.0
                gainReduction = targetGain + (gainReduction - targetGain) * currentRelease;
            }

            // 4. Apply gain reduction to the DELAYED signal (Look-ahead)
            double delayedL = delayBuffer[writePos * 2];
            double delayedR = delayBuffer[writePos * 2 + 1];

            // Store current input in the look-ahead circular buffer
            delayBuffer[writePos * 2] = lIn;
            delayBuffer[writePos * 2 + 1] = rIn;
            writePos = (writePos + 1) % delaySize;

            // Output delayed sample multiplied by smoothed gain
            buffer[f * 2] = (T)(delayedL * gainReduction);
            buffer[f * 2 + 1] = (T)(delayedR * gainReduction);
        }
    }

    void reset() {
        std::fill(delayBuffer.begin(), delayBuffer.end(), 0.0);
        writePos = 0;
        gainReduction = 1.0;
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

class DcBlocker {
    double x1_l, x1_r, y1_l, y1_r;
    double R;
public:
    DcBlocker() : x1_l(0), x1_r(0), y1_l(0), y1_r(0), R(0.999) {}
    void init(int sampleRate) { R = 1.0 - (2.0 * M_PI * 5.0 / sampleRate); }
    template<typename T>
    inline void process(T& l, T& r) {
        double y_l = (double)l - x1_l + R * y1_l;
        x1_l = (double)l; y1_l = y_l; l = (T)y_l;
        double y_r = (double)r - x1_r + R * y1_r;
        x1_r = (double)r; y1_r = y_r; r = (T)y_r;
    }
};

class Bs2b {
    double lp_b0, lp_b1, lp_a1;
    double lp_z_l, lp_z_r;
    static constexpr int DELAY_SAMPLES = 18;
    double delayL[DELAY_SAMPLES];
    double delayR[DELAY_SAMPLES];
    int delayIdx;
public:
    Bs2b() { reset(); }
    void reset() {
        lp_z_l = lp_z_r = 0.0;
        std::fill(std::begin(delayL), std::end(delayL), 0.0);
        std::fill(std::begin(delayR), std::end(delayR), 0.0);
        delayIdx = 0;
    }
    void init(int sampleRate, float cutHz = 700.0f) {
        double w = std::tan(M_PI * cutHz / sampleRate);
        double norm = 1.0 / (1.0 + w);
        lp_b0 = w * norm; lp_b1 = w * norm; lp_a1 = (w - 1.0) * norm;
    }
    template<typename T>
    inline void process(T& l, T& r, float level) {
        double in_l = (double)l; double in_r = (double)r;
        double lp_l = lp_b0 * in_l - lp_a1 * lp_z_l + lp_z_l;
        lp_z_l = lp_b1 * in_l - lp_a1 * lp_z_l;
        double lp_r = lp_b0 * in_r - lp_a1 * lp_z_r + lp_z_r;
        lp_z_r = lp_b1 * in_r - lp_a1 * lp_z_r;
        double blend = (double)level * 0.45;
        double direct = 1.0 - blend * 0.6;
        double dL = delayL[delayIdx]; double dR = delayR[delayIdx];
        delayL[delayIdx] = lp_l; delayR[delayIdx] = lp_r;
        delayIdx = (delayIdx + 1) % DELAY_SAMPLES;
        l = (T)(in_l * direct + dR * blend);
        r = (T)(in_r * direct + dL * blend);
    }
};

struct ReverbPreset {
    float roomSize; float damping; float predelayMs; float width;
};

static const std::map<int, ReverbPreset> REVERB_PRESETS = {
    {0, {0.0f, 0.5f,  0.0f, 1.0f}}, {1, {0.3f, 0.6f,  5.0f, 0.8f}}, {2, {0.7f, 0.4f, 20.0f, 1.0f}},
    {3, {0.9f, 0.2f, 40.0f, 1.0f}}, {4, {0.2f, 0.8f,  2.0f, 0.6f}}, {5, {0.5f, 0.7f,  1.0f, 0.9f}},
    {6, {0.4f, 0.5f, 10.0f, 0.8f}},
};

class Freeverb {
    static constexpr int numCombs = 8; static constexpr int numAllPasses = 4;
    static constexpr int stereoSpread = 23;
    static constexpr int combTuning[] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    static constexpr int allPassTuning[] = {556, 441, 341, 225};
    struct Comb {
        std::vector<double> buffer; int size = 0; int index = 0;
        double feedback = 0; double filterStore = 0; double damp = 0;
        void setSize(int s) { size = s; buffer.assign(size, 0.0); index = 0; filterStore = 0.0; }
        inline double process(double input) {
            double output = buffer[index];
            filterStore = (output * (1.0 - damp)) + (filterStore * damp);
            buffer[index] = input + (filterStore * feedback);
            if (++index >= size) index = 0; return output;
        }
        void mute() { std::fill(buffer.begin(), buffer.end(), 0.0); filterStore = 0.0; }
    };
    struct AllPass {
        std::vector<double> buffer; int size = 0; int index = 0; double feedback = 0.5;
        void setSize(int s) { size = s; buffer.assign(size, 0.0); index = 0; }
        inline double process(double input) {
            double bufOut = buffer[index]; double output = -input + bufOut;
            buffer[index] = input + (bufOut * feedback);
            if (++index >= size) index = 0; return output;
        }
        void mute() { std::fill(buffer.begin(), buffer.end(), 0.0); }
    };
    Comb combL[numCombs]; Comb combR[numCombs]; AllPass allPassL[numAllPasses]; AllPass allPassR[numAllPasses];
    std::vector<double> predelayBuffer; int predelayPos = 0; int predelaySize = 0;
    static constexpr double fixedgain = 0.015;
    std::atomic<double> roomSize{0.5}; std::atomic<double> damp{0.5}; std::atomic<double> wet{0.0};
    std::atomic<double> dry{1.0}; std::atomic<double> width{1.0};
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
            allPassL[i].feedback = allPassR[i].feedback = 0.5;
        }
        predelaySize = (int)(sampleRate * 0.5f); predelayBuffer.assign(predelaySize * 2, 0.0); predelayPos = 0;
    }
    void setRoomSize(float v) { roomSize.store((double)v * 0.28 + 0.7, std::memory_order_relaxed); }
    void setDamping(float v) { damp.store((double)v * 0.4, std::memory_order_relaxed); }
    void setWet(float v) {
        // v is 0..1. Maintain original dry signal at unity to prevent volume drop.
        // Add wet signal on top, scaled for musical balance.
        wet.store((double)v * 1.5, std::memory_order_relaxed);
        dry.store(1.0, std::memory_order_relaxed);
    }
    void setWidth(float v) { width.store((double)v, std::memory_order_relaxed); }
    template<typename T>
    inline void process(T& l, T& r, int predelaySamples) {
        double rs = roomSize.load(std::memory_order_relaxed); double d = damp.load(std::memory_order_relaxed);
        double w = wet.load(std::memory_order_relaxed); double dr = dry.load(std::memory_order_relaxed);
        double wid = width.load(std::memory_order_relaxed);
        int pSamples = std::min(predelaySamples, predelaySize - 1);
        int readPos = (predelayPos + predelaySize - pSamples) % predelaySize;
        double delayedL = predelayBuffer[readPos * 2]; double delayedR = predelayBuffer[readPos * 2 + 1];
        predelayBuffer[predelayPos * 2] = (double)l; predelayBuffer[predelayPos * 2 + 1] = (double)r;
        if (++predelayPos >= predelaySize) predelayPos = 0;
        double monoIn = (delayedL + delayedR) * fixedgain; double outL = 0, outR = 0;
        for (int i = 0; i < numCombs; i++) {
            combL[i].feedback = combR[i].feedback = rs; combL[i].damp = combR[i].damp = d;
            outL += combL[i].process(monoIn); outR += combR[i].process(monoIn);
        }
        for (int i = 0; i < numAllPasses; i++) {
            outL = allPassL[i].process(outL); outR = allPassR[i].process(outR);
        }
        double wet1 = w * (wid * 0.5 + 0.5); double wet2 = w * (1.0 - wid) * 0.5;
        double finalL = outL * wet1 + outR * wet2 + (double)l * dr;
        double finalR = outR * wet1 + outL * wet2 + (double)r * dr;
        l = (T)finalL; r = (T)finalR;
    }
    void mute() {
        for(int i=0; i<numCombs; i++) { combL[i].mute(); combR[i].mute(); }
        for(int i=0; i<numAllPasses; i++) { allPassL[i].mute(); allPassR[i].mute(); }
        std::fill(predelayBuffer.begin(), predelayBuffer.end(), 0.0);
    }
};

// ===================== MMAP AUDIO OUTPUT =====================
class MmapStream {
    AAudioStream* stream = nullptr;
    int32_t channels = 2;
    int32_t sampleRate = 48000;
public:
    MmapStream(int sr, int ch, int bufferFrames) {
        AAudioStreamBuilder* builder;
        AAudio_createStreamBuilder(&builder);
        AAudioStreamBuilder_setSampleRate(builder, sr);
        AAudioStreamBuilder_setChannelCount(builder, ch);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

#if __ANDROID_API__ >= 28
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
#endif

        if (bufferFrames > 0) {
            AAudioStreamBuilder_setBufferCapacityInFrames(builder, bufferFrames * 4);
        }

        aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
        if (result != AAUDIO_OK) {
            LOGI("AAudio stream open failed: %s", AAudio_convertResultToText(result));
            stream = nullptr;
        } else {
            sampleRate = AAudioStream_getSampleRate(stream);
            channels = AAudioStream_getChannelCount(stream);
            if (bufferFrames > 0) {
                AAudioStream_setBufferSizeInFrames(stream, bufferFrames);
            }
        }
        AAudioStreamBuilder_delete(builder);
    }
    ~MmapStream() { if (stream) AAudioStream_close(stream); }
    void start() { if (stream) AAudioStream_requestStart(stream); }
    void pause() { if (stream) AAudioStream_requestPause(stream); }
    void stop() { if (stream) AAudioStream_requestStop(stream); }
    void flush() { if (stream) AAudioStream_requestFlush(stream); }
    int write(float* data, int offset, int frames) {
        if (!stream) return 0;
        aaudio_result_t result = AAudioStream_write(stream, data + offset, frames, 10000000LL); // 10ms timeout
        if (result < 0) return 0;
        return (int)result;
    }
    int64_t getPosition() {
        if (!stream) return 0;
        int64_t frames = 0, nanos = 0;
        AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &frames, &nanos);
        return frames;
    }
    int getBufferSize() { return stream ? AAudioStream_getBufferSizeInFrames(stream) : 0; }
    int getSampleRate() { return sampleRate; }
    int getLatency() {
        if (!stream) return 0;
        // Approximation
        return 0; // Latency calculation needs more state
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
            midBassDb(0.0f), trebleDb(0.0f), airDb(0.0f),
            balance(0.0f), stereoWidth(1.0f), crossfeedEnabled(false), crossfeedLevel(0.4f),
            reverbAmount(0.0f),
            reverbType(0), reverbPredelayMs(0.0f), reverbWidth(1.0f),
            reverbDamping(0.5f), reverbRoomSize(0.5f),
            bitDepth(16), cutoffRatio(0.97f),
            soxrQuality(3), float64Enabled(false) {
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
        dcBlocker.init(inRate); reverb.init(inRate); crossfeed.init(inRate);
        limiter.init(inRate);
        limiter.setParams((double)limiterThresholdDb, (double)limiterAttackMs, (double)limiterReleaseMs);
        eq.init((float)inRate);
        updateFilters(); updateSoxr();
    }

    void updateSoxr() {
#ifdef HAVE_SOXR
        if (soxr_handle) { soxr_delete(soxr_handle); soxr_handle = nullptr; }
        if (inRate == outRate) return;
        soxr_error_t err; soxr_io_spec_t io_spec = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
        unsigned long recipe;
        switch (soxrQuality) {
            case 0: recipe = SOXR_QQ; break; case 1: recipe = SOXR_LQ; break; case 2: recipe = SOXR_MQ; break;
            case 3: recipe = SOXR_HQ; break; case 4: recipe = SOXR_VHQ; break; default: recipe = SOXR_HQ; break;
        }
        soxr_quality_spec_t q_spec = soxr_quality_spec(recipe, SOXR_LINEAR_PHASE);
        q_spec.passband_end = 0.997; q_spec.stopband_begin = 1.0;
        soxr_runtime_spec_t r_spec = soxr_runtime_spec(1);
        soxr_handle = soxr_create((double)inRate, (double)outRate, (unsigned)channels, &err, &io_spec, &q_spec, &r_spec);
#endif
    }

    void processInPlace(float* input, int inFrames) {
        if (float64Enabled) {
            int samples = inFrames * channels;
            doubleBuffer.resize(samples);
            for (int i = 0; i < samples; i++) doubleBuffer[i] = (double)input[i];
            processChain(doubleBuffer.data(), inFrames);
            for (int i = 0; i < samples; i++) input[i] = (float)doubleBuffer[i];
        } else {
            processChain(input, inFrames);
        }
    }

    template<typename T>
    void processChain(T* input, int inFrames) {
        int samples = inFrames * channels;
        // Smooth tone targets towards current values to avoid coefficient jumps
        bool needUpdateFilters = false;
        {
            float tmb = toneTargetMidBass.load(std::memory_order_relaxed);
            float tt = toneTargetTreble.load(std::memory_order_relaxed);
            float ta = toneTargetAir.load(std::memory_order_relaxed);

            float diff;
            diff = tmb - toneCurrentMidBass;
            if (std::abs(diff) > 0.0005f) { toneCurrentMidBass += diff * toneSmoothingFactor; needUpdateFilters = true; }
            else if (toneCurrentMidBass != tmb) { toneCurrentMidBass = tmb; needUpdateFilters = true; }

            diff = tt - toneCurrentTreble;
            if (std::abs(diff) > 0.0005f) { toneCurrentTreble += diff * toneSmoothingFactor; needUpdateFilters = true; }
            else if (toneCurrentTreble != tt) { toneCurrentTreble = tt; needUpdateFilters = true; }

            diff = ta - toneCurrentAir;
            if (std::abs(diff) > 0.0005f) { toneCurrentAir += diff * toneSmoothingFactor; needUpdateFilters = true; }
            else if (toneCurrentAir != ta) { toneCurrentAir = ta; needUpdateFilters = true; }

            if (needUpdateFilters) {
                midBassDb = toneCurrentMidBass; trebleDb = toneCurrentTreble; airDb = toneCurrentAir;
                updateFilters();
            }
        }

        if (dcBlockerEnabled && channels >= 2) {
            for (int i = 0; i < samples; i += 2) dcBlocker.process(input[i], input[i + 1]);
        }
        if (replayGainDb != 0.0f) {
            T gain = (T)std::pow(10.0, (double)replayGainDb / 20.0);
            for (int i = 0; i < samples; i++) input[i] *= gain;
        }
        if (preampDb != 0.0f) {
            T gain = (T)std::pow(10.0, (double)preampDb / 20.0);
            for (int i = 0; i < samples; i++) input[i] *= gain;
        }

        // EQ
        // EqEngine handles both IIR and FIR
        eq.process(input, inFrames, channels);

        if (channels >= 2) {
            for (int i = 0; i < samples; i += 2) {
                T& l = input[i]; T& r = input[i + 1];
                for (int t = 0; t < 3; t++) toneFilters[t].process(l, r);
            }
        } else {
            for (int i = 0; i < samples; i++) {
                T& s = input[i]; T dummy = 0;
                for (int t = 0; t < 3; t++) toneFilters[t].process(s, dummy);
            }
        }
        if (channels >= 2) {
            for (int i = 0; i < samples; i += 2) {
                T mid = (input[i] + input[i + 1]) * (T)0.5; T side = (input[i] - input[i + 1]) * (T)0.5;
                side *= (T)stereoWidth; T left = mid + side; T right = mid - side;
                if (balance > 0.0f) left *= (T)(1.0f - balance); else if (balance < 0.0f) right *= (T)(1.0f + balance);
                input[i] = left; input[i + 1] = right;
            }
        }
        if (crossfeedEnabled && channels >= 2) {
            for (int i = 0; i < samples; i += 2) crossfeed.process(input[i], input[i + 1], crossfeedLevel);
        }
        if (reverbAmount > 0.001f) {
            int predelaySamples = (int)(reverbPredelayMs * inRate / 1000.0f);
            for (int i = 0; i < samples; i += channels) {
                if (channels >= 2) reverb.process(input[i], input[i+1], predelaySamples);
                else { T dummy = input[i]; reverb.process(input[i], dummy, predelaySamples); }
            }
        }
        if (dvcEnabled) {
            T gain = (T)dvcLevel;
            for (int i = 0; i < samples; i++) input[i] *= gain;
        }
        if (limiterEnabled) limiter.process(input, inFrames, channels);
        dither.process(input, inFrames, channels);
    }

    void process(float* input, int inFrames, float* output, int& outFrames) {
        processInPlace(input, inFrames);
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
    void setReverb(float amount) { reverbAmount = amount; reverb.setWet(amount); }
    void setReverbType(int type) {
        reverbType = type; auto it = REVERB_PRESETS.find(type);
        if (it != REVERB_PRESETS.end()) {
            setReverbParams(it->second.roomSize, it->second.damping);
            setReverbPredelay(it->second.predelayMs); setReverbWidth(it->second.width);
        }
    }
    void setReverbPredelay(float ms) { reverbPredelayMs = ms; }
    void setReverbWidth(float w) { reverbWidth = w; reverb.setWidth(w); }
    void setReverbParams(float roomSize, float damping) {
        reverbRoomSize = roomSize; reverbDamping = damping; reverb.setRoomSize(roomSize); reverb.setDamping(damping);
    }
    void setLimiter(bool enabled) { limiterEnabled = enabled; }
    void setLimiterParams(float thresholdDb, float attackMs, float releaseMs) {
        limiterThresholdDb = thresholdDb;
        limiterAttackMs = attackMs;
        limiterReleaseMs = releaseMs;
        limiter.setParams((double)thresholdDb, (double)attackMs, (double)releaseMs);
    }
    void muteReverb() { reverb.mute(); reverbAmount = 0.0f; }
    void setBitDepth(int bd) { bitDepth = bd; }
    void setDither(bool enabled, int bd) { dither.setEnabled(enabled, bd); }
    void setDitherType(int mode) { dither.setType(mode); }
    void setTone(float midBass, float treble, float air) {
        // Set targets atomically; actual application is smoothed in audio thread
        toneTargetMidBass.store(midBass, std::memory_order_relaxed);
        toneTargetTreble.store(treble, std::memory_order_relaxed);
        toneTargetAir.store(air, std::memory_order_relaxed);
        // Do not call updateFilters() here to avoid coefficient jumps on the control thread
    }
    void setBand(int index, float freq, float gainDb, float Q, int type) {
        eq.setBand(index, freq, gainDb, Q, type);
    }
    void setEqEnabled(bool enabled) { eq.setEnabled(enabled); }
    void setEqPhaseMode(bool linearPhase) { eq.setPhaseMode(linearPhase); }
    void setUseSox(bool use) { if (useSox != use) { useSox = use; updateSoxr(); } }
    void setCutoffRatio(float ratio) { if (fabsf(cutoffRatio - ratio) > 0.001f) { cutoffRatio = ratio; updateSoxr(); } }
    void setSoxrQuality(int quality) { if (soxrQuality != quality) { soxrQuality = quality; updateSoxr(); } }
    void setFloat64Mode(bool enabled) { float64Enabled = enabled; }

private:
    std::array<float, 32> bandDbs, bandQs, bandFreqs;

    void updateFilters() {
        double sr = (double)inRate;
        // Tweak: gentler shelf/peaking defaults to avoid muffling and excessive resonances
        if (fabsf(midBassDb) > 0.05f) toneFilters[0].setPeaking(sr, 300.0, (double)midBassDb, 0.65); else toneFilters[0].reset();
        if (fabsf(trebleDb) > 0.05f) toneFilters[1].setHighShelf(sr, 6000.0, (double)trebleDb, 0.7); else toneFilters[1].reset();
        if (fabsf(airDb) > 0.05f) toneFilters[2].setHighShelf(sr, 12000.0, (double)airDb, 0.6); else toneFilters[2].reset();
        // Manual bands handled by EqEngine
    }
#ifdef HAVE_SOXR
    soxr_t soxr_handle;
#endif
    int inRate, outRate, channels; bool useSox; DcBlocker dcBlocker; bool dcBlockerEnabled;
    bool dvcEnabled; float dvcLevel; int dvcMode; bool limiterEnabled;
    float limiterThresholdDb = -0.2f, limiterAttackMs = 5.0f, limiterReleaseMs = 100.0f;
    float cutoffRatio; int soxrQuality;
    bool float64Enabled;
    DitherProcessor dither;
    float replayGainDb, preampDb; float midBassDb, trebleDb, airDb;
    EqEngine eq;
    BiquadState toneFilters[3]; LookaheadLimiter limiter; Bs2b crossfeed;
    bool crossfeedEnabled; float crossfeedLevel; Freeverb reverb; float reverbAmount; int reverbType;
    float reverbPredelayMs; float reverbWidth; float reverbDamping; float reverbRoomSize;
    float balance, stereoWidth; int bitDepth;

    // Tone smoothing targets and current values to avoid coefficient jumps
    std::atomic<float> toneTargetMidBass{0.0f}, toneTargetTreble{0.0f}, toneTargetAir{0.0f};
    float toneCurrentMidBass = 0.0f, toneCurrentTreble = 0.0f, toneCurrentAir = 0.0f;
    float toneSmoothingFactor = 0.12f; // per-buffer interpolation

    std::vector<float> tempBuffer; std::vector<double> doubleBuffer;
};

extern "C" {
JNIEXPORT jlong JNICALL Java_com_beatflowy_app_engine_NativeDsp_nCreate(JNIEnv* env, jobject thiz) { return (jlong)new DSP(); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nDestroy(JNIEnv* env, jobject thiz, jlong handle) { if (handle) delete (DSP*)handle; }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nInitResampler(JNIEnv* env, jobject thiz, jlong handle, jfloat inputSR, jint channels, jfloat targetSR) { if (handle) ((DSP*)handle)->init((int)inputSR, (int)targetSR, channels); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetVolume(JNIEnv* env, jobject thiz, jlong handle, jfloat volume) { if (handle) ((DSP*)handle)->setVolume(volume); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetTone(JNIEnv* env, jobject thiz, jlong handle, jfloat midBass, jfloat treble, jfloat air) { if (handle) ((DSP*)handle)->setTone(midBass, treble, air); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetBand(JNIEnv* env, jobject thiz, jlong handle, jint index, jfloat freq, jfloat gainDb, jfloat Q, jint type) { if (handle) ((DSP*)handle)->setBand(index, freq, gainDb, Q, type); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetEqEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setEqEnabled(enabled); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetEqPhaseMode(JNIEnv* env, jobject thiz, jlong handle, jboolean linearPhase) { if (handle) ((DSP*)handle)->setEqPhaseMode(linearPhase); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetHighQualityResampler(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setUseSox(enabled); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetPreamp(JNIEnv* env, jobject thiz, jlong handle, jfloat db) { if (handle) ((DSP*)handle)->setPreamp(db); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetDcBlocker(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setDcBlocker(enabled); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetReplayGain(JNIEnv* env, jobject thiz, jlong handle, jfloat db) { if (handle) ((DSP*)handle)->setReplayGain(db); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetDvc(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setDvc(enabled); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetDvcLevel(JNIEnv* env, jobject thiz, jlong handle, jfloat level) { if (handle) ((DSP*)handle)->setDvcLevel(level); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetDvcMode(JNIEnv* env, jobject thiz, jlong handle, jint mode) { if (handle) ((DSP*)handle)->setDvcMode(mode); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetBitDepth(JNIEnv* env, jobject thiz, jlong handle, jint bitDepth) { if (handle) ((DSP*)handle)->setBitDepth(bitDepth); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetDither(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled, jint bitDepth) { if (handle) ((DSP*)handle)->setDither(enabled, bitDepth); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetDitherType(JNIEnv* env, jobject thiz, jlong handle, jint type) { if (handle) ((DSP*)handle)->setDitherType(type); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetCrossfeed(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled, jfloat level) { if (handle) ((DSP*)handle)->setCrossfeed(enabled, level); }
JNIEXPORT jint JNICALL Java_com_beatflowy_app_engine_NativeDsp_nProcessResampled(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input, jint inFrames, jfloatArray output) {
    if (!handle) return 0; jfloat* inBody = env->GetFloatArrayElements(input, 0); jfloat* outBody = env->GetFloatArrayElements(output, 0);
    int outFrames = 0; ((DSP*)handle)->process(inBody, inFrames, outBody, outFrames);
    env->ReleaseFloatArrayElements(input, inBody, JNI_ABORT); env->ReleaseFloatArrayElements(output, outBody, 0); return outFrames;
}
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nInit(JNIEnv* env, jobject thiz, jlong handle, jfloat sampleRate, jint channels) { if (handle) ((DSP*)handle)->init((int)sampleRate, (int)sampleRate, channels); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nProcess(JNIEnv* env, jobject thiz, jlong handle, jfloatArray data, jint frames) {
    if (!handle) return; jfloat* body = env->GetFloatArrayElements(data, 0); ((DSP*)handle)->processInPlace(body, frames); env->ReleaseFloatArrayElements(data, body, 0);
}
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetSpatial(JNIEnv* env, jobject thiz, jlong handle, jfloat balance, jfloat widen) { if (handle) ((DSP*)handle)->setSpatial(balance, widen); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetReverb(JNIEnv* env, jobject thiz, jlong handle, jfloat amount) { if (handle) ((DSP*)handle)->setReverb(amount); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetReverbType(JNIEnv* env, jobject thiz, jlong handle, jint type) { if (handle) ((DSP*)handle)->setReverbType(type); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetReverbPredelay(JNIEnv* env, jobject thiz, jlong handle, jfloat ms) { if (handle) ((DSP*)handle)->setReverbPredelay(ms); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetReverbWidth(JNIEnv* env, jobject thiz, jlong handle, jfloat width) { if (handle) ((DSP*)handle)->setReverbWidth(width); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetReverbParams(JNIEnv* env, jobject thiz, jlong handle, jfloat roomSize, jfloat damping) { if (handle) ((DSP*)handle)->setReverbParams(roomSize, damping); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nMuteReverb(JNIEnv* env, jobject thiz, jlong handle) { if (handle) ((DSP*)handle)->muteReverb(); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetLimiter(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setLimiter(enabled); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetLimiterParams(JNIEnv* env, jobject thiz, jlong handle, jfloat thresholdDb, jfloat attackMs, jfloat releaseMs) {
    if (handle) ((DSP*)handle)->setLimiterParams(thresholdDb, attackMs, releaseMs);
}
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetCutoffRatio(JNIEnv* env, jobject thiz, jlong handle, jfloat ratio) { if (handle) ((DSP*)handle)->setCutoffRatio(ratio); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetSoxrQuality(JNIEnv* env, jobject thiz, jlong handle, jint quality) { if (handle) ((DSP*)handle)->setSoxrQuality(quality); }
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_NativeDsp_nSetFloat64(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setFloat64Mode(enabled); }

// MMAP Audio Output JNI
JNIEXPORT jlong JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapCreate(JNIEnv* env, jobject thiz, jint sampleRate, jint channels, jint bufferFrames) {
    return (jlong)new MmapStream(sampleRate, channels, bufferFrames);
}
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) delete (MmapStream*)handle;
}
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapStart(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) ((MmapStream*)handle)->start();
}
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapPause(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) ((MmapStream*)handle)->pause();
}
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapStop(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) ((MmapStream*)handle)->stop();
}
JNIEXPORT void JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapFlush(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) ((MmapStream*)handle)->flush();
}
JNIEXPORT jint JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapWrite(JNIEnv* env, jobject thiz, jlong handle, jfloatArray data, jint offset, jint frames) {
    if (!handle) return 0;
    jfloat* body = env->GetFloatArrayElements(data, nullptr);
    int written = ((MmapStream*)handle)->write(body, offset, frames);
    env->ReleaseFloatArrayElements(data, body, JNI_ABORT);
    return written;
}
JNIEXPORT jlong JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapGetPlaybackPosition(JNIEnv* env, jobject thiz, jlong handle) {
    return handle ? (jlong)((MmapStream*)handle)->getPosition() : 0;
}
JNIEXPORT jint JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapGetBufferFrames(JNIEnv* env, jobject thiz, jlong handle) {
    return handle ? ((MmapStream*)handle)->getBufferSize() : 0;
}
JNIEXPORT jint JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapGetLatencyMs(JNIEnv* env, jobject thiz, jlong handle) {
    return handle ? ((MmapStream*)handle)->getLatency() : 0;
}
JNIEXPORT jint JNICALL Java_com_beatflowy_app_engine_MmapAudioOutput_nMmapGetSampleRate(JNIEnv* env, jobject thiz, jlong handle) {
    return handle ? ((MmapStream*)handle)->getSampleRate() : 48000;
}
}
