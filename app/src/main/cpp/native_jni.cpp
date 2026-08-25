// native_jni.cpp — StrongholdDroid JNI entry point.
//
// Loaded by System.loadLibrary("strongholddroid_jni") in EmulatorCore.kt.
// Exposes three top-level entry points to Kotlin:
//
//   • WineBridge        — launches & supervises the Wine+box64 process
//   • AudioBridge       — exposes OpenSL/AAudio device that Wine PulseAudio
//                          pipe can attach to
//   • InputBridge       — receives MotionEvent packets from the RTS overlay
//                          and forwards them as XInput2 events into Wine's
//                          internal event queue
//
// Concurrency model
// -----------------
// Every call into this library MUST be made from a worker thread that is
// attached to the JVM via AttachCurrentThread. The Kotlin side enforces this
// by routing all calls through Dispatchers.IO + a dedicated emulator thread.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include "wine_bridge.h"
#include "audio_bridge.h"
#include "input_bridge.h"

#define LOG_TAG "strongholddroid-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }
    if (!strongholddroid::wine::init(env)) {
        LOGE("JNI_OnLoad: wine bridge init failed");
        return JNI_ERR;
    }
    if (!strongholddroid::audio::init(env)) {
        LOGE("JNI_OnLoad: audio bridge init failed");
        return JNI_ERR;
    }
    if (!strongholddroid::input::init(env)) {
        LOGE("JNI_OnLoad: input bridge init failed");
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad: StrongholdDroid native layer ready");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        strongholddroid::input::shutdown(env);
        strongholddroid::audio::shutdown(env);
        strongholddroid::wine::shutdown(env);
    }
    LOGI("JNI_OnUnload: StrongholdDroid native layer torn down");
}

}  // extern "C"
