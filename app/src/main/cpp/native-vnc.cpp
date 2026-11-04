/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

#include <jni.h>
#include <GLES2/gl2.h>
#include <rfb/rfbclient.h>

#include "ClientEx.h"
#include "Utility.h"


/******************************************************************************
 * Library Initialization
 *****************************************************************************/

struct JniContext {
    JavaVM *vm;                     //JVM Instance
    jclass managedCls;              //Managed `RfbClientNative` class
    jmethodID cbFramebufferUpdated; //Cached reference to managed callback

    [[nodiscard]]
    JNIEnv *getEnvUnchecked() const {
        JNIEnv *env = nullptr;

        if (vm != nullptr && vm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK)
            return env;

        return nullptr;
    }

    [[nodiscard]]
    JNIEnv *getEnv() const {
        JNIEnv *env = getEnvUnchecked();
        if (env)
            return env;

        abort(); // Just crash if we can't get JNIEnv
    }
};

static JniContext context{};

/**
 * Called when our library is loaded.
 */
JNIEXPORT jint
JNI_OnLoad(JavaVM *vm, void *) {
    context.vm = vm;

    if (context.getEnvUnchecked() == nullptr)
        return JNI_ERR;

    return JNI_VERSION_1_6;
}

JNIEXPORT void
JNI_OnUnload(JavaVM *, void *) {
    if (context.managedCls) {
        context.getEnv()->DeleteGlobalRef(context.managedCls);
        context.managedCls = nullptr;
    }
}


extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_vnc_RfbClientNative_initLibrary(JNIEnv *env, jclass clazz) {
    if (context.managedCls)
        context.getEnv()->DeleteGlobalRef(context.managedCls);

    context.managedCls = (jclass) env->NewGlobalRef(clazz);
    context.cbFramebufferUpdated = env->GetMethodID(context.managedCls, "cbFinishedFrameBufferUpdate", "()V");
    if (!context.cbFramebufferUpdated) {
        // A missing method would be used as a null jmethodID on every framebuffer
        // update; say so now, at load time, where it can still be reported.
        env->ExceptionClear();
        rfbClientErr("initLibrary: cbFinishedFrameBufferUpdate is missing - the managed "
                     "class and libnative-vnc.so do not match\n");
        return;
    }
    //TODO: Cache more method IDs so we don't have to repeatedly search them

    rfbClientLog = &log_info;
    rfbClientErr = &log_error;
}

/**
 * Parks the log switch of Utility.h. Called by RfbClientNative when the library loads
 * and on every later change of the Tools screen's recording switch (see AppLogging),
 * so a VNC session on an install with logging off writes no log lines - including
 * everything libvncserver hands to rfbClientLog/rfbClientErr.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_vnc_RfbClientNative_setLogEnabled(JNIEnv *env, jclass clazz, jboolean enabled) {
    gLogEnabled = enabled ? 1 : 0;
}


/******************************************************************************
 * rfbClient Callbacks
 *****************************************************************************/

/**
 * Every callback below calls back into managed code, so a Java exception can be
 * left pending on return - a parse error in a server supplied certificate, a
 * cancelled credential prompt because the viewer went away, an OutOfMemoryError
 * from a huge clipboard.
 *
 * A pending exception must be dealt with before the next JNI call: ART aborts
 * the process when a native method is made while one is outstanding, which turns
 * a recoverable protocol error into a crash the server gets to choose. Reporting
 * it and clearing it is enough - every caller here can already handle "no value".
 */
static bool takePendingException(const char *where) {
    auto env = context.getEnvUnchecked();
    if (!env || !env->ExceptionCheck())
        return false;

    env->ExceptionDescribe();
    env->ExceptionClear();
    rfbClientErr("%s: managed code threw; the exception was cleared\n", where);
    return true;
}

