// Placeholder native library so CMake configures successfully.
// Replace with real moonlight-common-c + MoonBridge JNI after integration.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "MoonlightAVNC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("MoonlightAVNC placeholder JNI loaded – replace with real moonlight-common-c");
    return JNI_VERSION_1_6;
}
