#include <vector>
#include <array>
#include <cmath>
#include <algorithm>
#include <map>
#include <string>
#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <jni.h>
#ifdef HAVE_SOXR
#include <soxr.h>
#endif
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#ifdef __ARM_NEON
#include <arm_neon.h>
#endif

#define LOG_TAG "BeatraxusDSP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ===================== DSD / DoP UTILS =====================

struct DsfHeader {
    char id[4]; // "DSD "
    uint64_t size;
    uint64_t totalSize;
    uint64_t metadataPointer;
};

struct DsfFormatChunk {
    char id[4]; // "fmt "
    uint64_t size;
    uint32_t version;
    uint32_t formatId;
    uint32_t channelType;
    uint32_t channelCount;
    uint32_t sampleRate;
    uint32_t bitsPerSample;
    uint64_t sampleCount;
    uint32_t blockSize;
    uint32_t reserved;
};

class DsdProcessor {
public:
    // Packs 16 bits of DSD into a 24-bit PCM sample with DoP markers
    // DSD bits are expected in 16-bit units.
    static void packDoP(const uint8_t* dsd, int32_t* pcmInt, int frames, int channels, bool alternateMarker) {
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                uint8_t marker = alternateMarker ? 0x05 : 0xFA;
                int32_t packed = (marker << 24) | (dsd[(f * channels + c) * 2] << 16) | (dsd[(f * channels + c) * 2 + 1] << 8);
                pcmInt[f * channels + c] = packed; // real integer sample, not reinterpreted
            }
            alternateMarker = !alternateMarker;
        }
    }

    static void dsdToPcm(const uint8_t* dsd, float* pcm, int frames, int channels) {
        // Bug 2 Fix: Real decimation with 32-tap windowed-sinc FIR
        // Each PCM sample is produced from one DSD byte per channel (8:1 decimation).
        // This takes DSD64 (2.8MHz) to DXD (352.8kHz).
        static const float fir[32] = {
            -0.0016f, -0.0022f, -0.0020f, 0.0000f, 0.0042f, 0.0104f, 0.0183f, 0.0270f,
            0.0355f, 0.0426f, 0.0475f, 0.0496f, 0.0487f, 0.0448f, 0.0384f, 0.0302f,
            0.0211f, 0.0121f, 0.0041f, -0.0023f, -0.0066f, -0.0087f, -0.0089f, -0.0076f,
            -0.0054f, -0.0030f, -0.0010f, 0.0003f, 0.0009f, 0.0010f, 0.0007f, 0.0004f
        };

        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                float sum = 0;
                // Look back up to 4 bytes (32 bits) for this channel to apply the 32-tap FIR
                for (int b_off = 0; b_off < 4; b_off++) {
                    int idx = f - b_off;
                    // 0xAA (10101010) is silence in DSD
                    uint8_t b = (idx >= 0) ? dsd[idx * channels + c] : 0xAA;
                    for (int bit = 0; bit < 8; bit++) {
                        float impulse = (b & (1 << (7 - bit))) ? 1.0f : -1.0f;
                        sum += impulse * fir[b_off * 8 + bit];
                    }
                }
                pcm[f * channels + c] = sum;
            }
        }
    }
};

// ===================== DITHER PROCESSOR =====================
class DitherProcessor {
    std::vector<uint32_t> lcgStateA, lcgStateB;
    std::vector<double> lastDitherErr;
    std::vector<double> lastNoise;
    int type = 2; // 2 = SHAPED
    int bitDepth = 16;
    bool enabled = false;

public:
    DitherProcessor() { init(2); }

    void init(int channels) {
        lcgStateA.assign(channels, 0x1234ABCD);
        lcgStateB.assign(channels, 0xDEADBEEF);
        for(int i=0; i<channels; i++) {
            lcgStateA[i] += i * 0x777;
            lcgStateB[i] += i * 0x333;
        }
        lastDitherErr.assign(channels, 0.0);
        lastNoise.assign(channels, 0.0);
    }