static char *onGetPassword(rfbClient *client) {
    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    auto mid = env->GetMethodID(cls, "cbGetPassword", "()Ljava/lang/String;");
    auto jPassword = (jstring) env->CallObjectMethod(obj, mid);
    if (takePendingException("onGetPassword")) {
        // Without the clear above, the next JNI call from libvncserver would abort
        // the process; and jPassword is null here, so it must not reach
        // getNativeStrCopy (which would hand a null jstring to GetStringUTFChars).
        if (jPassword)
            env->DeleteLocalRef(jPassword);
        return nullptr;
    }

    char *password = jPassword ? getNativeStrCopy(env, jPassword) : nullptr;
    env->DeleteLocalRef(jPassword);
    return password;
}

static rfbCredential *onGetCredential(rfbClient *client, int credentialType) {
    if (credentialType == rfbCredentialTypeX509) {
        // Return empty credentials here, server certificate will be verified later
        auto credential = (rfbCredential *) malloc(sizeof(rfbCredential));
        if (!credential)
            return nullptr;
        memset(credential, 0, sizeof(rfbCredential));
        return credential;
    }

    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    //Retrieve credentials
    jmethodID mid = env->GetMethodID(cls, "cbGetCredential",
                                     "()Lcom/limelight/vnc/UserCredential;");
    jobject jCredential = env->CallObjectMethod(obj, mid);
    if (takePendingException("onGetCredential")) {
        if (jCredential)
            env->DeleteLocalRef(jCredential);
        return nullptr;
    }
    if (!jCredential) {
        return nullptr;
    }

    //Extract username & password
    auto jCredentialCls = env->GetObjectClass(jCredential);
    auto usernameField = env->GetFieldID(jCredentialCls, "username", "Ljava/lang/String;");
    auto jUsername = env->GetObjectField(jCredential, usernameField);

    auto passwordField = env->GetFieldID(jCredentialCls, "password", "Ljava/lang/String;");
    auto jPassword = env->GetObjectField(jCredential, passwordField);

    //Create native rfbCredential
    auto credential = (rfbCredential *) malloc(sizeof(rfbCredential));
    if (!credential) {
        env->DeleteLocalRef(jUsername);
        env->DeleteLocalRef(jPassword);
        env->DeleteLocalRef(jCredentialCls);
        env->DeleteLocalRef(jCredential);
        return nullptr;
    }
    credential->userCredential.username = jUsername
            ? getNativeStrCopy(env, (jstring) jUsername) : nullptr;
    credential->userCredential.password = jPassword
            ? getNativeStrCopy(env, (jstring) jPassword) : nullptr;

    env->DeleteLocalRef(jUsername);
    env->DeleteLocalRef(jPassword);
    env->DeleteLocalRef(jCredentialCls);
    env->DeleteLocalRef(jCredential);
    return credential;
}

static rfbBool onVerifyServerCertificate(rfbClient *client, const unsigned char *der, int der_len) {
    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    jbyteArray bytes = env->NewByteArray(der_len);
    if (!bytes) {
        rfbClientErr("onVerifyServerCertificate: Unable to allocate memory for DER");
        return FALSE;
    }
    env->SetByteArrayRegion(bytes, 0, der_len, reinterpret_cast<const jbyte *>(der));

    jmethodID mid = env->GetMethodID(cls, "cbVerifyServerCertificate", "([B)Z");
    auto result = env->CallBooleanMethod(obj, mid, bytes);
    env->DeleteLocalRef(bytes);

    // The managed side parses the DER the server chose, so it is exactly the place
    // a hostile server can make an exception appear. Treat "could not decide" as
    // "not trusted" rather than letting the pending exception reach the next JNI
    // call, and never fall through to `result`, whose value is unspecified here.
    if (takePendingException("onVerifyServerCertificate"))
        return FALSE;

    return result ? TRUE : FALSE;
}

static void onBell(rfbClient *client) {
    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    jmethodID mid = env->GetMethodID(cls, "cbBell", "()V");
    env->CallVoidMethod(obj, mid);
    takePendingException("onBell");
}

