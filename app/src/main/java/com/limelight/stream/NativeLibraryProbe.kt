package com.limelight.stream

import com.limelight.util.Log

/**
 * Safely probes whether the optional native libraries of this build are
 * present and loadable.
 *
 * Each protocol owns exactly one of them, and each probe is independent:
 *
 * * `libmoonlight-core.so` (built from the moonlight-common-c submodule) backs
 *   GameStream / Sunshine.
 * * `libnative-vnc.so` (built from the libvncserver submodule) backs RFB/VNC.
 *
 * [com.limelight.nvstream.jni.MoonBridge] has a static initializer that calls
 * `System.loadLibrary("moonlight-core")`, and
 * [com.limelight.vnc.RfbClientNative] has one that loads and initializes
 * `native-vnc`. Touching either class when its library is missing throws
 * [UnsatisfiedLinkError], which would crash the process. Worse, both CMake
 * targets also build a *placeholder* `.so` when their sources are absent: that
 * library loads fine, but exports no symbols, so the very first native call in
 * the static initializer is what fails. This helper catches it once and caches
 * the result so each protocol can be disabled on its own instead of taking the
 * whole app (or the other protocol) down with it.
 */
object NativeLibraryProbe {

    private const val TAG = "NativeLibraryProbe"

    @Volatile
    private var checked = false

    @Volatile
    private var available = false

    @Volatile
    private var vncChecked = false

    @Volatile
    private var vncAvailable = false

    /**
     * @return true if Moonlight's native bridge can be loaded (real streaming
     *         is possible); false if the `.so` is missing (UI-only build).
     */
    fun isNativeAvailable(): Boolean {
        if (checked) return available
        synchronized(this) {
            if (checked) return available
            available = probe("com.limelight.nvstream.jni.MoonBridge", "moonlight-core")
            checked = true
            return available
        }
    }

    /**
     * @return true if the native libvncclient bridge can be loaded, i.e. the VNC
     *         (RFB) protocol is usable in this build.
     */
    fun isVncAvailable(): Boolean {
        if (vncChecked) return vncAvailable
        synchronized(this) {
            if (vncChecked) return vncAvailable
            vncAvailable = probe("com.limelight.vnc.RfbClientNative", "native-vnc")
            vncChecked = true
            return vncAvailable
        }
    }

    /**
     * Forces execution of [className]'s static initializer, which loads
     * [libraryName] and immediately calls into it. Any failure - missing class,
     * missing `.so`, or placeholder `.so` without symbols - means the protocol
     * backed by that library is not available.
     */
    private fun probe(className: String, libraryName: String): Boolean {
        return runCatching {
            Class.forName(className)
            true
        }.getOrElse {
            Log.w(TAG, "Native $libraryName library unavailable: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }
}
