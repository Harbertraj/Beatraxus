// openal_jni_bridge.cpp
//
// Minimal JNI bridge around OpenAL Soft, configured to force HRTF on, for a
// single moving point source. This is intentionally small: init, load a
// mono PCM buffer, set its 3D position (OpenAL + HRTF handles the
// ipsi/contra filtering and interpolation for you), play/stop, shutdown.
//
// Matches com.example.binaural.audio.OpenAlAudioEngine.kt — rename the
// package in both files together if you change it.

#include <jni.h>
#include <AL/al.h>
#include <AL/alc.h>
#include <AL/alext.h>   // for ALC_HRTF_SOFT etc.
#include <android/log.h>
#include <vector>
#include <cstring>

#define LOG_TAG "OpenALBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    ALCdevice*  g_device = nullptr;
    ALCcontext* g_context = nullptr;
    ALuint      g_source = 0;
    ALuint      g_buffer = 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_beatraxus_app_engine_OpenAlAudioEngine_nativeInit(JNIEnv*, jobject) {
    // ALC_HRTF_SOFT = TRUE forces HRTF rendering on for this device, rather
    // than leaving it to alsoft.conf / auto-detection.
    ALCint attrs[] = {
        ALC_HRTF_SOFT, ALC_TRUE,
        0,
    };

    g_device = alcOpenDevice(nullptr);
    if (!g_device) {
        LOGE("alcOpenDevice failed");
        return JNI_FALSE;
    }

    g_context = alcCreateContext(g_device, attrs);
    if (!g_context) {
        LOGE("alcCreateContext failed");
        alcCloseDevice(g_device);
        g_device = nullptr;
        return JNI_FALSE;
    }
    alcMakeContextCurrent(g_context);

    ALCint hrtf_state = 0;
    alcGetIntegerv(g_device, ALC_HRTF_SOFT, 1, &hrtf_state);
    LOGI("HRTF active: %s", hrtf_state ? "yes" : "no (check device/config)");

    alGenSources(1, &g_source);
    alGenBuffers(1, &g_buffer);

    // Make sure the source is NOT treated as ambient/relative — HRTF only
    // does anything useful for sources positioned in 3D space relative to
    // the (fixed, forward-facing) listener.
    alSourcei(g_source, AL_SOURCE_RELATIVE, AL_FALSE);
    alListener3f(AL_POSITION, 0.0f, 0.0f, 0.0f);
    ALfloat forward_up[6] = { 0.0f, 0.0f, -1.0f,   0.0f, 1.0f, 0.0f };
    alListenerfv(AL_ORIENTATION, forward_up);

    return JNI_TRUE;
}

// Load a mono 16-bit PCM buffer (e.g. decoded from one of your source
// assets) at the given sample rate. Re-callable to swap clips.
extern "C" JNIEXPORT void JNICALL
Java_com_beatraxus_app_engine_OpenAlAudioEngine_nativeLoadMonoPcm16(
        JNIEnv* env, jobject, jshortArray pcm, jint sampleRate) {
    jsize len = env->GetArrayLength(pcm);
    std::vector<int16_t> samples(len);
    env->GetShortArrayRegion(pcm, 0, len, reinterpret_cast<jshort*>(samples.data()));

    alSourcei(g_source, AL_BUFFER, 0); // detach before re-filling
    alBufferData(g_buffer, AL_FORMAT_MONO16,
                 samples.data(), (ALsizei)(samples.size() * sizeof(int16_t)),
                 sampleRate);
    alSourcei(g_source, AL_BUFFER, (ALint)g_buffer);
}

// x,y,z in OpenAL's right-handed coords (x=right, y=up, z=toward listener).
// For a simple left/right/front "virtual speaker" preset, you mostly just
// drive x (and leave y=0, z=small negative to keep it "in front").
extern "C" JNIEXPORT void JNICALL
Java_com_beatraxus_app_engine_OpenAlAudioEngine_nativeSetSourcePosition(
        JNIEnv*, jobject, jfloat x, jfloat y, jfloat z) {
    alSource3f(g_source, AL_POSITION, x, y, z);
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatraxus_app_engine_OpenAlAudioEngine_nativePlay(JNIEnv*, jobject) {
    alSourcePlay(g_source);
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatraxus_app_engine_OpenAlAudioEngine_nativeStop(JNIEnv*, jobject) {
    alSourceStop(g_source);
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatraxus_app_engine_OpenAlAudioEngine_nativeSetLooping(
        JNIEnv*, jobject, jboolean loop) {
    alSourcei(g_source, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatraxus_app_engine_OpenAlAudioEngine_nativeShutdown(JNIEnv*, jobject) {
    if (g_source) { alDeleteSources(1, &g_source); g_source = 0; }
    if (g_buffer) { alDeleteBuffers(1, &g_buffer); g_buffer = 0; }
    if (g_context) {
        alcMakeContextCurrent(nullptr);
        alcDestroyContext(g_context);
        g_context = nullptr;
    }
    if (g_device) {
        alcCloseDevice(g_device);
        g_device = nullptr;
    }
}