static void onGotXCutText(rfbClient *client, const char *text, int len, bool is_utf8) {
    if (!text || len <= 0) return;

    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    jbyteArray bytes = env->NewByteArray(len);
    if (!bytes) {
        rfbClientErr("onGotXCutText: Unable to allocate memory for cut text");
        return;
    }
    env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(text));

    jmethodID mid = env->GetMethodID(cls, "cbGotXCutText", "([BZ)V");
    env->CallVoidMethod(obj, mid, bytes, is_utf8);
    env->DeleteLocalRef(bytes);
    takePendingException("onGotXCutText");
}

static void onGotXCutTextLatin1(rfbClient *client, const char *text, int len) {
    onGotXCutText(client, text, len, false);
}

static void onGotXCutTextUTF8(rfbClient *client, const char *text, int len) {
    if (text && len > 0 && text[len - 1] == '\0')
        --len; // LibVNCClient includes terminating NULL in length for UTF8
    onGotXCutText(client, text, len, true);
}

static rfbBool onHandleCursorPos(rfbClient *client, int x, int y) {
    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    jmethodID mid = env->GetMethodID(cls, "cbHandleCursorPos", "(II)V");
    env->CallVoidMethod(obj, mid, x, y);
    takePendingException("onHandleCursorPos");

    return TRUE;
}

static void onFinishedFrameBufferUpdate(rfbClient *client) {
    auto obj = getManagedClient(client);
    auto env = context.getEnv();

    env->CallVoidMethod(obj, context.cbFramebufferUpdated);
    takePendingException("onFinishedFrameBufferUpdate");
}

/**
 * We need to use our own allocator to know when frame size has changed.
 * and to acquire framebuffer lock during modification.
 */

/**
 * Bounds a server can pick for its own framebuffer.
 *
 * Both numbers come from the ServerInit message, so they are chosen by whatever
 * is on the other end of the socket, not by this app. `width * height * bpp` is
 * then malloc'ed and memset'ed here. Linux overcommits by default, so a 64-bit
 * build happily "succeeds" a 17 GB malloc for a 65535x65535 desktop - and the
 * memset that follows touches every page, which gets the whole process killed by
 * the low memory killer rather than returning an error anyone could display.
 * The old `>= SIZE_MAX` test could never fire on 64-bit, which is where nearly
 * every device running this app lives.
 *
 * 16384 is far above any real display (8K UHD is 7680 wide) and the byte cap
 * covers a full 8K RGBA framebuffer with room to spare, so nothing a legitimate
 * server can send is refused.
 */
static const int MAX_FRAME_BUFFER_DIMENSION = 16384;
static const uint64_t MAX_FRAME_BUFFER_BYTES = 256ull * 1024 * 1024;

static rfbBool onMallocFrameBuffer(rfbClient *client) {

    const auto width = client->width;
    const auto height = client->height;
    const auto requestedSize = (uint64_t) width * height * client->format.bitsPerPixel / 8;

    if (width <= 0 || height <= 0
        || width > MAX_FRAME_BUFFER_DIMENSION
        || height > MAX_FRAME_BUFFER_DIMENSION
        || requestedSize > MAX_FRAME_BUFFER_BYTES) {
        rfbClientErr("CRITICAL: refusing framebuffer of %dx%d (%llu bytes)\n",
                     width, height, (unsigned long long) requestedSize);
        return FALSE;
    }

    auto allocSize = (size_t) requestedSize;
    auto ex = getClientExtension(client);

    LOCK(ex->mutex);
    {

        if (client->frameBuffer)
            free(client->frameBuffer);

        client->frameBuffer = static_cast<uint8_t *>(malloc(allocSize));

        if (client->frameBuffer) {
            ex->fbRealWidth = width;
            ex->fbRealHeight = height;
            // A new buffer means a new texture size: drop the allocation marker so the next
            // upload re-specifies the texture in full, and forget any partial box collected
            // against the old buffer.
            ex->texWidth = 0;
            ex->texHeight = 0;
            ex->dirtyX1 = ex->dirtyY1 = ex->dirtyX2 = ex->dirtyY2 = 0;
            memset(client->frameBuffer, 0, allocSize); //Clear any garbage
        } else {
            ex->fbRealWidth = 0;
            ex->fbRealHeight = 0;
        }
    }
    UNLOCK(ex->mutex);

    if (client->frameBuffer == nullptr) {
        rfbClientErr("CRITICAL: frameBuffer allocation failed\n");
        return FALSE;
    }

    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    auto mid = env->GetMethodID(cls, "cbFramebufferSizeChanged", "(II)V");
    env->CallVoidMethod(obj, mid, width, height);
    takePendingException("onMallocFrameBuffer");

    return TRUE;
}