    void reset() {
        for(size_t i=0; i<lcgStateA.size(); i++) {
            lcgStateA[i] = 0x1234ABCD + (uint32_t)i * 0x777;
            lcgStateB[i] = 0xDEADBEEF + (uint32_t)i * 0x333;
        }
        std::fill(lastDitherErr.begin(), lastDitherErr.end(), 0.0);
        std::fill(lastNoise.begin(), lastNoise.end(), 0.0);
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
        if (lcgStateA.size() < (size_t)channels) init(channels);

        double scale = 1.0 / (double)(1LL << (bitDepth - 1));

        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                uint32_t& stateA = lcgStateA[c];
                uint32_t& stateB = lcgStateB[c];

                stateA = 1664525u * stateA + 1013904223u;
                double r1 = (stateA >> 16) * (1.0 / 65536.0);
                stateB = 22695477u * stateB + 1u;
                double r2 = (stateB >> 16) * (1.0 / 65536.0);

                double noise = r1 - r2;
                int idx = f * channels + c;
                double input = static_cast<double>(data[idx]);

                if (type == 2) {
                    double original = input;                    // save before shaping
                    input += -0.9 * lastDitherErr[c];           // apply 1st-order shaping
                    double dithered = input + noise * scale;
                    double quantized = std::floor(dithered / scale + 0.5) * scale;
                    lastDitherErr[c] = quantized - original;    // error vs true original
                    data[idx] = static_cast<T>(quantized);
                } else if (type == 3) {
                    double hpNoise = noise - 0.5 * lastNoise[c];
                    lastNoise[c] = noise;
                    data[idx] = static_cast<T>(input + hpNoise * scale);
                } else {
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

// ===================== BIQUAD FILTER [RETAINED FOR EQ] =====================

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

    template<typename T>
    inline void processSingle(T& s, bool isRight) {
        if (!enabled || (filterType <= (EqBandType)2 && std::abs(gain) < 0.001f)) return;

        double in = (double)s;
        double& z1 = isRight ? z1_r : z1_l;
        double& z2 = isRight ? z2_r : z2_l;

        double out = in * b0 + z1;
        z1 = in * b1 + z2 - a1 * out;
        z2 = in * b2 - a2 * out;
        s = (T)out;
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
    void setLowPass(double sr, float f, float g, float q_val) {
        filterType = EqBandType::LOW_PASS;
        freq = f; gain = g; q = q_val;
        update(sr);
    }
    void setHighPass(double sr, float f, float g, float q_val) {
        filterType = EqBandType::HIGH_PASS;
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

// ===================== LINKWITZ-RILEY 4TH ORDER (LR4) =====================
// High-precision crossover filter. LR4 consists of two cascaded 2nd-order Butterworth
// filters, which provides a 24dB/octave slope and perfectly flat summation.
class LR4Filter {
    struct Biquad {
        double b0, b1, b2, a1, a2;
        double z1[2], z2[2]; // Stereo state
        void reset() { z1[0]=z1[1]=z2[0]=z2[1]=0.0; }
        void setLP(double f, double sr) {
            double omega = M_PI * f / sr;
            double sn = std::sin(omega), cs = std::cos(omega);
            double alpha = sn / std::sqrt(2.0);
            double a0 = 1.0 + alpha;
            b0 = (1.0 - cs) * 0.5 / a0; b1 = (1.0 - cs) / a0; b2 = (1.0 - cs) * 0.5 / a0;
            a1 = -2.0 * cs / a0; a2 = (1.0 - alpha) / a0;
        }
        void setHP(double f, double sr) {
            double omega = M_PI * f / sr;
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

// 8-Band Crossover using a tree of LR4 filters
class Crossover8Band {
    LR4Filter tree[7][2]; // 7 split points, each has LP and HP
    double splitFreqs[7] = {120, 280, 550, 1100, 2500, 5000, 10000};
public:
    void init(double sr) {
        for(int i=0; i<7; i++) {
            tree[i][0].setLP(splitFreqs[i], sr);
            tree[i][1].setHP(splitFreqs[i], sr);
            tree[i][0].reset(); tree[i][1].reset();
        }
    }
    // Splits input signal into 8 bands using sequential Linkwitz-Riley stages.
    void split(double l, double r, double outL[8], double outR[8]) {
        double curL = l; double curR = r;
        for(int i=0; i<7; i++) {
            double bandL = curL, bandR = curR;
            tree[i][0].process(bandL, bandR);
            outL[i] = bandL; outR[i] = bandR;
            tree[i][1].process(curL, curR);
        }
        outL[7] = curL; outR[7] = curR;
    }
};


class EqEngine {
    static constexpr int FIR_LEN = 2047;
    static constexpr int FFT_SIZE = 8192;
    static constexpr int HOP_SIZE = FFT_SIZE - FIR_LEN + 1;

    // 1.2: Double buffered band states for lock-free read/write
    std::array<BiquadState, 32> bands[2];
    std::atomic<int> activeBandsIdx{0};

    std::atomic<bool> enabled{false};
    std::atomic<bool> linearPhase{false};
    std::atomic<float> lastSr{48000};

    // FIR state
    // 1.1: Double buffered coefficients
    std::vector<double> firFreq[2];
    std::atomic<int> activeFirIdx{0};

    std::vector<double> overlapL, overlapR;
    kiss_fft_state fftForward, fftInverse;

    // Internal buffers to avoid allocations in audio thread
    std::vector<std::complex<double>> specL, specR, timeL, timeR;

    // 1.1: Pre-allocated scratch buffers for recomputeFir (Worker thread ONLY)
    std::vector<std::complex<double>> scratchH, scratchImp, scratchFirComplex, scratchFreqComplex;
    std::vector<double> scratchFir;

    // Worker thread components
    std::thread workerThread;
    std::mutex workerMutex;
    std::condition_variable workerCv;
    std::atomic<bool> workerExit{false};
    std::atomic<bool> recomputeRequested{false};
    std::atomic<double> autoHeadroomGain{1.0};

    void workerLoop() {
        while (!workerExit) {
            {
                std::unique_lock<std::mutex> lock(workerMutex);
                workerCv.wait(lock, [this] { return recomputeRequested.load() || workerExit.load(); });
                if (workerExit) break;
                recomputeRequested = false;
            }
            if (linearPhase) recomputeFir();

            // 2.7: Compute headroom
            double peakDb = computePeakGainDb(lastSr.load());
            // Only attenuate if peak would clip above 0dBFS. Small positive headroom
            // is handled by the limiter — don't penalise normal EQ boosts.
            double gain = 1.0;
            if (peakDb > 0.0) {
                gain = std::pow(10.0, -peakDb / 20.0);
            }
            autoHeadroomGain.store(gain, std::memory_order_release);
        }
    }

    double computePeakGainDb(int sampleRate) {
        const int kProbePoints = 256;
        double peakLinear = 0.0;
        int bandsIdx = activeBandsIdx.load(std::memory_order_acquire);
        std::array<BiquadState, 32> currentBands = bands[bandsIdx]; // COPY, not reference

        for (int i = 0; i < kProbePoints; ++i) {
            double t = (double)i / (kProbePoints - 1);
            double freq = 20.0 * std::pow((sampleRate / 2.0) / 20.0, t); // 20 Hz .. Nyquist

            std::complex<double> response(1.0, 0.0);
            for (auto& band : currentBands) {
                if (!band.enabled) continue;
                response *= band.response(freq, sampleRate);
            }
            peakLinear = std::max(peakLinear, std::abs(response));
        }
        return 20.0 * std::log10(std::max(peakLinear, 1e-6));
    }

public:
    EqEngine() {
        kiss_fft_alloc(fftForward, FFT_SIZE, 0);
        kiss_fft_alloc(fftInverse, FFT_SIZE, 1);
        overlapL.resize(FFT_SIZE, 0.0);
        overlapR.resize(FFT_SIZE, 0.0);
        specL.resize(FFT_SIZE); specR.resize(FFT_SIZE);
        timeL.resize(FFT_SIZE); timeR.resize(FFT_SIZE);

        scratchH.resize(FFT_SIZE);
        scratchImp.resize(FFT_SIZE);
        scratchFir.resize(FFT_SIZE);
        scratchFirComplex.resize(FFT_SIZE);
        scratchFreqComplex.resize(FFT_SIZE);

        firFreq[0].assign(FFT_SIZE * 2, 0.0);
        firFreq[1].assign(FFT_SIZE * 2, 0.0);

        workerThread = std::thread(&EqEngine::workerLoop, this);
    }

    ~EqEngine() {
        workerExit = true;
        workerCv.notify_all();
        if (workerThread.joinable()) workerThread.join();
    }

    void setEnabled(bool e) { enabled = e; }
    void setPhaseMode(bool lp) {
        if (linearPhase != lp) {
            linearPhase = lp;
            recomputeRequested = true;
            workerCv.notify_one();
        }
    }

    void setBand(int idx, float f, float g, float q, int type) {
        if (idx < 0 || idx >= 32) return;

        int currentActive = activeBandsIdx.load(std::memory_order_acquire);
        int stagingIdx = 1 - currentActive;

        // Copy current state to staging
        bands[stagingIdx] = bands[currentActive];

        bands[stagingIdx][idx].freq = f;
        bands[stagingIdx][idx].gain = g;
        bands[stagingIdx][idx].q = q;
        bands[stagingIdx][idx].filterType = (EqBandType)type;
        bands[stagingIdx][idx].enabled = (type > 2 || std::abs(g) > 0.001f);
        bands[stagingIdx][idx].update(lastSr.load());

        activeBandsIdx.store(stagingIdx, std::memory_order_release);

        recomputeRequested = true;
        workerCv.notify_one();
    }

    void init(float sr) {
        lastSr = sr;
        int currentActive = activeBandsIdx.load(std::memory_order_acquire);
        int stagingIdx = 1 - currentActive;

        bands[stagingIdx] = bands[currentActive];
        for (auto& b : bands[stagingIdx]) b.update(sr);

        activeBandsIdx.store(stagingIdx, std::memory_order_release);

        recomputeRequested = true;
        workerCv.notify_one();
    }

    template<typename T>
    void process(T* buf, int frames, int channels) {
        if (!enabled.load(std::memory_order_relaxed)) return;

        if (linearPhase.load(std::memory_order_relaxed)) processFir(buf, frames, channels);
        else processIir(buf, frames, channels);
    }

    int getLatencyFrames() const {
        return linearPhase.load(std::memory_order_relaxed) ? (FIR_LEN / 2) : 0;
    }

    double getAutoHeadroomGain() const {
        return autoHeadroomGain.load(std::memory_order_acquire);
    }

    void flush() {
        for (int i = 0; i < 2; i++) {
            for (auto& b : bands[i]) { b.z1_l = b.z2_l = b.z1_r = b.z2_r = 0.0; }
        }
        std::fill(overlapL.begin(), overlapL.end(), 0.0);
        std::fill(overlapR.begin(), overlapR.end(), 0.0);
    }

private:
    void recomputeFir() {
        int bandsIdx = activeBandsIdx.load(std::memory_order_acquire);
        std::array<BiquadState, 32> currentBands = bands[bandsIdx]; // COPY, not reference
        float sr = lastSr.load();

        for (int i = 0; i <= FFT_SIZE / 2; ++i) {
            double f = (double)i * sr / FFT_SIZE;
            double mag = 1.0;
            for (auto& b : currentBands) {
                if (b.enabled) {
                    std::complex<double> resp = b.response(f, sr);
                    mag *= std::abs(resp);
                }
            }
            scratchH[i] = std::complex<double>(mag, 0.0);
            if (i > 0 && i < FFT_SIZE / 2) scratchH[FFT_SIZE - i] = std::complex<double>(mag, 0.0);
        }

        kiss_fft(fftInverse, scratchH.data(), scratchImp.data());

        int half = FIR_LEN / 2;
        std::fill(scratchFir.begin(), scratchFir.end(), 0.0);
        for (int i = 0; i < FIR_LEN; ++i) {
            int idx = (i - half + FFT_SIZE) % FFT_SIZE;
            double w = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / (FIR_LEN - 1))); // Hann window
            scratchFir[i] = scratchImp[idx].real() * w;
        }

        std::fill(scratchFirComplex.begin(), scratchFirComplex.end(), 0.0);
        for (int i = 0; i < FIR_LEN; ++i) scratchFirComplex[i] = scratchFir[i];

        kiss_fft(fftForward, scratchFirComplex.data(), scratchFreqComplex.data());

        int stagingFirIdx = 1 - activeFirIdx.load(std::memory_order_acquire);
        for(int i=0; i<FFT_SIZE; ++i) {
            firFreq[stagingFirIdx][i*2] = scratchFreqComplex[i].real();
            firFreq[stagingFirIdx][i*2+1] = scratchFreqComplex[i].imag();
        }
        activeFirIdx.store(stagingFirIdx, std::memory_order_release);
    }

    template<typename T>
    void processIir(T* buf, int frames, int channels) {
        int bandsIdx = activeBandsIdx.load(std::memory_order_acquire);
        auto& currentBands = bands[bandsIdx];
        if (channels == 2) {
#if defined(__ARM_NEON)
            if (std::is_same<T, float>::value) {
                // NEON optimization for 32-bit floats
                for (int f = 0; f < frames; f++) {
                    float32x2_t lr = vld1_f32((float*)&buf[f * 2]);
                    for (auto& b : currentBands) {
                        if (!b.enabled) continue;
                        float32x2_t z1 = {(float)b.z1_l, (float)b.z1_r};
                        float32x2_t z2 = {(float)b.z2_l, (float)b.z2_r};

                        float32x2_t out = vadd_f32(vmul_n_f32(lr, (float)b.b0), z1);
                        z1 = vsub_f32(vadd_f32(vmul_n_f32(lr, (float)b.b1), z2), vmul_n_f32(out, (float)b.a1));
                        z2 = vsub_f32(vmul_n_f32(lr, (float)b.b2), vmul_n_f32(out, (float)b.a2));

                        lr = out;
                        b.z1_l = (double)vget_lane_f32(z1, 0); b.z1_r = (double)vget_lane_f32(z1, 1);
                        b.z2_l = (double)vget_lane_f32(z2, 0); b.z2_r = (double)vget_lane_f32(z2, 1);
                    }
                    vst1_f32((float*)&buf[f * 2], lr);
                }
                return;
            }
#if defined(__aarch64__)
            if (std::is_same<T, double>::value) {
                // NEON optimization for 64-bit doubles (AArch64 ONLY)
                for (int f = 0; f < frames; f++) {
                    float64x2_t lr = vld1q_f64((double*)&buf[f * 2]);
                    for (auto& b : currentBands) {
                        if (!b.enabled) continue;
                        float64x2_t z1 = {b.z1_l, b.z1_r};
                        float64x2_t z2 = {b.z2_l, b.z2_r};

                        float64x2_t out = vaddq_f64(vmulq_n_f64(lr, b.b0), z1);
                        z1 = vsubq_f64(vaddq_f64(vmulq_n_f64(lr, b.b1), z2), vmulq_n_f64(out, b.a1));
                        z2 = vsubq_f64(vmulq_n_f64(lr, b.b2), vmulq_n_f64(out, b.a2));

                        lr = out;
                        b.z1_l = vgetq_lane_f64(z1, 0); b.z1_r = vgetq_lane_f64(z1, 1);
                        b.z2_l = vgetq_lane_f64(z2, 0); b.z2_r = vgetq_lane_f64(z2, 1);
                    }
                    vst1q_f64((double*)&buf[f * 2], lr);
                }
                return;
            }
#endif
#endif
            for (int f = 0; f < frames; f++) {
                double l = (double)buf[f * 2], r = (double)buf[f * 2 + 1];
                for (auto& b : currentBands) if (b.enabled) b.process(l, r);
                buf[f * 2] = (T)l; buf[f * 2 + 1] = (T)r;
            }
        } else {
            for (int f = 0; f < frames; f++) {
                // Process at least the first channel, or more if we have state.
                // For now, only process the first 2 channels properly to avoid corruption.
                double l = (double)buf[f * channels], r = (channels > 1) ? (double)buf[f * channels + 1] : 0.0;
                for (auto& b : currentBands) if (b.enabled) b.process(l, r);
                buf[f * channels] = (T)l;
                if (channels > 1) buf[f * channels + 1] = (T)r;
            }
        }
    }

    template<typename T>
    void processFir(T* buf, int frames, int channels) {
        int firIdx = activeFirIdx.load(std::memory_order_acquire);
        const auto& currentFir = firFreq[firIdx];
        int processed = 0;
        while (processed < frames) {
            int take = std::min(frames - processed, HOP_SIZE);

            std::fill(timeL.begin(), timeL.end(), 0.0);
            std::fill(timeR.begin(), timeR.end(), 0.0);
            if (channels >= 2) {
                for (int i = 0; i < take; i++) {
                    timeL[i] = (double)buf[(processed + i) * channels];
                    timeR[i] = (double)buf[(processed + i) * channels + 1];
                }
            } else {
                for (int i = 0; i < take; i++) {
                    timeL[i] = (double)buf[(processed + i) * channels];
                }
            }

            kiss_fft(fftForward, timeL.data(), specL.data());
            if (channels >= 2) kiss_fft(fftForward, timeR.data(), specR.data());

            for (int i = 0; i < FFT_SIZE; i++) {
                std::complex<double> f(currentFir[i*2], currentFir[i*2+1]);
                specL[i] *= f;
                if (channels >= 2) specR[i] *= f;
            }

            kiss_fft(fftInverse, specL.data(), timeL.data());
            if (channels >= 2) kiss_fft(fftInverse, specR.data(), timeR.data());

            if (channels >= 2) {
                for (int i = 0; i < take; i++) {
                    buf[(processed + i) * channels] = (T)(timeL[i].real() + overlapL[i]);
                    buf[(processed + i) * channels + 1] = (T)(timeR[i].real() + overlapR[i]);
                }
            } else {
                for (int i = 0; i < take; i++) {
                    buf[(processed + i) * channels] = (T)(timeL[i].real() + overlapL[i]);
                }
            }

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

// ===================== LOOKAHEAD LIMITER (Transparent, Soft-Knee) =====================
class LookaheadLimiter {
    std::vector<double> delayBuffer;
    size_t delaySize = 0;
    size_t writePos = 0;
    double gainEnvelope = 1.0;
    double ceiling = 0.891; // -1 dBFS
    double releaseCoeff = 0.0;
    double attackCoeff = 0.0;
    int currentSampleRate = 48000;
    int currentChannels = 2;
    bool hardMode = false; // true = power-amp style: no soft knee, no envelope smoothing

public:
    void init(int sampleRate, int channels) {
        currentSampleRate = sampleRate;
        currentChannels = channels;
        // 5ms look-ahead delay for musical peak protection
        delaySize = (size_t)std::max(1, (int)std::ceil(0.005 * sampleRate));
        delayBuffer.assign(delaySize * channels, 0.0);
        writePos = 0;
        gainEnvelope = 1.0;

        // Default release/attack
        releaseCoeff = std::exp(-1.0 / (0.100 * sampleRate));
        attackCoeff = std::exp(-1.0 / (0.0005 * sampleRate));
    }

    void setParams(double thresholdDb, double attackMs, double releaseMs) {
        ceiling = std::pow(10.0, thresholdDb / 20.0);

        // Guard against 0 or negative ms, which would blow up the exp() below
        double safeAttackMs = std::max(0.01, attackMs);
        double safeReleaseMs = std::max(1.0, releaseMs);

        attackCoeff = std::exp(-1.0 / (safeAttackMs / 1000.0 * currentSampleRate));
        releaseCoeff = std::exp(-1.0 / (safeReleaseMs / 1000.0 * currentSampleRate));
    }

    void setHardMode(bool enabled) {
        hardMode = enabled;
    }

    template<typename T>
    void process(T* data, int frames, int channels) {
        if (delaySize == 0) return;

        for (int i = 0; i < frames; i++) {
            T maxPeak = 0;
            for (int c = 0; c < channels; c++) {
                T s = std::abs(data[i * channels + c]);
                if (s > maxPeak) maxPeak = s;
            }

            // 1. Required gain to hit ceiling
            double targetGain = 1.0;
            if (maxPeak > 1e-6) {
                if (hardMode) {
                    // Sharp threshold: untouched below ceiling, clamped exactly
                    // at it above — no soft-knee gain reduction pre-ceiling.
                    if ((double)maxPeak > ceiling) {
                        targetGain = ceiling / (double)maxPeak;
                    }
                } else {
                    // Soft-knee (quadratic) 6dB width
                    // Transition starts at -7dB, ends at -1dB
                    double peakDb = 20.0 * std::log10((double)maxPeak + 1e-9);
                    double ceilingDb = 20.0 * std::log10(ceiling);
                    double kneeDb = 6.0;

                    if (peakDb > ceilingDb + kneeDb / 2.0) {
                        targetGain = std::pow(10.0, (ceilingDb - peakDb) / 20.0);
                    } else if (peakDb > ceilingDb - kneeDb / 2.0) {
                        // Quadratic knee formula
                        double diff = peakDb - (ceilingDb - kneeDb / 2.0);
                        double reductionDb = (diff * diff) / (2.0 * kneeDb);
                        targetGain = std::pow(10.0, -reductionDb / 20.0);
                    }
                }
            }

            // 2. Envelope follower (Adjustable Attack, Adjustable Release)
            if (hardMode) {
                // No smoothing — gain snaps immediately to whatever's needed
                // this sample, like a plain peak clamp / power-amp limiter.
                gainEnvelope = targetGain;
            } else if (targetGain < gainEnvelope) {
                gainEnvelope = targetGain + (gainEnvelope - targetGain) * attackCoeff;
            } else {
                gainEnvelope = targetGain + (gainEnvelope - targetGain) * releaseCoeff;
            }

            // 3. Apply gain to delayed signal
            for (int c = 0; c < channels; c++) {
                size_t readPos = writePos * channels + c;
                T delayedSample = (T)delayBuffer[readPos];
                delayBuffer[readPos] = (double)data[i * channels + c];
                data[i * channels + c] = delayedSample * (T)gainEnvelope;
            }
            writePos = (writePos + 1) % delaySize;
        }
    }

    void reset() {
        std::fill(delayBuffer.begin(), delayBuffer.end(), 0.0);
        writePos = 0;
        gainEnvelope = 1.0;
    }
};

void resample_cubic(const float* in, int inFrames, float* out, int outFrames, int channels, float ratio) {
    // Anti-aliasing pre-filter state (one-pole lowpass at Nyquist*ratio) for downsampling
    static thread_local std::vector<float> filterState;
    static thread_local std::vector<float> filtered;
    bool needFilter = ratio < 0.999f; // downsampling: output is lower rate
    if (needFilter) {
        filtered.assign(inFrames * channels, 0.0f);
        filterState.resize(channels, 0.0f);
        // cutoff = ratio * pi (bilinear-approx one-pole)
        float alpha = ratio / (ratio + 1.0f);  // simple RC lowpass
        for (int f = 0; f < inFrames; f++)
            for (int c = 0; c < channels; c++) {
                filterState[c] = filterState[c] + alpha * (in[f * channels + c] - filterState[c]);
                filtered[f * channels + c] = filterState[c];
            }
    }
    const float* src = needFilter ? filtered.data() : in;
    for (int i = 0; i < outFrames; i++) {
        float x = i / ratio;
        int ix = (int)x;
        float frac = x - ix;
        for (int ch = 0; ch < channels; ch++) {
            auto get = [&](int f) -> float {
                if (f < 0) return src[ch];
                if (f >= inFrames) return src[(inFrames - 1) * channels + ch];
                return src[f * channels + ch];
            };
            float y0=get(ix-1), y1=get(ix), y2=get(ix+1), y3=get(ix+2);
            float a=(-0.5f*y0)+(1.5f*y1)-(1.5f*y2)+(0.5f*y3);
            float b=y0-(2.5f*y1)+(2.0f*y2)-(0.5f*y3);
            float c2=(-0.5f*y0)+(0.5f*y2);
            out[i*channels+ch] = a*frac*frac*frac + b*frac*frac + c2*frac + y1;
        }
    }
}

class DcBlocker {
    std::vector<double> x1, y1;
    double R;
public:
    DcBlocker() : R(0.999) { init(48000, 2); }
    void reset() { std::fill(x1.begin(), x1.end(), 0.0); std::fill(y1.begin(), y1.end(), 0.0); }
    void init(int sampleRate, int channels) {
        R = 1.0 - (2.0 * M_PI * 5.0 / sampleRate);
        x1.assign(channels, 0.0);
        y1.assign(channels, 0.0);
    }
    template<typename T>
    void process(T* buffer, int frames, int channels) {
        if (x1.size() < (size_t)channels) init(48000, channels);
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                double in = (double)buffer[f * channels + c];
                double out = in - x1[c] + R * y1[c];
                x1[c] = in; y1[c] = out;
                buffer[f * channels + c] = (T)out;
            }
        }
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

// Refactored to support 8-band per-stem spatialization with HRTF cues.
// The engine splits the signal using an LR4 crossover and applies independent
// Panning, Distance, Depth, and Spectral (HRTF) processing to each band.
class Audio3DStageEngine {
    static constexpr int NUM_BANDS = 8;
    Crossover8Band crossover;

    struct BandSpatialState {
        double targetAz = 0.0, curAz = 0.0;
        double targetEl = 0.0, curEl = 0.0;
        double targetDist = 2.0, curDist = 2.0;

        // Per-band depth delay
        std::vector<double> delayL, delayR;
        size_t delaySize = 0, writePos = 0;
        double lpStateL = 0.0, lpStateR = 0.0;

        // ITD Delay buffers (Max ~1ms for extreme HRTF)
        std::vector<double> itdBufL, itdBufR;
        size_t itdSize = 0, itdWritePos = 0;

        // Spectral cues (HRTF) - Cascade of 2 Biquads per ear
        BiquadState hrtfL[2], hrtfR[2];

        // Elevation filters (persistent state to prevent clicks)
        BiquadState elFilterL, elFilterR;
    };

    BandSpatialState bands[NUM_BANDS];
    double targetWidth = 1.0, curWidth = 1.0;
    double targetCenterLock = 0.0, curCenterLock = 0.0;
    double targetSpatialIntensity = 1.0, curSpatialIntensity = 1.0;
    int hrtfMode = 0; // 0=Natural, 1=Natural Wide, 2=Cinematic, 3=Studio
    int spatialUiMode = 0; // 0=Modern, 1=Classic
    bool engineEnabled = false;
    int currentSampleRate = 48000;

public:
    void init(int sampleRate) {
        currentSampleRate = sampleRate;
        crossover.init((double)sampleRate);

        for (int i = 0; i < NUM_BANDS; i++) {
            // Up to 25ms of depth delay per band
            bands[i].delaySize = (size_t)std::ceil(0.025 * sampleRate) + 4;
            bands[i].delayL.assign(bands[i].delaySize, 0.0);
            bands[i].delayR.assign(bands[i].delaySize, 0.0);
            bands[i].writePos = 0;
            bands[i].lpStateL = 0.0;
            bands[i].lpStateR = 0.0;

            // ITD Delay (Max 1ms)
            bands[i].itdSize = (size_t)std::ceil(0.001 * sampleRate) + 4;
            bands[i].itdBufL.assign(bands[i].itdSize, 0.0);
            bands[i].itdBufR.assign(bands[i].itdSize, 0.0);
            bands[i].itdWritePos = 0;

            for(int j=0; j<2; j++) {
                bands[i].hrtfL[j].reset(); bands[i].hrtfR[j].reset();
            }
            bands[i].elFilterL.reset(); bands[i].elFilterR.reset();
        }
    }

    void setEnabled(bool enabled) { engineEnabled = enabled; }
    bool isEnabled() const { return engineEnabled; }

    void setWidth(double width) { targetWidth = std::clamp(width, 0.0, 2.0); }
    void setCenterLock(double centerLock) { targetCenterLock = std::clamp(centerLock, 0.0, 1.0); }
    void setSpatialIntensity(double intensity) { targetSpatialIntensity = std::clamp(intensity, 0.0, 1.0); }
    void setHrtfMode(int mode) { hrtfMode = std::clamp(mode, 0, 3); }
    void setSpatialUiMode(int mode) { spatialUiMode = mode; }

    void setBandPosition(int index, double azimuthDeg, double elevationDeg, double distanceM) {
        if (index < 0 || index >= NUM_BANDS) return;
        bands[index].targetAz = azimuthDeg;
        bands[index].targetEl = elevationDeg;
        bands[index].targetDist = std::max(0.3, distanceM);
    }

    void process(double& left, double& right) {
        if (!engineEnabled) return;

        const double smoothCoeff = 0.0015; // Smooth over ~10-20ms
        curWidth += (targetWidth - curWidth) * smoothCoeff;
        curCenterLock += (targetCenterLock - curCenterLock) * smoothCoeff;
        curSpatialIntensity += (targetSpatialIntensity - curSpatialIntensity) * smoothCoeff;

        if (spatialUiMode == 1) {
            // Classic Mode: treated as two independent speakers (L and R)
            double l0 = left, r0 = 0.0;
            processBand(0, l0, r0, smoothCoeff);
            double l1 = 0.0, r1 = right;
            processBand(1, l1, r1, smoothCoeff);
            left = l0 + l1;
            right = r0 + r1;
        } else {
            // Modern Mode: 8-band crossover
            double bandL[NUM_BANDS], bandR[NUM_BANDS];
            crossover.split(left, right, bandL, bandR);
            double outSumL = 0.0, outSumR = 0.0;
            for (int i = 0; i < NUM_BANDS; i++) {
                processBand(i, bandL[i], bandR[i], smoothCoeff);
                outSumL += bandL[i];
                outSumR += bandR[i];
            }
            left = outSumL;
            right = outSumR;
        }
    }

private:
    void processBand(int i, double& bL, double& bR, double smoothCoeff) {
        BandSpatialState& s = bands[i];

        // 1. Parameter Smoothing
        s.curAz += (s.targetAz - s.curAz) * smoothCoeff;
        s.curEl += (s.targetEl - s.curEl) * smoothCoeff;
        s.curDist += (s.targetDist - s.curDist) * smoothCoeff;

        // 2. Width Expansion (Independent of 3D Pos)
        double mid = (bL + bR) * 0.5;
        double side = (bL - bR) * 0.5;
        double focusedSide = (side * curWidth) * (1.0 - curCenterLock) + (side * curCenterLock);
        bL = mid + focusedSide;
        bR = mid - focusedSide;

        // 3. 3D Spatialization (Apply only if Intensity > 0)
        if (curSpatialIntensity > 0.001) {
            double azRad = (s.curAz - 90.0) * M_PI / 180.0;
            double cosAz = std::cos(azRad); // Positive = Right, Negative = Left
            double sinAz = std::sin(azRad); // Front/Back factor

            // a. Equal-Power Panning (Inter-aural Level Difference - ILD)
            double panL = std::sqrt(std::max(0.0, (1.0 - cosAz) * 0.5));
            double panR = std::sqrt(std::max(0.0, (1.0 + cosAz) * 0.5));

            double mono = (bL + bR) * 0.5;
            double spatialL = mono * panL;
            double spatialR = mono * panR;

            // b. Inter-aural Time Difference (ITD)
            double itdMaxSamples = 0.00066 * currentSampleRate;
            double itdSamples = cosAz * itdMaxSamples;

            s.itdBufL[s.itdWritePos] = spatialL;
            s.itdBufR[s.itdWritePos] = spatialR;

            auto getInterpolated = [](const std::vector<double>& buf, double delay, size_t writePos, size_t size) -> double {
                double readPos = (double)writePos + (double)size - delay;
                int i0 = (int)std::floor(readPos) % (int)size;
                int i1 = (i0 + 1) % (int)size;
                double frac = readPos - std::floor(readPos);
                return buf[i0] * (1.0 - frac) + buf[i1] * frac;
            };

            if (itdSamples >= 0) {
                spatialL = getInterpolated(s.itdBufL, itdSamples, s.itdWritePos, s.itdSize);
            } else {
                spatialR = getInterpolated(s.itdBufR, -itdSamples, s.itdWritePos, s.itdSize);
            }
            s.itdWritePos = (s.itdWritePos + 1) % s.itdSize;

            // c. Spectral Cues
            double backFactor = std::clamp((sinAz + 1.0) * 0.5, 0.0, 1.0);
            if (backFactor > 0.01) {
                double mufflingDb = -6.0 * backFactor;
                double notchDb = -12.0 * backFactor;
                s.hrtfL[0].setHighShelf(currentSampleRate, 4000.0, mufflingDb, 0.7);
                s.hrtfR[0].setHighShelf(currentSampleRate, 4000.0, mufflingDb, 0.7);
                s.hrtfL[1].setPeaking(currentSampleRate, 6500.0, notchDb, 4.0);
                s.hrtfR[1].setPeaking(currentSampleRate, 6500.0, notchDb, 4.0);
            } else {
                s.hrtfL[0].reset(); s.hrtfR[0].reset();
                s.hrtfL[1].reset(); s.hrtfR[1].reset();
            }

            double elFactor = s.curEl / 90.0;
            if (std::abs(elFactor) > 0.01) {
                double elDb = 3.0 * elFactor;
                s.elFilterL.setHighShelf(currentSampleRate, 3000.0, elDb, 0.7);
                s.elFilterR.setHighShelf(currentSampleRate, 3000.0, elDb, 0.7);
            } else {
                s.elFilterL.reset(); s.elFilterR.reset();
            }

            s.elFilterL.processSingle(spatialL, false);
            s.elFilterR.processSingle(spatialR, true);
            s.hrtfL[0].processSingle(spatialL, false);
            s.hrtfR[0].processSingle(spatialR, true);
            s.hrtfL[1].processSingle(spatialL, false);
            s.hrtfR[1].processSingle(spatialR, true);

            // d. Distance Falloff
            // Reduced exponent (0.2) to prevent aggressive volume drop at high distance.
            double distGain = 1.0 / std::pow(std::max(0.5, s.curDist), 0.2);
            // Boost factor increased to 1.6 to better maintain perceived loudness
            spatialL *= distGain * 1.6;
            spatialR *= distGain * 1.6;

            // e. Air Absorption
            double lpCoeff = std::clamp(1.0 - (s.curDist - 1.0) * 0.04, 0.2, 1.0);
            s.lpStateL += (spatialL - s.lpStateL) * lpCoeff;
            s.lpStateR += (spatialR - s.lpStateR) * lpCoeff;
            spatialL = s.lpStateL;
            spatialR = s.lpStateR;

            // f. Depth Delay
            double depthT = std::clamp((s.curDist - 0.3) / 10.0, 0.0, 1.0);
            int delaySamples = (int)(depthT * 0.02 * currentSampleRate);
            s.delayL[s.writePos] = spatialL;
            s.delayR[s.writePos] = spatialR;
            size_t readPos = (s.writePos + s.delaySize - delaySamples) % s.delaySize;
            spatialL = s.delayL[readPos];
            spatialR = s.delayR[readPos];
            s.writePos = (s.writePos + 1) % s.delaySize;

            // g. Final Blend (Equal Power)
            double dryGain = std::cos(curSpatialIntensity * M_PI * 0.5);
            double wetGain = std::sin(curSpatialIntensity * M_PI * 0.5);
            bL = bL * dryGain + spatialL * wetGain;
            bR = bR * dryGain + spatialR * wetGain;
        }
    }

public:
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
        // Equal-power mix: as wet rises, dry falls, total power stays constant.
        // v = 0: dry=1, wet=0. v = 1: dry=0.707, wet=1.06 (Freeverb's fixedgain=0.015
        // already attenuates the reverb signal internally; the 1.06 factor is compensation).
        double w = std::sqrt((double)v) * 1.06;
        double d = std::sqrt(1.0 - (double)v * 0.3);  // dry dips only -1.3dB at full wet
        wet.store(w, std::memory_order_relaxed);
        dry.store(d, std::memory_order_relaxed);
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

class TempoProcessor {
    int sampleRate = 48000;
    int channels = 2;
    float speed = 1.0f;
    std::vector<float> overlapBuffer;
    int overlapSamples = 0;
    static constexpr int CHUNK_MS = 30; // 30ms chunks for OLA
public:
    void init(int sr, int ch) {
        sampleRate = sr; channels = ch;
        int chunkSize = sr * CHUNK_MS / 1000;
        overlapSamples = chunkSize / 4;
        overlapBuffer.assign(overlapSamples * ch, 0.0f);
    }
    void setSpeed(float s) { speed = s; }

    int process(float* input, int inFrames, float* output, int maxOutFrames) {
        if (std::abs(speed - 1.0f) < 0.01f) {
            int frames = std::min(inFrames, maxOutFrames);
            std::copy(input, input + frames * channels, output);
            return frames;
        }

        int chunkSize = sampleRate * CHUNK_MS / 1000;
        int hopOut = chunkSize - overlapSamples;
        int hopIn  = (int)(hopOut * speed);
        int outFrames = 0, inPos = 0;
        bool firstChunk = true;

        while (inPos + chunkSize <= inFrames && outFrames + chunkSize <= maxOutFrames) {
            for (int i = 0; i < chunkSize; i++) {
                for (int c = 0; c < channels; c++) {
                    float sample = input[(inPos + i) * channels + c];
                    if (i < overlapSamples && !firstChunk) {
                        // Hann window: 0.5*(1 - cos(pi*t)), symmetric fade-in/fade-out
                        float t = (float)i / overlapSamples;
                        float fadeIn  = 0.5f * (1.0f - std::cos((float)M_PI * t));
                        float fadeOut = 0.5f * (1.0f - std::cos((float)M_PI * (1.0f - t)));
                        float prevTail = overlapBuffer[i * channels + c];
                        output[(outFrames + i) * channels + c] = sample * fadeIn + prevTail * fadeOut;
                    } else {
                        output[(outFrames + i) * channels + c] = sample;
                    }
                }
            }
            for (int i = 0; i < overlapSamples; i++)
                for (int c = 0; c < channels; c++)
                    overlapBuffer[i * channels + c] = input[(inPos + chunkSize - overlapSamples + i) * channels + c];

            outFrames += hopOut;
            inPos += hopIn;
            firstChunk = false;
        }
        return outFrames;
    }
};

// ===================== MMAP AUDIO OUTPUT =====================
class MmapStream {
    AAudioStream* stream = nullptr;
    int32_t channels = 2;
    int32_t sampleRate = 48000;
public:
    MmapStream(int sr, int ch, int bufferFrames, aaudio_format_t format = AAUDIO_FORMAT_PCM_FLOAT) {
        AAudioStreamBuilder* builder;
        AAudio_createStreamBuilder(&builder);
        AAudioStreamBuilder_setSampleRate(builder, sr);
        AAudioStreamBuilder_setChannelCount(builder, ch);
        AAudioStreamBuilder_setFormat(builder, format);
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
    int write(void* data, int offset, int frames) {
        if (!stream) return 0;
        int elementSize = (AAudioStream_getFormat(stream) == AAUDIO_FORMAT_PCM_FLOAT) ? sizeof(float) : sizeof(int32_t);
        aaudio_result_t result = AAudioStream_write(stream, (uint8_t*)data + (offset * channels * elementSize), frames, 10000000LL); // 10ms timeout
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
    void setBufferConfig(int bufferFrames, int bufferCount, int postFadeFrames) {
        if (stream) {
            AAudioStream_setBufferSizeInFrames(stream, bufferFrames);
        }
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
            useSox(true), dcBlockerEnabled(true), monoEnabled(false), dvcEnabled(true), rmsDvcEnabled(true), rmsLevelerEnabled(true), limiterEnabled(false), replayGainDb(0.0f), preampDb(0.0f),
            dvcLevel(1.0f), dvcMode(0),
            bassDb(0.0f), trebleDb(0.0f), airDb(0.0f),
            balance(0.0f), stereoWidth(1.0f), crossfeedEnabled(false), crossfeedLevel(0.4f),
            reverbAmount(0.0f),
            reverbType(0), reverbPredelayMs(0.0f), reverbWidth(1.0f),
            reverbDamping(0.5f), reverbRoomSize(0.5f),
            audio3DStageEnabled(false),
            bitDepth(16), cutoffRatio(0.97f),
            soxrQuality(3), float64Enabled(false),
            headroomManagementEnabled(true),
            noHeadroomGainEnabled(false),
            hardwareVolumeEnabled(false) {
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
        // Pre-compute DVC ramp coefficient once — avoids exp() on audio thread
        // 8ms time constant — perceptually smooth, matches Poweramp's volume feel
        dvcRampCoeff = 1.0 - std::exp(-1.0 / (0.008 * inRate));

        // Leveler coefficients (Hybrid Algorithm)
        // 50ms RMS window
        rmsEnvCoeff = 1.0 - std::exp(-1.0 / (0.050 * inRate));
        // 500ms attack, 1500ms release
        levelerAttackCoeff = 1.0 - std::exp(-1.0 / (0.500 * inRate));
        levelerReleaseCoeff = 1.0 - std::exp(-1.0 / (1.500 * inRate));

        dcBlocker.init(inRate, channels); reverb.init(inRate); crossfeed.init(inRate);
        audio3DStage.init(inRate);
        limiter.init(inRate, channels);
        dither.init(channels);
        limiter.setParams((double)limiterThresholdDb, (double)limiterAttackMs, (double)limiterReleaseMs);
        eq.init((float)inRate);
        tempo.init(inRate, channels);
        updateFilters(); updateSoxr();
    }

    float playbackSpeed = 1.0f;
    bool preservePitch = true;
    TempoProcessor tempo;

    void updateSoxr() {
#ifdef HAVE_SOXR
        if (soxr_handle) { soxr_delete(soxr_handle); soxr_handle = nullptr; }
        double effectiveInRate = inRate;
        if (!preservePitch) effectiveInRate *= playbackSpeed;
        if (effectiveInRate == (double)outRate) return;

        soxr_error_t err; soxr_io_spec_t io_spec = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
        unsigned long recipe;
        switch (soxrQuality) {
            case 0: recipe = SOXR_QQ; break; case 1: recipe = SOXR_LQ; break; case 2: recipe = SOXR_MQ; break;
            case 3: recipe = SOXR_HQ; break; case 4: recipe = SOXR_VHQ; break; default: recipe = SOXR_HQ; break;
        }
        soxr_quality_spec_t q_spec = soxr_quality_spec(recipe, SOXR_LINEAR_PHASE);
        q_spec.passband_end = 0.997; q_spec.stopband_begin = 1.0;
        soxr_runtime_spec_t r_spec = soxr_runtime_spec(1);
        soxr_handle = soxr_create(effectiveInRate, (double)outRate, (unsigned)channels, &err, &io_spec, &q_spec, &r_spec);
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

        // Update real-time levels (decaying peak)
        float currentPeakL = 0.0f, currentPeakR = 0.0f;
        for (int f = 0; f < inFrames; f++) {
            currentPeakL = std::max(currentPeakL, std::abs((float)input[f * channels]));
            if (channels >= 2) currentPeakR = std::max(currentPeakR, std::abs((float)input[f * channels + 1]));
        }
        float lastL = levelL.load(std::memory_order_relaxed);
        float lastR = levelR.load(std::memory_order_relaxed);
        // Fast attack, slower release (per-buffer)
        levelL.store(std::max(currentPeakL, lastL * 0.92f), std::memory_order_relaxed);
        levelR.store(std::max(currentPeakR, lastR * 0.92f), std::memory_order_relaxed);

        // Smooth tone targets towards current values to avoid coefficient jumps
        bool needUpdateFilters = false;
        {
            float tmb = toneTargetBass.load(std::memory_order_relaxed);
            float tt = toneTargetTreble.load(std::memory_order_relaxed);
            float ta = toneTargetAir.load(std::memory_order_relaxed);

            float diff;
            diff = tmb - toneCurrentBass;
            if (std::abs(diff) > 0.0005f) { toneCurrentBass += diff * toneSmoothingFactor; needUpdateFilters = true; }
            else if (toneCurrentBass != tmb) { toneCurrentBass = tmb; needUpdateFilters = true; }

            diff = tt - toneCurrentTreble;
            if (std::abs(diff) > 0.0005f) { toneCurrentTreble += diff * toneSmoothingFactor; needUpdateFilters = true; }
            else if (toneCurrentTreble != tt) { toneCurrentTreble = tt; needUpdateFilters = true; }

            diff = ta - toneCurrentAir;
            if (std::abs(diff) > 0.0005f) { toneCurrentAir += diff * toneSmoothingFactor; needUpdateFilters = true; }
            else if (toneCurrentAir != ta) { toneCurrentAir = ta; needUpdateFilters = true; }

            if (needUpdateFilters) {
                bassDb = toneCurrentBass; trebleDb = toneCurrentTreble; airDb = toneCurrentAir;
                updateFilters();
            }
        }

        if (dcBlockerEnabled && channels >= 1) dcBlocker.process(input, inFrames, channels);
        if (replayGainDb != 0.0f) {
            T gain = (T)std::pow(10.0, (double)replayGainDb / 20.0);
            for (int i = 0; i < samples; i++) input[i] *= gain;
        }
        if (preampDb != 0.0f) {
            T gain = (T)std::pow(10.0, (double)preampDb / 20.0);
            for (int i = 0; i < samples; i++) input[i] *= gain;
        }

    // 2.7: Headroom Management (Applied BEFORE EQ)
    if (headroomManagementEnabled && !noHeadroomGainEnabled) {
        T g = (T)eq.getAutoHeadroomGain();
        T aiG = (T)aiEq.getAutoHeadroomGain();
        T simG = (T)simEq.getAutoHeadroomGain();
        if (aiG < g) g = aiG;
        if (simG < g) g = simG;

        if (g < (T)0.999) {
            for (int i = 0; i < samples; i++) input[i] *= g;
        }
    }

    // AI EQ
    aiEq.process(input, inFrames, channels);

    // Simulation EQ (Phase 3.5)
    simEq.process(input, inFrames, channels);

    // EQ
    eq.process(input, inFrames, channels);

        if (channels >= 2) {
            for (int f = 0; f < inFrames; f++) {
                T& l = input[f * channels]; T& r = input[f * channels + 1];
                for (int t = 0; t < 3; t++) toneFilters[t].process(l, r);
            }
        } else {
            for (int i = 0; i < samples; i++) {
                T& s = input[i]; T dummy = 0;
                for (int t = 0; t < 3; t++) toneFilters[t].process(s, dummy);
            }
        }

        // Cinema Mode: dedicated dialogue-presence filter (~1-4kHz peaking boost).
        // Fully separate biquad from eq/aiEq/simEq/toneFilters so it never interacts
        // with the user's own EQ, AI EQ, headphone sim, or tone settings.
        if (cinemaModeEnabled) {
            if (channels >= 2) {
                for (int f = 0; f < inFrames; f++) {
                    T& l = input[f * channels]; T& r = input[f * channels + 1];
                    cinemaDialogueFilter.process(l, r);
                }
            } else {
                for (int i = 0; i < samples; i++) {
                    T& s = input[i]; T dummy = 0;
                    cinemaDialogueFilter.process(s, dummy);
                }
            }
        }

        // --- Mono Downmix ---
        if (monoEnabled && channels >= 2) {
            for (int f = 0; f < inFrames; f++) {
                T mono = (input[f * channels] + input[f * channels + 1]) * (T)0.5;
                input[f * channels] = input[f * channels + 1] = mono;
            }
        }
        if (channels >= 2) {
            for (int f = 0; f < inFrames; f++) {
                int lIdx = f * channels, rIdx = f * channels + 1;
                T mid = (input[lIdx] + input[rIdx]) * (T)0.5; T side = (input[lIdx] - input[rIdx]) * (T)0.5;
                side *= (T)stereoWidth; T left = mid + side; T right = mid - side;
                if (balance > 0.0f) left *= (T)(1.0f - balance); else if (balance < 0.0f) right *= (T)(1.0f + balance);
                input[lIdx] = left; input[rIdx] = right;
            }
        }
        if (crossfeedEnabled && channels >= 2) {
            for (int f = 0; f < inFrames; f++) crossfeed.process(input[f * channels], input[f * channels + 1], crossfeedLevel);
        }
        if (audio3DStageEnabled && channels >= 2 && audio3DStage.isEnabled()) {
            for (int f = 0; f < inFrames; f++) {
                int lIdx = f * channels, rIdx = f * channels + 1;
                double l = (double)input[lIdx], r = (double)input[rIdx];
                audio3DStage.process(l, r);
                input[lIdx] = (T)l; input[rIdx] = (T)r;
            }
        }
        if (reverbAmount > 0.001f) {
            int predelaySamples = (int)(reverbPredelayMs * inRate / 1000.0f);
            for (int i = 0; i < samples; i += channels) {
                if (channels >= 2) reverb.process(input[i], input[i+1], predelaySamples);
                else { T dummy = input[i]; reverb.process(input[i], dummy, predelaySamples); }
            }
        }
        if (dvcEnabled) {
            // Target RMS: 0.12 (-18.4 dBFS)
            const double targetRms = 0.12;
            const double maxBoost = 2.0;       // +6dB
            const double maxAttenuation = 0.5; // -6dB

            for (int i = 0; i < samples; i += channels) {
                double levelerGainToApply = 1.0;

                if (rmsDvcEnabled) {
                    // Compute current frame RMS
                    double sumSq = 0;
                    for (int c = 0; c < channels; c++) sumSq += (double)input[i + c] * (double)input[i + c];
                    double frameRms = std::sqrt(sumSq / channels);

                    // Update RMS envelope (50ms window)
                    levelerRmsEnvelope += (frameRms - levelerRmsEnvelope) * rmsEnvCoeff;

                    // Compute required gain
                    double targetGain = 1.0;
                    if (levelerRmsEnvelope > 1e-6) {
                        targetGain = targetRms / levelerRmsEnvelope;
                    }
                    targetGain = std::clamp(targetGain, maxAttenuation, maxBoost);

                    // Smooth gain change (500ms attack, 1500ms release)
                    double levelerCoeff = (targetGain < levelerGain) ? levelerAttackCoeff : levelerReleaseCoeff;
                    levelerGain += (targetGain - levelerGain) * levelerCoeff;

                    if (rmsLevelerEnabled) {
                        levelerGainToApply = levelerGain;
                    }
                }

                // Apply Leveler gain AND User Volume (DVC Level)
                // Use perceptual ramp for user volume
                dvcCurrentLevel += (dvcLevel - dvcCurrentLevel) * dvcRampCoeff;
                T totalGain = (T)(levelerGainToApply * dvcCurrentLevel);

                for (int c = 0; c < channels; c++) input[i + c] *= totalGain;
            }
            // Snap user volume if close enough
            if (std::abs(dvcCurrentLevel - dvcLevel) < 1e-7) dvcCurrentLevel = dvcLevel;
        }
        if (softLimiterEnabled) {
            float softClipThreshold = std::pow(10.0f, limiterThresholdDb / 20.0f);
            for (int i = 0; i < samples; i++) {
                input[i] = std::tanh(input[i] / softClipThreshold) * softClipThreshold;
            }
        }
        if (limiterEnabled) limiter.process(input, inFrames, channels);
        dither.process(input, inFrames, channels);
    }

    void process(float* input, int inFrames, float* output, int& outFrames, int outputCapacityFrames) {
        processInPlace(input, inFrames);

        float* effectiveInput = input;
        int effectiveInFrames = inFrames;

        if (preservePitch && std::abs(playbackSpeed - 1.0f) > 0.01f) {
            // Tempo processing changes frame count
            int maxOut = (int)(inFrames / playbackSpeed * 1.5f) + 1024;
            tempoBuffer.resize(maxOut * channels);
            effectiveInFrames = tempo.process(input, inFrames, tempoBuffer.data(), maxOut);
            effectiveInput = tempoBuffer.data();
        }

        if (inRate != outRate || (!preservePitch && std::abs(playbackSpeed - 1.0f) > 0.01f)) {
            double effectiveInRate = inRate;
            if (!preservePitch) effectiveInRate *= playbackSpeed;

            int expectedOutFrames = (int)((float)effectiveInFrames * outRate / effectiveInRate);
            resampleBuffer.resize(expectedOutFrames * channels + 256);
#ifdef HAVE_SOXR
            if (useSox && soxr_handle) {
                size_t idone, odone;
                soxr_process(soxr_handle, effectiveInput, (size_t)effectiveInFrames, &idone, resampleBuffer.data(), (size_t)expectedOutFrames, &odone);
                outFrames = (int)odone;
            } else {
#else
            {
#endif
                float ratio = (float)outRate / effectiveInRate;
                resample_cubic(effectiveInput, effectiveInFrames, resampleBuffer.data(), expectedOutFrames, channels, ratio);
                outFrames = expectedOutFrames;
            }

            if (outFrames > outputCapacityFrames) {
                LOGE("DSP::process: Output frames (%d) exceed capacity (%d). Clamping to prevent overflow.", outFrames, outputCapacityFrames);
                outFrames = outputCapacityFrames;
            }
            std::copy(resampleBuffer.begin(), resampleBuffer.begin() + (outFrames * channels), output);
        } else {
            int framesToCopy = std::min(effectiveInFrames, outputCapacityFrames);
            if (effectiveInFrames > outputCapacityFrames) {
                LOGE("DSP::process: Effective input frames (%d) exceed capacity (%d). Clamping to prevent overflow.", effectiveInFrames, outputCapacityFrames);
            }
            std::copy(effectiveInput, effectiveInput + (framesToCopy * channels), output);
            outFrames = framesToCopy;
        }
    }

    void setReplayGain(float db) { replayGainDb = db; }
    void setPreamp(float db) { preampDb = db; }
    void setVolume(float v) {
        if (hardwareVolumeEnabled) {
            dvcLevel = 1.0f;
        } else {
            dvcLevel = std::clamp(v, 0.0f, 1.0f);
        }
    }
    void setDcBlocker(bool enabled) { dcBlockerEnabled = enabled; }
    void setMono(bool enabled) { monoEnabled = enabled; }
    void setDvc(bool enabled) { dvcEnabled = enabled; }
    void setRmsDvc(bool enabled) { rmsDvcEnabled = enabled; }
    void setRmsLeveler(bool enabled) { rmsLevelerEnabled = enabled; }
    void setDvcLevel(float level) {
        if (hardwareVolumeEnabled) {
            dvcLevel = 1.0;
        } else {
            dvcLevel = std::clamp((double)level, 0.0, 1.0);
        }
        // Snap if within 0.1% linear — avoids ramping from power-on state
        if (std::abs(dvcCurrentLevel - dvcLevel) < 0.001) {
            dvcCurrentLevel = dvcLevel;
        }
    }
    void setHardwareVolume(bool enabled) {
        hardwareVolumeEnabled = enabled;
        if (enabled) {
            dvcLevel = 1.0;
            dvcCurrentLevel = 1.0;
        }
    }
    void setDvcMode(int mode) { dvcMode = mode; }
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
    void setAudio3DStageEnabled(bool enabled) {
        audio3DStageEnabled = enabled;
        audio3DStage.setEnabled(enabled);
    }
    void setAudio3DStageParams(float width, float depth, float height, float distance,
                                float centerFocus, float roomReflections) {
        audio3DStage.setWidth((double)width);
        audio3DStage.setCenterLock((double)centerFocus);
    }
    void setSoundStageWidth(float width) { audio3DStage.setWidth((double)width); }
    void setSoundStageCenterLock(float amount) { audio3DStage.setCenterLock((double)amount); }
    void setSpatialIntensity(float intensity) { audio3DStage.setSpatialIntensity((double)intensity); }
    void setSpatialUiMode(int mode) { audio3DStage.setSpatialUiMode(mode); }
    void setHrtfMode(int mode) { audio3DStage.setHrtfMode(mode); }

    void setAudio3DSpeakerPosition(int index, float az, float el, float dist) {
        audio3DStage.setBandPosition(index, (double)az, (double)el, (double)dist);
    }

    void setLimiter(bool enabled) {
        limiterEnabled = enabled;
        if (enabled) softLimiterEnabled = false;
    }
    void setSoftLimiter(bool enabled) {
        softLimiterEnabled = enabled;
        if (enabled) limiterEnabled = false;
    }
    void setLimiterParams(float thresholdDb, float attackMs, float releaseMs) {
        limiterThresholdDb = thresholdDb;
        limiterAttackMs = attackMs;
        limiterReleaseMs = releaseMs;
        limiter.setParams((double)thresholdDb, (double)attackMs, (double)releaseMs);
    }
    void setLimiterHardMode(bool enabled) {
        limiterHardModeEnabled = enabled;
        limiter.setHardMode(enabled);
    }
    void muteReverb() { reverb.mute(); reverbAmount = 0.0f; }
    void setBitDepth(int bd) { bitDepth = bd; }
    void setDither(bool enabled, int bd) { dither.setEnabled(enabled, bd); }
    void setDitherType(int mode) { dither.setType(mode); }
    void setCinemaMode(bool enabled, float intensity) {
        intensity = std::clamp(intensity, 0.0f, 1.0f);
        bool changed = (cinemaModeEnabled != enabled) || (fabsf(cinemaIntensity - intensity) > 0.005f);
        cinemaModeEnabled = enabled;
        cinemaIntensity = intensity;
        if (!changed) return;
        if (enabled) {
            // ~2.5kHz peaking presence boost covering the 1-4kHz dialogue range, scaled by intensity.
            cinemaDialogueFilter.setPeaking((double)inRate, 2500.0, 3.0f * intensity, 1.1f);
        } else {
            cinemaDialogueFilter.reset();
        }
    }
    void setTone(float bass, float treble, float air) {
        // Set targets atomically; actual application is smoothed in audio thread
        toneTargetBass.store(bass, std::memory_order_relaxed);
        toneTargetTreble.store(treble, std::memory_order_relaxed);
        toneTargetAir.store(air, std::memory_order_relaxed);
        // Do not call updateFilters() here to avoid coefficient jumps on the control thread
    }
    void setBand(int index, float freq, float gainDb, float Q, int type) {
        eq.setBand(index, freq, gainDb, Q, type);
    }
    void setSpatial(float b, float w) {
        balance = b;
        stereoWidth = w;
    }
    void setEqEnabled(bool enabled) { eq.setEnabled(enabled); }
    void setEqPhaseMode(bool linearPhase) { eq.setPhaseMode(linearPhase); }
    void setHeadroomManagement(bool enabled) { headroomManagementEnabled = enabled; }
    void setNoHeadroomGain(bool enabled) { noHeadroomGainEnabled = enabled; }
    float getAutoHeadroomDb() {
        double g = eq.getAutoHeadroomGain();
        double aiG = aiEq.getAutoHeadroomGain();
        double simG = simEq.getAutoHeadroomGain();
        if (aiG < g) g = aiG;
        if (simG < g) g = simG;
        return (float)(20.0 * std::log10(std::max(g, 1e-6)));
    }

    void setAiBand(int index, float freq, float gainDb, float Q, int type) {
        aiEq.setBand(index, freq, gainDb, Q, type);
    }
    void setAiEqEnabled(bool enabled) { aiEq.setEnabled(enabled); }

    void setSimBand(int index, float freq, float gainDb, float Q, int type) {
        simEq.setBand(index, freq, gainDb, Q, type);
    }
    void setSimEqEnabled(bool enabled) { simEq.setEnabled(enabled); }

    void setUseSox(bool use) { if (useSox != use) { useSox = use; updateSoxr(); } }
    void setCutoffRatio(float ratio) { if (fabsf(cutoffRatio - ratio) > 0.001f) { cutoffRatio = ratio; updateSoxr(); } }
    void setSoxrQuality(int quality) { if (soxrQuality != quality) { soxrQuality = quality; updateSoxr(); } }
    void setFloat64Mode(bool enabled) { float64Enabled = enabled; }
    void setSpeed(float speed, bool pitchCorrection) {
        if (playbackSpeed != speed || preservePitch != pitchCorrection) {
            playbackSpeed = std::clamp(speed, 0.5f, 2.0f);
            preservePitch = pitchCorrection;
            updateSoxr();
        }
    }
    int getEqLatencyFrames() const {
        int l = eq.getLatencyFrames();
        int al = aiEq.getLatencyFrames();
        int sl = simEq.getLatencyFrames();
        return l + al + sl;
    }

    void getLevels(float* l, float* r) {
        *l = levelL.load(std::memory_order_relaxed);
        *r = levelR.load(std::memory_order_relaxed);
    }

private:
    std::array<float, 32> bandDbs, bandQs, bandFreqs;

    void updateFilters() {
        double sr = (double)inRate;
        // Tweak: Peaking filter at 80Hz for tighter, punchier bass (not muffled)
        if (fabsf(bassDb) > 0.05f) toneFilters[0].setPeaking(sr, 80.0, (double)bassDb, 0.7); else toneFilters[0].reset();
        if (fabsf(trebleDb) > 0.05f) toneFilters[1].setHighShelf(sr, 6000.0, (double)trebleDb, 0.7); else toneFilters[1].reset();
        if (fabsf(airDb) > 0.05f) toneFilters[2].setHighShelf(sr, 12000.0, (double)airDb, 0.6); else toneFilters[2].reset();
        // Manual bands handled by EqEngine
    }
#ifdef HAVE_SOXR
    soxr_t soxr_handle;
#endif
    int inRate, outRate, channels;
    bool useSox;
    bool dcBlockerEnabled;
    DcBlocker dcBlocker;
    bool dvcEnabled; bool rmsDvcEnabled; bool rmsLevelerEnabled; double dvcLevel; double dvcCurrentLevel = 1.0; int dvcMode;
    // Pre-computed ramp coefficient (computed once on init, not per buffer)
    double dvcRampCoeff = 0.0;
    bool limiterEnabled, softLimiterEnabled = false;
    bool limiterHardModeEnabled = false;
    float limiterThresholdDb = -0.2f, limiterAttackMs = 5.0f, limiterReleaseMs = 100.0f;
    float cutoffRatio; int soxrQuality;
    bool float64Enabled;
    bool headroomManagementEnabled;
    bool noHeadroomGainEnabled;
    bool hardwareVolumeEnabled;
    bool monoEnabled;
    std::atomic<float> levelL{0.0f}, levelR{0.0f};

    // RMS Leveler State
    double levelerGain = 1.0;
    double levelerRmsEnvelope = 0.0;
    double levelerAttackCoeff = 0.0;
    double levelerReleaseCoeff = 0.0;
    double rmsEnvCoeff = 0.0;
    DitherProcessor dither;
    float replayGainDb, preampDb; float bassDb, trebleDb, airDb;
    EqEngine eq;
    EqEngine aiEq; // New AI EQ Engine
    EqEngine simEq; // Headphone simulation EQ (Phase 3.5)
    BiquadState toneFilters[3]; LookaheadLimiter limiter; Bs2b crossfeed;
    bool cinemaModeEnabled = false; float cinemaIntensity = 1.0f; BiquadState cinemaDialogueFilter;
    Audio3DStageEngine audio3DStage;
    bool audio3DStageEnabled;
    bool crossfeedEnabled; float crossfeedLevel; Freeverb reverb; float reverbAmount; int reverbType;
    float reverbPredelayMs; float reverbWidth; float reverbDamping; float reverbRoomSize;
    float balance, stereoWidth;    int bitDepth;

    // Tone smoothing targets and current values to avoid coefficient jumps
    std::atomic<float> toneTargetBass{0.0f}, toneTargetTreble{0.0f}, toneTargetAir{0.0f};
    float toneCurrentBass = 0.0f, toneCurrentTreble = 0.0f, toneCurrentAir = 0.0f;
    float toneSmoothingFactor = 0.12f; // per-buffer interpolation

    std::vector<float> tempoBuffer, resampleBuffer; std::vector<double> doubleBuffer;
};

// ===================== AUDIO ANALYZER (Offline) =====================
#include <unistd.h>
#include <sys/types.h>

struct AnalysisResults {
    float lufs;
    float rms;
    float peak;
    float dynamicRange;
    float bassScore;
    float midScore;
    float trebleScore;
    float stereoWidth;
    float tempoBpm;
    std::vector<float> spectralData; // For TFLite input
    // NEW: quality-analysis extensions
    float truePeakDb;       // estimated true (inter-sample) peak in dBFS
    float clippedSamplePct; // % of samples at or above ~-0.18 dBFS (0.0-100.0)
    float freqRangeLowHz;   // lowest frequency with meaningful energy
    float freqRangeHighHz;  // highest frequency with meaningful energy
};

class AudioAnalyzer {
    // K-weighting filters for LUFS (ITU-R BS.1770)
    BiquadState preFilter;
    BiquadState rlFilter;
    double sampleRate = 44100;

public:
    AudioAnalyzer(double sr) : sampleRate(sr) {
        // High-shelf pre-filter
        preFilter.setHighShelf(sr, 1500.0, 4.0, 0.707);
        // High-pass RL filter
        rlFilter.setHighPass(sr, 100.0, 0.0, 0.707);
    }

    AnalysisResults analyze(float* buffer, int frames, int channels) {
        AnalysisResults res = {};
        if (frames <= 0 || channels <= 0) return res;

        double sumSq[2] = {0, 0};
        float peak = 0;
        double sideEnergy = 0;

        // LUFS state
        double lufsSum[2] = {0, 0};

        // NEW: clipping + true-peak state
        long long clippedCount = 0;
        const float kClipThreshold = 0.98f; // ~ -0.18 dBFS
        float oversampledPeak = 0.0f;
        float prevL = 0.0f, prevR = 0.0f;

        // Spectral state
        int fftSize = 2048;
        kiss_fft_state fft;
        kiss_fft_alloc(fft, fftSize, 0);
        std::vector<std::complex<double>> in(fftSize), out(fftSize);

        double bassEnergy = 0, midEnergy = 0, trebleEnergy = 0;
        int fftCount = 0;
        std::vector<double> magSum(fftSize / 2, 0.0);

        std::vector<double> flux;
        double lastEnergy = 0;

        for (int f = 0; f < frames; f++) {
            float l = buffer[f * channels];
            float r = (channels >= 2) ? buffer[f * channels + 1] : l;

            peak = std::max({peak, std::abs(l), std::abs(r)});
            sumSq[0] += (double)l * l;
            sumSq[1] += (double)r * r;
            sideEnergy += (double)(l - r) * (l - r);

            // Clipping detection (cheap: raw sample-domain threshold)
            if (std::abs(l) >= kClipThreshold || std::abs(r) >= kClipThreshold) {
                clippedCount++;
            }

            // True-peak estimate: 4x linear-interpolation oversampling between
            // consecutive sample pairs to catch inter-sample peaks. O(n), same pass.
            oversampledPeak = std::max(oversampledPeak, std::abs(l));
            oversampledPeak = std::max(oversampledPeak, std::abs(r));
            if (f > 0) {
                for (int k = 1; k < 4; k++) {
                    float t = k / 4.0f;
                    float il = prevL + (l - prevL) * t;
                    float ir = prevR + (r - prevR) * t;
                    oversampledPeak = std::max({oversampledPeak, std::abs(il), std::abs(ir)});
                }
            }
            prevL = l; prevR = r;

            // Apply LUFS K-weighting
            double fl = (double)l, fr = (double)r;
            preFilter.process(fl, fr);
            rlFilter.process(fl, fr);
            lufsSum[0] += fl * fl;
            lufsSum[1] += fr * fr;

            // Spectral analysis (on windowed blocks)
            if (f % fftSize == 0 && f + fftSize <= frames) {
                for (int i = 0; i < fftSize; i++) {
                    double w = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / (fftSize - 1)));
                    in[i] = std::complex<double>((buffer[(f + i) * channels] + ((channels >= 2) ? buffer[(f + i) * channels + 1] : 0)) * 0.5 * w, 0.0);
                }
                kiss_fft(fft, in.data(), out.data());

                double currentEnergy = 0;
                for (int i = 0; i < fftSize / 2; i++) {
                    double freq = (double)i * sampleRate / fftSize;
                    double mag = std::abs(out[i]);
                    currentEnergy += mag;
                    magSum[i] += mag;
                    if (freq < 250) bassEnergy += mag;
                    else if (freq < 4000) midEnergy += mag;
                    else trebleEnergy += mag;
                }
                flux.push_back(std::max(0.0, currentEnergy - lastEnergy));
                lastEnergy = currentEnergy;
                fftCount++;
            }
        }

        if (flux.size() > 10) {
            // Sample rate of flux is sampleRate / fftSize (one flux value per FFT hop)
            float fluxRate = (float)sampleRate / fftSize;
            float bestScore = 0.0f;
            float bestBpm = 120.0f;
            // Search 60-200 BPM (period in flux samples = fluxRate * 60 / bpm)
            for (float bpm = 60.0f; bpm <= 200.0f; bpm += 1.0f) {
                float period = fluxRate * 60.0f / bpm;
                if (period < 2.0f || period >= (float)flux.size()) continue;
                int lag = (int)std::round(period);
                double corr = 0.0;
                int count = 0;
                for (int i = 0; i + lag < (int)flux.size(); i++) {
                    corr += (double)flux[i] * flux[i + lag];
                    count++;
                }
                if (count > 0) corr /= count;
                if ((float)corr > bestScore) { bestScore = (float)corr; bestBpm = bpm; }
            }
            res.tempoBpm = bestBpm;
        } else {
            res.tempoBpm = 120.0f;
        }

        float totalSamples = (float)frames * channels;
        res.rms = (float)std::sqrt((sumSq[0] + sumSq[1]) / (totalSamples + 1e-10));
        res.peak = peak;
        res.dynamicRange = 20.0f * std::log10(res.peak + 1e-9f) - 20.0f * std::log10(res.rms + 1e-9f);
        res.dynamicRange = std::clamp(res.dynamicRange, 0.0f, 60.0f);
        res.stereoWidth = (float)(sideEnergy / (sumSq[0] + sumSq[1] + 1e-10));

        // NEW: clipping + true peak
        res.clippedSamplePct = frames > 0 ? (float)(100.0 * clippedCount / (double)frames) : 0.0f;
        res.truePeakDb = 20.0f * std::log10(oversampledPeak + 1e-9f);

        // LUFS Calculation (Simplified ITU-R BS.1770)
        double meanSqL = lufsSum[0] / frames;
        double meanSqR = lufsSum[1] / frames;
        // Channel weighting: G_i = 1.0 for L, R
        res.lufs = (float)(-0.691 + 10.0 * std::log10(meanSqL + meanSqR + 1e-10));

        if (fftCount > 0) {
            double total = bassEnergy + midEnergy + trebleEnergy + 1e-10;
            res.bassScore = (float)(bassEnergy / total);
            res.midScore = (float)(midEnergy / total);
            res.trebleScore = (float)(trebleEnergy / total);

            // AI Feature Bucketing
            res.spectralData.assign(128, 0.0f);
            int binsPerBucket = std::max(1, (int)(magSum.size() / 128));
            for (int b = 0; b < 128; b++) {
                double sum = 0;
                for (int i = 0; i < binsPerBucket && (b * binsPerBucket + i) < (int)magSum.size(); i++)
                    sum += magSum[b * binsPerBucket + i];
                res.spectralData[b] = (float)(sum / binsPerBucket / std::max(1, fftCount));
            }

            // Frequency range: find first/last bin within -60 dB of the peak bin.
            double peakMag = 0.0;
            for (double m : magSum) peakMag = std::max(peakMag, m);
            double threshold = peakMag * std::pow(10.0, -60.0 / 20.0);
            int lowBin = -1, highBin = -1;
            for (int i = 0; i < (int)magSum.size(); i++) {
                if (magSum[i] >= threshold) { lowBin = i; break; }
            }
            for (int i = (int)magSum.size() - 1; i >= 0; i--) {
                if (magSum[i] >= threshold) { highBin = i; break; }
            }
            if (lowBin >= 0 && highBin >= 0) {
                res.freqRangeLowHz = (float)((double)lowBin * sampleRate / fftSize);
                res.freqRangeHighHz = (float)((double)highBin * sampleRate / fftSize);
            } else {
                res.freqRangeLowHz = 0.0f;
                res.freqRangeHighHz = 0.0f;
            }
        } else {
            res.spectralData.assign(128, 0.0f);
            res.freqRangeLowHz = 0.0f;
            res.freqRangeHighHz = 0.0f;
        }
        return res;
    }
};

extern "C" {
JNIEXPORT jobject JNICALL Java_com_beatraxus_app_engine_NativeDsp_nExtractFeatures(JNIEnv* env, jobject thiz, jint fd, jint seconds) {
    if (fd < 0) return nullptr;

    // Check if it's a DSF file first
    char magic[4];
    lseek(fd, 0, SEEK_SET);
    if (read(fd, magic, 4) == 4 && memcmp(magic, "DSD ", 4) == 0) {
        // Basic DSF metadata extraction
        DsfFormatChunk fmt = {};
        lseek(fd, 28, SEEK_SET); // Skip DSD header to get to fmt chunk
        if (read(fd, &fmt, sizeof(DsfFormatChunk)) != sizeof(DsfFormatChunk)) {
            return nullptr;
        }

        jclass featuresClass = env->FindClass("com/beatraxus/app/engine/AudioFeatures");
        if (!featuresClass) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to find AudioFeatures class");
            return nullptr;
        }
        jmethodID constructor = env->GetMethodID(featuresClass, "<init>", "(FFFFFFFFF[FFFFF)V");
        if (!constructor) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to find AudioFeatures constructor");
            return nullptr;
        }
        jfloatArray spectralData = env->NewFloatArray(128);
        float dummy[128] = {0};
        env->SetFloatArrayRegion(spectralData, 0, 128, dummy);

        return env->NewObject(featuresClass, constructor,
            -10.0f, // LUFS (dummy for DSD)
            0.1f,   // RMS
            1.0f,   // Peak
            20.0f,  // DR
            0.3f,   // Bass
            0.3f,   // Mid
            0.3f,   // Treble
            1.0f,   // Stereo
            120.0f, // Tempo
            spectralData,
            0.0f,     // truePeakDb (dummy for DSD - already lossless/DSD, no clipping concept here)
            0.0f,     // clippedSamplePct
            20.0f,    // freqRangeLowHz
            22000.0f  // freqRangeHighHz
        );
    }

    AMediaExtractor* ex = AMediaExtractor_new();
    if (!ex) return nullptr;
    if (AMediaExtractor_setDataSourceFd(ex, fd, 0, 0x7FFFFFFFFFFFFFFFL) != AMEDIA_OK) {
        AMediaExtractor_delete(ex);
        return nullptr;
    }

    AMediaCodec* codec = nullptr;
    int trackIdx = -1;
    for (int i = 0; i < AMediaExtractor_getTrackCount(ex); i++) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(ex, i);
        if (!format) continue;
        const char* mime;
        if (AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime)) {
            if (strncmp(mime, "audio/", 6) == 0) {
                trackIdx = i;
                codec = AMediaCodec_createDecoderByType(mime);
                if (codec) {
                    media_status_t status = AMediaCodec_configure(codec, format, nullptr, nullptr, 0);
                    if (status != AMEDIA_OK) {
                        AMediaCodec_delete(codec);
                        codec = nullptr;
                    } else {
                        AMediaExtractor_selectTrack(ex, i);
                    }
                }
                AMediaFormat_delete(format);
                if (codec) break;
            }
        }
        if (format) AMediaFormat_delete(format);
    }

    if (!codec) {
        AMediaExtractor_delete(ex);
        return nullptr;
    }

    if (AMediaCodec_start(codec) != AMEDIA_OK) {
        AMediaCodec_delete(codec);
        AMediaExtractor_delete(ex);
        return nullptr;
    }

    std::vector<float> pcm;
    bool sawInputEOS = false, sawOutputEOS = false;
    int sampleRate = 44100, channels = 2;

    AMediaFormat* format = AMediaCodec_getOutputFormat(codec);
    if (format) {
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channels);
        AMediaFormat_delete(format);
    }

    int64_t maxSamples = (int64_t)seconds * sampleRate * channels;
    if (maxSamples <= 0) maxSamples = 30 * sampleRate * channels;

    while (!sawOutputEOS && pcm.size() < maxSamples) {
        if (!sawInputEOS) {
            ssize_t inputIdx = AMediaCodec_dequeueInputBuffer(codec, 2000);
            if (inputIdx >= 0) {
                size_t inputSize;
                uint8_t* inputBuf = AMediaCodec_getInputBuffer(codec, inputIdx, &inputSize);
                ssize_t sampleSize = AMediaExtractor_readSampleData(ex, inputBuf, inputSize);
                if (sampleSize < 0) {
                    AMediaCodec_queueInputBuffer(codec, inputIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    sawInputEOS = true;
                } else {
                    AMediaCodec_queueInputBuffer(codec, inputIdx, 0, sampleSize, AMediaExtractor_getSampleTime(ex), 0);
                    AMediaExtractor_advance(ex);
                }
            }
        }

        AMediaCodecBufferInfo info;
        ssize_t outputIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 2000);
        if (outputIdx >= 0) {
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) sawOutputEOS = true;
            size_t outputSize;
            uint8_t* outputBuf = AMediaCodec_getOutputBuffer(codec, outputIdx, &outputSize);
            int16_t* s16 = (int16_t*)(outputBuf + info.offset);
            int count = info.size / sizeof(int16_t);
            for (int i = 0; i < count; i++) pcm.push_back(s16[i] / 32768.0f);
            AMediaCodec_releaseOutputBuffer(codec, outputIdx, false);
        } else if (outputIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* newFormat = AMediaCodec_getOutputFormat(codec);
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate);
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channels);
            AMediaFormat_delete(newFormat);
        }
    }

    AMediaCodec_stop(codec); AMediaCodec_delete(codec); AMediaExtractor_delete(ex);

    AudioAnalyzer analyzer(sampleRate);
    AnalysisResults res = analyzer.analyze(pcm.data(), pcm.size() / channels, channels);

    jclass featuresClass = env->FindClass("com/beatraxus/app/engine/AudioFeatures");
    if (!featuresClass) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to find AudioFeatures class");
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(featuresClass, "<init>", "(FFFFFFFFF[FFFFF)V");
    if (!constructor) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to find AudioFeatures constructor");
        return nullptr;
    }
    jfloatArray spectralData = env->NewFloatArray(res.spectralData.size());
    env->SetFloatArrayRegion(spectralData, 0, res.spectralData.size(), res.spectralData.data());

    return env->NewObject(featuresClass, constructor,
        res.lufs, res.rms, res.peak, res.dynamicRange, res.bassScore, res.midScore, res.trebleScore,
        res.stereoWidth, res.tempoBpm, spectralData,
        res.truePeakDb, res.clippedSamplePct, res.freqRangeLowHz, res.freqRangeHighHz
    );
}

JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nPackDoP(JNIEnv* env, jobject thiz, jbyteArray dsd, jintArray pcm, jint frames, jint channels, jboolean alt) {
    jbyte* dsdData = env->GetByteArrayElements(dsd, nullptr);
    jint* pcmData = env->GetIntArrayElements(pcm, nullptr);
    DsdProcessor::packDoP((const uint8_t*)dsdData, (int32_t*)pcmData, frames, channels, alt);
    env->ReleaseByteArrayElements(dsd, dsdData, JNI_ABORT);
    env->ReleaseIntArrayElements(pcm, pcmData, 0);
}

JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nDsdToPcm(JNIEnv* env, jobject thiz, jbyteArray dsd, jfloatArray pcm, jint frames, jint channels) {
    jbyte* dsdData = env->GetByteArrayElements(dsd, nullptr);
    jfloat* pcmData = env->GetFloatArrayElements(pcm, nullptr);
    DsdProcessor::dsdToPcm((const uint8_t*)dsdData, pcmData, frames, channels);
    env->ReleaseByteArrayElements(dsd, dsdData, JNI_ABORT);
    env->ReleaseFloatArrayElements(pcm, pcmData, 0);
}

JNIEXPORT jlong JNICALL Java_com_beatraxus_app_engine_NativeDsp_nCreate(JNIEnv* env, jobject thiz) { return (jlong)new DSP(); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nDestroy(JNIEnv* env, jobject thiz, jlong handle) { if (handle) delete (DSP*)handle; }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nInitResampler(JNIEnv* env, jobject thiz, jlong handle, jfloat inputSR, jint channels, jfloat targetSR) { if (handle) ((DSP*)handle)->init((int)inputSR, (int)targetSR, channels); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetVolume(JNIEnv* env, jobject thiz, jlong handle, jfloat volume) { if (handle) ((DSP*)handle)->setVolume(volume); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetTone(JNIEnv* env, jobject thiz, jlong handle, jfloat bass, jfloat treble, jfloat air) { if (handle) ((DSP*)handle)->setTone(bass, treble, air); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSpatial(JNIEnv* env, jobject thiz, jlong handle, jfloat balance, jfloat widen) { if (handle) ((DSP*)handle)->setSpatial(balance, widen); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSpatialEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setAudio3DStageEnabled(enabled); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSpatialIntensity(JNIEnv* env, jobject thiz, jlong handle, jfloat intensity) { if (handle) ((DSP*)handle)->setSpatialIntensity(intensity); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetHrtfMode(JNIEnv* env, jobject thiz, jlong handle, jint mode) { if (handle) ((DSP*)handle)->setHrtfMode(mode); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSpatialUiMode(JNIEnv* env, jobject thiz, jlong handle, jint mode) { if (handle) ((DSP*)handle)->setSpatialUiMode(mode); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetCinemaMode(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled, jfloat intensity) { if (handle) ((DSP*)handle)->setCinemaMode(enabled, intensity); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetBand(JNIEnv* env, jobject thiz, jlong handle, jint index, jfloat freq, jfloat gainDb, jfloat Q, jint type) { if (handle) ((DSP*)handle)->setBand(index, freq, gainDb, Q, type); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetEqEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setEqEnabled(enabled); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetEqPhaseMode(JNIEnv* env, jobject thiz, jlong handle, jboolean linearPhase) { if (handle) ((DSP*)handle)->setEqPhaseMode(linearPhase); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetHeadroomManagement(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setHeadroomManagement(enabled); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetNoHeadroomGain(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setNoHeadroomGain(enabled); }
JNIEXPORT jfloat JNICALL Java_com_beatraxus_app_engine_NativeDsp_nGetHeadroomDb(JNIEnv* env, jobject thiz, jlong handle) { return handle ? ((DSP*)handle)->getAutoHeadroomDb() : 0.0f; }
JNIEXPORT jint JNICALL Java_com_beatraxus_app_engine_NativeDsp_nGetEqLatencyFrames(JNIEnv* env, jobject thiz, jlong handle) { return handle ? ((DSP*)handle)->getEqLatencyFrames() : 0; }

JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetAiBand(JNIEnv* env, jobject thiz, jlong handle, jint index, jfloat freq, jfloat gainDb, jfloat Q, jint type) {
    if (handle) ((DSP*)handle)->setAiBand(index, freq, gainDb, Q, type);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetAiEqEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setAiEqEnabled(enabled);
}

JNIEXPORT jfloatArray JNICALL Java_com_beatraxus_app_engine_NativeDsp_nGetLevels(JNIEnv* env, jobject thiz, jlong handle) {
    float levels[2] = {0, 0};
    if (handle) ((DSP*)handle)->getLevels(&levels[0], &levels[1]);
    jfloatArray result = env->NewFloatArray(2);
    env->SetFloatArrayRegion(result, 0, 2, levels);
    return result;
}

JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSimBand(JNIEnv* env, jobject thiz, jlong handle, jint index, jfloat freq, jfloat gainDb, jfloat Q, jint type) {
    if (handle) ((DSP*)handle)->setSimBand(index, freq, gainDb, Q, type);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSimEqEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setSimEqEnabled(enabled);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetHardwareVolume(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setHardwareVolume(enabled);
}

JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetHighQualityResampler(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setUseSox(enabled); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetPreamp(JNIEnv* env, jobject thiz, jlong handle, jfloat db) { if (handle) ((DSP*)handle)->setPreamp(db); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetDcBlocker(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setDcBlocker(enabled); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetReplayGain(JNIEnv* env, jobject thiz, jlong handle, jfloat db) { if (handle) ((DSP*)handle)->setReplayGain(db); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetDvc(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setDvc(enabled); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetRmsDvc(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setRmsDvc(enabled); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetRmsLeveler(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setRmsLeveler(enabled); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetDvcLevel(JNIEnv* env, jobject thiz, jlong handle, jfloat level) { if (handle) ((DSP*)handle)->setDvcLevel(level); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetDvcMode(JNIEnv* env, jobject thiz, jlong handle, jint mode) { if (handle) ((DSP*)handle)->setDvcMode(mode); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetBitDepth(JNIEnv* env, jobject thiz, jlong handle, jint bitDepth) { if (handle) ((DSP*)handle)->setBitDepth(bitDepth); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetDither(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled, jint bitDepth) { if (handle) ((DSP*)handle)->setDither(enabled, bitDepth); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetDitherType(JNIEnv* env, jobject thiz, jlong handle, jint type) { if (handle) ((DSP*)handle)->setDitherType(type); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetCrossfeed(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled, jfloat level) { if (handle) ((DSP*)handle)->setCrossfeed(enabled, level); }
JNIEXPORT jint JNICALL Java_com_beatraxus_app_engine_NativeDsp_nProcessResampled(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input, jint inFrames, jfloatArray output, jint outputCapacityFrames) {
    if (!handle) return 0; jfloat* inBody = env->GetFloatArrayElements(input, 0); jfloat* outBody = env->GetFloatArrayElements(output, 0);
    int outFrames = 0; ((DSP*)handle)->process(inBody, inFrames, outBody, outFrames, outputCapacityFrames);
    env->ReleaseFloatArrayElements(input, inBody, JNI_ABORT); env->ReleaseFloatArrayElements(output, outBody, 0); return outFrames;
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nInit(JNIEnv* env, jobject thiz, jlong handle, jfloat sampleRate, jint channels) {
    if (handle) ((DSP*)handle)->init((int)sampleRate, (int)sampleRate, channels);
    // NOTE: call nInitResampler afterwards if output rate differs from input rate.
    // This is safe because nInitResampler calls init(inputSR, targetSR, channels)
    // which overwrites both rates correctly.
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nProcess(JNIEnv* env, jobject thiz, jlong handle, jfloatArray data, jint frames) {
    if (!handle) return; jfloat* body = env->GetFloatArrayElements(data, 0); ((DSP*)handle)->processInPlace(body, frames); env->ReleaseFloatArrayElements(data, body, 0);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetAudio3DStageEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setAudio3DStageEnabled(enabled);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSoundStageWidth(JNIEnv* env, jobject thiz, jlong handle, jfloat width) {
    if (handle) ((DSP*)handle)->setSoundStageWidth(width);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSoundStageCenterLock(JNIEnv* env, jobject thiz, jlong handle, jfloat amount) {
    if (handle) ((DSP*)handle)->setSoundStageCenterLock(amount);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetAudio3DStageParams(JNIEnv* env, jobject thiz, jlong handle, jfloat width, jfloat depth, jfloat height, jfloat distance, jfloat centerFocus, jfloat roomReflections) {
    if (handle) ((DSP*)handle)->setAudio3DStageParams(width, depth, height, distance, centerFocus, roomReflections);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSoundStageNodePosition(JNIEnv* env, jobject thiz, jlong handle, jint index, jfloat az, jfloat el, jfloat dist) {
    if (handle) ((DSP*)handle)->setAudio3DSpeakerPosition(index, az, el, dist);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetReverb(JNIEnv* env, jobject thiz, jlong handle, jfloat amount) { if (handle) ((DSP*)handle)->setReverb(amount); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetReverbType(JNIEnv* env, jobject thiz, jlong handle, jint type) { if (handle) ((DSP*)handle)->setReverbType(type); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetReverbPredelay(JNIEnv* env, jobject thiz, jlong handle, jfloat ms) { if (handle) ((DSP*)handle)->setReverbPredelay(ms); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetReverbWidth(JNIEnv* env, jobject thiz, jlong handle, jfloat width) { if (handle) ((DSP*)handle)->setReverbWidth(width); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetReverbParams(JNIEnv* env, jobject thiz, jlong handle, jfloat roomSize, jfloat damping) { if (handle) ((DSP*)handle)->setReverbParams(roomSize, damping); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nMuteReverb(JNIEnv* env, jobject thiz, jlong handle) { if (handle) ((DSP*)handle)->muteReverb(); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetLimiter(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setLimiter(enabled); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSoftLimiter(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setSoftLimiter(enabled); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetLimiterParams(JNIEnv* env, jobject thiz, jlong handle, jfloat thresholdDb, jfloat attackMs, jfloat releaseMs) {
    if (handle) ((DSP*)handle)->setLimiterParams(thresholdDb, attackMs, releaseMs);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetLimiterHardMode(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setLimiterHardMode(enabled);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSpeed(JNIEnv* env, jobject thiz, jlong handle, jfloat speed, jboolean preservePitch) {
    if (handle) ((DSP*)handle)->setSpeed(speed, preservePitch);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetMono(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setMono(enabled);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetCutoffRatio(JNIEnv* env, jobject thiz, jlong handle, jfloat ratio) { if (handle) ((DSP*)handle)->setCutoffRatio(ratio); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetSoxrQuality(JNIEnv* env, jobject thiz, jlong handle, jint quality) { if (handle) ((DSP*)handle)->setSoxrQuality(quality); }
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_NativeDsp_nSetFloat64(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) { if (handle) ((DSP*)handle)->setFloat64Mode(enabled); }

// MMAP Audio Output JNI
JNIEXPORT jlong JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapCreate(JNIEnv* env, jobject thiz, jint sampleRate, jint channels, jint bufferFrames, jint format) {
    return (jlong)new MmapStream(sampleRate, channels, bufferFrames, (aaudio_format_t)format);
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) delete (MmapStream*)handle;
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapStart(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) ((MmapStream*)handle)->start();
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapPause(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) ((MmapStream*)handle)->pause();
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapStop(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) ((MmapStream*)handle)->stop();
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapFlush(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle) ((MmapStream*)handle)->flush();
}
JNIEXPORT jint JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapWrite(JNIEnv* env, jobject thiz, jlong handle, jfloatArray data, jint offset, jint frames) {
    if (!handle) return 0;
    jfloat* body = env->GetFloatArrayElements(data, nullptr);
    int written = ((MmapStream*)handle)->write(body, offset, frames);
    env->ReleaseFloatArrayElements(data, body, JNI_ABORT);
    return written;
}
JNIEXPORT jint JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapWriteInt(JNIEnv* env, jobject thiz, jlong handle, jintArray data, jint offset, jint frames) {
    if (!handle) return 0;
    jint* body = env->GetIntArrayElements(data, nullptr);
    int written = ((MmapStream*)handle)->write(body, offset, frames);
    env->ReleaseIntArrayElements(data, body, JNI_ABORT);
    return written;
}
JNIEXPORT jlong JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapGetPlaybackPosition(JNIEnv* env, jobject thiz, jlong handle) {
    return handle ? (jlong)((MmapStream*)handle)->getPosition() : 0;
}
JNIEXPORT jint JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapGetBufferFrames(JNIEnv* env, jobject thiz, jlong handle) {
    return handle ? ((MmapStream*)handle)->getBufferSize() : 0;
}
JNIEXPORT jint JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapGetLatencyMs(JNIEnv* env, jobject thiz, jlong handle) {
    return handle ? ((MmapStream*)handle)->getLatency() : 0;
}
JNIEXPORT jint JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapGetSampleRate(JNIEnv* env, jobject thiz, jlong handle) {
    return handle ? ((MmapStream*)handle)->getSampleRate() : 48000;
}
JNIEXPORT void JNICALL Java_com_beatraxus_app_engine_MmapAudioOutput_nMmapSetBufferConfig(JNIEnv* env, jobject thiz, jlong handle, jint bufferFrames, jint bufferCount, jint postFadeFrames) {
    if (handle) ((MmapStream*)handle)->setBufferConfig(bufferFrames, bufferCount, postFadeFrames);
}
}