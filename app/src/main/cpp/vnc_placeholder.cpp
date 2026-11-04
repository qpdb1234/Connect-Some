// Placeholder for libnative-vnc.so when the libvncserver sources are absent.
//
// It intentionally exports nothing but JNI_OnLoad, so the library loads
// successfully but every `external fun` declared by
// com.limelight.vnc.RfbClientNative throws UnsatisfiedLinkError. That is
// exactly what NativeLibraryProbe.isVncAvailable() looks for: the VNC protocol
// then gets disabled in the UI while GameStream keeps working, instead of the
// process dying the moment someone touches the class.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "NativeVnc"

extern "C" jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "libnative-vnc.so placeholder loaded - libvncserver sources missing, VNC disabled");
    return JNI_VERSION_1_6;
}