static void onGotCursorShape(rfbClient *client, int xHot, int yHot, int width, int height, int /*bytesPerPixel*/) {
    auto ex = getClientExtension(client);
    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    LOCK(ex->mutex);
    updateCursor(ex->cursor, client->rcSource, client->rcMask, (uint16_t) width, (uint16_t) height,
                 (uint16_t) xHot, (uint16_t) yHot);
    UNLOCK(ex->mutex);

    jmethodID mid = env->GetMethodID(cls, "cbHandleCursorInfo", "(IIII)V");
    env->CallVoidMethod(obj, mid, width, height, xHot, yHot);
    takePendingException("onGotCursorShape");
}

/**
 * Called by libvncclient once per rectangle it has just written into the framebuffer
 * (cursor, pointer-position and size pseudo-rects never reach it). We only grow a bounding
 * box here: the pixels are already in client->frameBuffer, and the renderer decides what
 * slice of it to push to the GPU when it next uploads. Unioning rather than tracking every
 * rect keeps this O(1) on the receive thread.
 */
static void onGotFrameBufferUpdate(rfbClient *client, int x, int y, int w, int h) {
    auto ex = getClientExtension(client);

    LOCK(ex->mutex);
    if (ex->dirtyX2 <= ex->dirtyX1 || ex->dirtyY2 <= ex->dirtyY1) {
        ex->dirtyX1 = x;
        ex->dirtyY1 = y;
        ex->dirtyX2 = x + w;
        ex->dirtyY2 = y + h;
    } else {
        if (x < ex->dirtyX1) ex->dirtyX1 = x;
        if (y < ex->dirtyY1) ex->dirtyY1 = y;
        if (x + w > ex->dirtyX2) ex->dirtyX2 = x + w;
        if (y + h > ex->dirtyY2) ex->dirtyY2 = y + h;
    }
    UNLOCK(ex->mutex);
}

/**
 * Hooks callbacks to rfbClient.
 */
static void setCallbacks(rfbClient *client) {
    client->GetPassword = onGetPassword;
    client->GetCredential = onGetCredential;
    client->VerifyServerCertificate = onVerifyServerCertificate;
    client->Bell = onBell;
    client->GotXCutText = onGotXCutTextLatin1;
    client->GotXCutTextUTF8 = onGotXCutTextUTF8;
    client->HandleCursorPos = onHandleCursorPos;
    client->FinishedFrameBufferUpdate = onFinishedFrameBufferUpdate;
    client->MallocFrameBuffer = onMallocFrameBuffer;
    client->GotFrameBufferUpdate = onGotFrameBufferUpdate;
    client->GotCursorShape = onGotCursorShape;
}


/******************************************************************************
 * Native method Implementation
 *****************************************************************************/

