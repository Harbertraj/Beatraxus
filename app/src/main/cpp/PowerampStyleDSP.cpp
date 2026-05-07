#include <vector>
#include <array>
#include <cmath>
#include <algorithm>
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

struct Biquad {
    double b0, b1, b2, a1, a2;
    double z1_l, z2_l, z1_r, z2_r;

    Biquad() { reset(); }

    void reset() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0;
        a1 = 0.0; a2 = 0.0;
        z1_l = z2_l = z1_r = z2_r = 0.0;
    }

    inline void process(float& l, float& r) {
        double out_l = (double)l * b0 + z1_l;
        z1_l = (double)l * b1 + z2_l - a1 * out_l;
        z2_l = (double)l * b2 - a2 * out_l;
        l = (float)out_l;

        double out_r = (double)r * b0 + z1_r;
        z1_r = (double)r * b1 + z2_r - a1 * out_r;
        z2_r = (double)r * b2 - a2 * out_r;
        r = (float)out_r;
    }

    void setPeaking(double sr, double freq, double gainDb, double Q) {
        double A = pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * M_PI * freq / sr;
        double alpha = sin(w0) / (2.0 * Q);
        double cosw0 = cos(w0);
        double a0 = 1.0 + alpha / A;
        b0 = (1.0 + alpha * A) / a0;
        b1 = (-2.0 * cosw0) / a0;
        b2 = (1.0 - alpha * A) / a0;
        a1 = (-2.0 * cosw0) / a0;
        a2 = (1.0 - alpha / A) / a0;
    }

    void setLowShelf(double sr, double freq, double gainDb, double shelfSlope = 1.0) {
        double A = pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * M_PI * freq / sr;
        double alpha = sin(w0) / 2.0 * sqrt((A + 1.0 / A) * (1.0 / shelfSlope - 1.0) + 2.0);
        double cosw0 = cos(w0);
        double sqrtA2alpha = 2.0 * sqrt(A) * alpha;
        double a0 = (A + 1.0) + (A - 1.0) * cosw0 + sqrtA2alpha;
        b0 = (A * ((A + 1.0) - (A - 1.0) * cosw0 + sqrtA2alpha)) / a0;
        b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0)) / a0;
        b2 = (A * ((A + 1.0) - (A - 1.0) * cosw0 - sqrtA2alpha)) / a0;
        a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cosw0)) / a0;
        a2 = ((A + 1.0) + (A - 1.0) * cosw0 - sqrtA2alpha) / a0;
    }

    void setHighShelf(double sr, double freq, double gainDb, double shelfSlope = 1.0) {
        double A = pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * M_PI * freq / sr;
        double alpha = sin(w0) / 2.0 * sqrt((A + 1.0 / A) * (1.0 / shelfSlope - 1.0) + 2.0);
        double cosw0 = cos(w0);
        double sqrtA2alpha = 2.0 * sqrt(A) * alpha;
        double a0 = (A + 1.0) - (A - 1.0) * cosw0 + sqrtA2alpha;
        b0 = (A * ((A + 1.0) + (A - 1.0) * cosw0 + sqrtA2alpha)) / a0;
        b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)) / a0;
        b2 = (A * ((A + 1.0) - (A - 1.0) * cosw0 - sqrtA2alpha)) / a0;
        a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cosw0)) / a0;
        a2 = ((A + 1.0) - (A - 1.0) * cosw0 - sqrtA2alpha) / a0;
    }
};

// ===================== DSP UTILITIES =====================

inline float soft_clip(float x) {
    if (x > 1.0f) return 1.0f - expf(-x);
    if (x < -1.0f) return -1.0f + expf(x);
    return x;
}

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

// ===================== DSP ENGINE =====================
class DSP {
public:
    DSP() :
#ifdef HAVE_SOXR
            soxr_handle(nullptr),
#endif
            inRate(44100), outRate(44100), channels(2),
            useSox(true), dvcEnabled(true), limiterEnabled(true), replayGainDb(0.0f), preampDb(0.0f), dvcVolume(1.0f),
            bassDb(0.0f), midBassDb(0.0f), trebleDb(0.0f), airDb(0.0f),
            balance(0.0f), stereoWidth(1.0f), reverbAmount(0.0f), reverbIndex(0),
            reverbType(0), cutoffRatio(0.97f) {
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
        reverbBuffer.assign(inRate * channels, 0.0f);
        reverbIndex = 0;
        updateFilters();
        updateSoxr();
    }

    void updateSoxr() {
#ifdef HAVE_SOXR
        if (soxr_handle) {
            soxr_delete(soxr_handle);
            soxr_handle = nullptr;
        }
        soxr_error_t err;
        soxr_io_spec_t io_spec = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
        // Use cutoffRatio for passband_end
        soxr_quality_spec_t q_spec = soxr_quality_spec(SOXR_HQ, 0);
        q_spec.passband_end = (double)cutoffRatio;

        soxr_runtime_spec_t r_spec = soxr_runtime_spec(1);

        soxr_handle = soxr_create(inRate, outRate, channels, &err, &io_spec, &q_spec, &r_spec);
        if (!soxr_handle) {
            LOGI("SOXR FAILED: %s", soxr_strerror(err));
        }
#endif
    }