extern "C"
JNIEXPORT jlong JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeClientCreate(JNIEnv *env, jobject thiz) {
    rfbClient *client = rfbGetClient(8, 3, 4);
    if (client == nullptr)
        return 0;

    auto ex = assignClientExtension(client);
    if (!ex) {
        // The client was allocated by rfbGetClient and nothing else holds a
        // reference to it once the extension is missing, so returning the null
        // pointer alone would leak the whole structure.
        rfbClientCleanup(client);
        return 0;
    }

    setCallbacks(client);
    client->canHandleNewFBSize = TRUE;

    //Attach reference to managed object
    auto obj = env->NewGlobalRef(thiz);
    setManagedClient(client, obj);

    return (jlong) client;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeConfigure(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr,
                                                   jint securityType, jboolean use_local_cursor, jint image_quality,
                                                   jboolean use_raw_encoding) {
    auto client = (rfbClient *) client_ptr;

    // 0 means all auth types
    if (securityType != 0) {
        uint32_t auth[1] = {static_cast<uint32_t>(securityType)};
        SetClientAuthSchemes(client, auth, 1);
    }

    if (use_local_cursor) {
        client->appData.useRemoteCursor = TRUE;
        getClientExtension(client)->cursor = newCursor();
    }

    client->appData.qualityLevel = image_quality;

#ifdef LIBVNCSERVER_HAVE_LIBJPEG
    // AVNC parity: apart from the raw-preview mode nothing else is set here.
    // LibVNCClient's initAppData() (vncviewer.c) installs
    // "tight zrle ultra copyrect hextile zlib corre rre raw" and enableJPEG,
    // and Tight is what makes SendEncodings() append a rfbEncodingQualityLevel
    // token (rfbclient.c) - so the level assigned above actually reaches the
    // server and every step of the quality slider shows up in the picture.
    // This branch needs the libjpeg archive built by extern's
    // build-libjpeg-turbo.ps1; CMakeLists.txt defines the macro when it exists.
    if (use_raw_encoding)
        client->appData.encodingsString = "raw";
#else
    // No libjpeg in this build, so the tight token - and with it the quality
    // level - is compiled out of the library default list, and every quality
    // setting produced the same picture. ZYWRLE is the one lossy encoding whose
    // decoder is here (zrle.c needs zlib only) and whose wavelet synthesis reads
    // the same qualityLevel, and SendEncodings() advertises a level for it as
    // well. Ask for it explicitly, ahead of the lossless zrle so that servers
    // offering nothing but zrle fall through instead of failing the negotiation.
    if (use_raw_encoding)
        client->appData.encodingsString = "raw";
    else
        client->appData.encodingsString = "zywrle zrle ultra copyrect hextile zlib corre rre raw";
#endif

    // Change pixel format to match with the default format used by most VNC
    // servers. Technically, we should not have to this as VNC servers have to
    // respect whatever pixel format client prefers. But there are some wierd
    // servers which ignore pixel format sent by client. As a result, users
    // blame AVNC for not rendering the colors properly.
    // Note: This change affects how OpenGL Texture for framebuffer is rendered,
    // and it only works for 24 bit-per-pixel density.
    client->format.redShift = 16;
    client->format.greenShift = 8;
    client->format.blueShift = 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeSetDest(JNIEnv *env, jobject /*thiz*/, jlong client_ptr,
                                                 jstring host, jint port) {
    auto client = (rfbClient *) client_ptr;
    client->destHost = getNativeStrCopy(env, host);
    client->destPort = port;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeInit(JNIEnv *env, jobject /*thiz*/, jlong client_ptr,
                                              jstring host, jint port) {
    auto client = (rfbClient *) client_ptr;

    client->serverHost = getNativeStrCopy(env, host);
    client->serverPort = port < 100 ? port + 5900 : port;

    if (rfbClientConnect(client) && rfbClientInitialise(client)) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeCleanup(JNIEnv *env, jobject /*thiz*/,
                                                 jlong client_ptr) {
    auto client = (rfbClient *) client_ptr;

    if (client->frameBuffer) {
        free(client->frameBuffer);
        client->frameBuffer = nullptr;
    }

    auto managedClient = getManagedClient(client);
    env->DeleteGlobalRef(managedClient);

    freeClientExtension(client);
    rfbClientCleanup(client);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeProcessServerMessage(JNIEnv * /*env*/, jobject /*thiz*/,
                                                              jlong client_ptr) {
    auto client = (rfbClient *) client_ptr;

    auto waitResult = WaitForMessage(client, 1000000);

    if (waitResult == 0) // Timeout
        return JNI_TRUE;

    if (waitResult > 0 && HandleRFBServerMessage(client))
        return JNI_TRUE;

    return JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeGetLastErrorStr(JNIEnv *env, jobject/*thiz*/) {
    auto str = errnoToStr(errno);
    return env->NewStringUTF(str);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeSendKeyEvent(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr,
                                                      jint key_sym, jint xt_code, jboolean is_down) {
    auto client = (rfbClient *) client_ptr;
    rfbBool down = is_down ? TRUE : FALSE;

    // Remap Alt key to Meta key for MacOS
    auto isServerMacOS = client->serverMajor == 3 && client->serverMinor == 889;
    if (isServerMacOS) {
        if (key_sym == 0xffe9  /* Left alt  */) key_sym = 0xffe7  /* Left meta  */;
        if (key_sym == 0xffea  /* Right alt */) key_sym = 0xffe8  /* Right meta */;
    }

    if (xt_code > 0 && SendExtendedKeyEvent(client, key_sym, xt_code, down))
        return JNI_TRUE;
    else
        return SendKeyEvent(client, key_sym, down);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeSendPointerEvent(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr,
                                                          jint x, jint y, jint mask) {
    return (jboolean) SendPointerEvent((rfbClient *) client_ptr, x, y, mask);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeSendCutText(JNIEnv *env, jobject /*thiz*/, jlong client_ptr, jbyteArray bytes,
                                                     jboolean is_utf8) {
    auto client = (rfbClient *) client_ptr;
    auto textBuffer = env->GetByteArrayElements(bytes, nullptr);
    auto textLen = env->GetArrayLength(bytes);
    auto textChars = reinterpret_cast<char *>(textBuffer);

    rfbBool result = is_utf8
                     ? SendClientCutTextUTF8(client, textChars, textLen)
                     : SendClientCutText(client, textChars, textLen);

    env->ReleaseByteArrayElements(bytes, textBuffer, JNI_ABORT);
    return (jboolean) result;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeIsUTF8CutTextSupported(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {
    return (jboolean) (((rfbClient *) client_ptr)->extendedClipboardServerCapabilities != 0);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeSetDesktopSize(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr,
                                                        jint width, jint height) {
    return (jboolean) SendExtDesktopSize((rfbClient *) client_ptr, width, height);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeCancelDesktopSizeChange(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {
    auto client = (rfbClient *) client_ptr;

    // SendExtDesktopSize() raises this guard just before it asks the server for a new
    // size, and only a reply clears it. While it is up, every framebuffer update
    // request is dropped with "Skipping Update - resize in progress", so a server
    // which ignores the request leaves the viewer on a picture that never changes
    // again. The size is no longer wanted, so lower the guard and ask for the frame
    // the host actually has.
    client->requestedResize = FALSE;
    SendFramebufferUpdateRequest(client, 0, 0, client->width, client->height, FALSE);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeRefreshFrameBuffer(JNIEnv * /*env*/, jobject /*thiz*/, jlong clientPtr) {
    auto client = (rfbClient *) clientPtr;
    return (jboolean) SendFramebufferUpdateRequest(client, 0, 0, client->width, client->height, TRUE);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_vnc_RfbClientNative_nativePauseFramebufferUpdates(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr,
                                                                 jboolean pause) {
    ((rfbClient *) client_ptr)->pauseFramebufferUpdates = pause;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeGetDesktopName(JNIEnv *env, jobject /*thiz*/, jlong client_ptr) {
    auto client = (rfbClient *) client_ptr;
    return env->NewStringUTF(client->desktopName ? client->desktopName : "");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeGetWidth(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {
    return ((rfbClient *) client_ptr)->width;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeGetHeight(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {
    return ((rfbClient *) client_ptr)->height;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeIsEncrypted(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {
    return static_cast<jboolean>(((rfbClient *) client_ptr)->tlsSession ? JNI_TRUE : JNI_FALSE);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeUploadFrameTexture(JNIEnv * /*env*/, jobject /*thiz*/,
                                                            jlong client_ptr) {
    auto client = (rfbClient *) client_ptr;
    auto ex = getClientExtension(client);

    LOCK(ex->mutex);

    if (!client->frameBuffer) {
        UNLOCK(ex->mutex);
        return;
    }

    const int width = ex->fbRealWidth;
    const int height = ex->fbRealHeight;
    const int bpp = client->format.bitsPerPixel / 8;

    if (ex->texWidth != width || ex->texHeight != height) {
        // First frame after (re)connecting, a server resize, or a GL context loss - the
        // texture has no storage at this size, so allocate it and hand over the whole
        // picture. This is also the one path that ignores the dirty box: it covers everything.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, client->frameBuffer);
        ex->texWidth = width;
        ex->texHeight = height;
    } else {
        // Storage already matches: push only what changed. A desktop spends most of its
        // time redrawing a small corner - a caret, a clock, one window - and re-sending the
        // whole framebuffer for that was what made motion stutter. GLES2 has no
        // GL_UNPACK_ROW_LENGTH, so a partial-width box must go row by row; a box spanning
        // the full width is contiguous and uploads in a single call.
        int x1 = ex->dirtyX1, y1 = ex->dirtyY1, x2 = ex->dirtyX2, y2 = ex->dirtyY2;
        if (x1 < 0) x1 = 0;
        if (y1 < 0) y1 = 0;
        if (x2 > width) x2 = width;
        if (y2 > height) y2 = height;
        const int bw = x2 - x1;
        const int bh = y2 - y1;

        if (bw > 0 && bh > 0) {
            if (bw == width) {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y1, width, bh,
                                GL_RGBA, GL_UNSIGNED_BYTE,
                                client->frameBuffer + (size_t) y1 * width * bpp);
            } else if ((long long) bw * bh > (long long) width * height / 4) {
                // The change already covers a quarter of the screen (a drag, a page flip):
                // one full upload beats the per-row loop's call overhead.
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                                GL_RGBA, GL_UNSIGNED_BYTE, client->frameBuffer);
            } else {
                for (int row = y1; row < y2; row++) {
                    glTexSubImage2D(GL_TEXTURE_2D, 0, x1, row, bw, 1,
                                    GL_RGBA, GL_UNSIGNED_BYTE,
                                    client->frameBuffer + ((size_t) row * width + x1) * bpp);
                }
            }
        }
    }

    // Everything collected so far is now on the GPU; start the next box fresh.
    ex->dirtyX1 = ex->dirtyY1 = ex->dirtyX2 = ex->dirtyY2 = 0;

    UNLOCK(ex->mutex);

    // Note: client->frameBuffer data is actually in 'BGRA' format, instead of 'RGBA'.
    // But OpenGL ES doesn't support that directly. So we use 'GL_RGBA' here, and flip
    // the components to correct order inside fragment shader.
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeInvalidateFrameTexture(JNIEnv * /*env*/, jobject /*thiz*/,
                                                                jlong client_ptr) {
    auto ex = getClientExtension((rfbClient *) client_ptr);

    // The GL side recreated the texture (its context was lost and restored), so the storage
    // our size marker describes no longer exists. Force the next upload back through
    // glTexImage2D rather than writing into a texture that is not allocated.
    LOCK(ex->mutex);
    ex->texWidth = 0;
    ex->texHeight = 0;
    UNLOCK(ex->mutex);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_vnc_RfbClientNative_nativeUploadCursorTexture(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {

    auto client = (rfbClient *) client_ptr;
    auto ex = getClientExtension(client);
    auto cursor = ex->cursor;

    if (!cursor || !cursor->buffer)
        return;

    LOCK(ex->mutex);

    glTexImage2D(GL_TEXTURE_2D,
                 0,
                 GL_RGBA,
                 cursor->width,
                 cursor->height,
                 0,
                 GL_RGBA,
                 GL_UNSIGNED_BYTE,
                 cursor->buffer);

    UNLOCK(ex->mutex);
}