    void process(float* input, int inFrames, float* output, int& outFrames) {
        int samples = inFrames * channels;

        // 1. ReplayGain & Preamp
        float totalGain = powf(10.0f, (replayGainDb + preampDb) / 20.0f);
        if (totalGain != 1.0f) {
            for (int i = 0; i < samples; i++) input[i] *= totalGain;
        }

        // 2. EQ & Tone processing (at inRate)
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
                float dummy = s;
                for (int b = 0; b < 32; b++) eqBands[b].process(s, dummy);
                for (int t = 0; t < 4; t++) toneFilters[t].process(s, dummy);
            }
        }

        // 3. Stereo Widen / Balance
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

        // 4. Reverb
        if (reverbAmount > 0.001f && !reverbBuffer.empty()) {
            float feedback = 0.5f;
            float wet = reverbAmount * 0.4f;
            int delaySamples = (int)(inRate * 0.05); // Default 50ms

            if (reverbType == 1) { // ROOM
                delaySamples = (int)(inRate * 0.03); feedback = 0.3f;
            } else if (reverbType == 2) { // HALL
                delaySamples = (int)(inRate * 0.08); feedback = 0.6f;
            } else if (reverbType == 3) { // PLATE
                delaySamples = (int)(inRate * 0.04); feedback = 0.7f;
            } else if (reverbType == 4) { // CATHEDRAL
                delaySamples = (int)(inRate * 0.15); feedback = 0.8f;
            }

            for (int i = 0; i < samples; i++) {
                int rIdx = (reverbIndex + i) % reverbBuffer.size();
                int dIdx = (rIdx + reverbBuffer.size() - (delaySamples * channels)) % reverbBuffer.size();

                float delayed = reverbBuffer[dIdx];
                float inSample = input[i];
                input[i] += delayed * wet;
                reverbBuffer[rIdx] = inSample + delayed * feedback;
            }
            reverbIndex = (reverbIndex + samples) % reverbBuffer.size();
        }

        // 5. Resample
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

        int outSamples = outFrames * channels;

        // 6. DVC (Digital Volume Control)
        if (dvcEnabled && dvcVolume != 1.0f) {
            for (int i = 0; i < outSamples; i++) {
                tempBuffer[i] *= dvcVolume;
            }
        }

        // 7. Dynamic Protection (Peak Normalization)
        if (limiterEnabled) {
            float peak = 0.0f;
            for (int i = 0; i < outSamples; i++) {
                float absVal = fabsf(tempBuffer[i]);
                if (absVal > peak) peak = absVal;
            }

            if (peak > 1.0f) {
                float scale = 1.0f / peak;
                for (int i = 0; i < outSamples; i++) {
                    tempBuffer[i] *= scale;
                }
            }

            // 8. Soft Clipper
            for (int i = 0; i < outSamples; i++) {
                tempBuffer[i] = soft_clip(tempBuffer[i]);
            }
        }

        std::copy(tempBuffer.begin(), tempBuffer.begin() + outSamples, output);
    }

    void setReplayGain(float db) { replayGainDb = db; }
    void setPreamp(float db) { preampDb = db; }
    void setVolume(float v) { dvcVolume = std::clamp(v, 0.0f, 2.0f); }
    void setDvc(bool enabled) { dvcEnabled = enabled; }
    void setSpatial(float b, float w) { balance = b; stereoWidth = w; }
    void setReverb(float amount) { reverbAmount = amount; }
    void setReverbType(int type) { reverbType = type; }
    void setLimiter(bool enabled) { limiterEnabled = enabled; }
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
    void updateFilters() {
        double sr = (double)inRate;
        // Tone filters
        toneFilters[0].setLowShelf(sr, 150.0, (double)bassDb, 0.7);    // Bass at 150Hz
        toneFilters[1].setPeaking(sr, 450.0, (double)midBassDb, 0.8);  // Mid Bass at 450Hz
        toneFilters[2].setHighShelf(sr, 6000.0, (double)trebleDb, 0.7); // Treble at 6kHz
        toneFilters[3].setHighShelf(sr, 12000.0, (double)airDb, 0.7);  // Air at 12kHz

        // EQ Bands
        for (int i = 0; i < 32; i++) {
            eqBands[i].setPeaking(sr, (double)bandFreqs[i], (double)bandDbs[i], (double)bandQs[i]);
        }
    }

#ifdef HAVE_SOXR
    soxr_t soxr_handle;
#endif
    int inRate, outRate, channels;
    bool useSox;
    bool dvcEnabled;
    bool limiterEnabled;
    float cutoffRatio;
    float replayGainDb, preampDb, dvcVolume;
    float bassDb, midBassDb, trebleDb, airDb;
    std::array<float, 32> bandDbs;
    std::array<float, 32> bandFreqs;
    std::array<float, 32> bandQs;
    Biquad eqBands[32];
    Biquad toneFilters[4];
    float balance, stereoWidth, reverbAmount;
    int reverbType;
    std::vector<float> reverbBuffer;
    int reverbIndex;
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
Java_com_beatflowy_app_engine_NativeDsp_nSetReplayGain(JNIEnv* env, jobject thiz, jlong handle, jfloat db) {
    if (handle) ((DSP*)handle)->setReplayGain(db);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetDvc(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setDvc(enabled);
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
    int outFrames = 0;
    std::vector<float> output(frames * 2 + 256);
    ((DSP*)handle)->process(body, frames, output.data(), outFrames);
    int copyFrames = std::min(frames, outFrames);
    std::copy(output.begin(), output.begin() + (copyFrames * 2), body);
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
Java_com_beatflowy_app_engine_NativeDsp_nSetLimiter(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    if (handle) ((DSP*)handle)->setLimiter(enabled);
}

JNIEXPORT void JNICALL
Java_com_beatflowy_app_engine_NativeDsp_nSetCutoffRatio(JNIEnv* env, jobject thiz, jlong handle, jfloat ratio) {
    if (handle) ((DSP*)handle)->setCutoffRatio(ratio);
}

}
