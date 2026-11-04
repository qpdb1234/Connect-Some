/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.vnc

import com.limelight.App
import com.limelight.R
import com.limelight.model.ServerProfile
import com.limelight.stream.NativeLibraryProbe

/**
 * The single place where a saved profile's protocol is turned into a concrete
 * [VncClient]. Everywhere else the code either uses the [VncClient] facade or
 * asks for an optional capability (`client as? GameStreamExtras`), so a
 * protocol test never leaks into the UI, input or session layers.
 */
object VncClientFactory {

    /**
     * How the image of [profile] will reach the screen.
     *
     * The GL renderer needs this *before* a client instance exists (the surface
     * is created while the profile is still being loaded from the database), so
     * the answer is derived from the protocol here instead of from a live
     * client. This stays the only protocol test in the app: a client also
     * reports its own [VncClient.renderMode], and both values always agree.
     *
     * Both hardware-decoding protocols (GameStream, Android device) land on a
     * `SurfaceTexture`'s surface; only RFB hands over raw pixels.
     */
    fun renderModeFor(profile: ServerProfile): RenderMode {
        return when (profile.protocol) {
            ServerProfile.PROTOCOL_VNC -> RenderMode.PIXEL_BUFFER
            else -> RenderMode.SURFACE
        }
    }

    fun create(
            profile: ServerProfile,
            observer: VncClient.Observer,
            onConnectionStarted: () -> Unit,
            onConnectionTerminated: (errorCode: Int) -> Unit): VncClient {

        return when (profile.protocol) {
            ServerProfile.PROTOCOL_GAME_STREAM ->
                GameStreamClient(observer, onConnectionStarted, onConnectionTerminated)

            // The helper jar this app pushes to the target device ships as a raw
            // resource built by the packageAndroidServerJar task, so no probing is
            // needed: every build of the app contains it.
            ServerProfile.PROTOCOL_ANDROID ->
                AndroidClient(profile, observer, onConnectionStarted, onConnectionTerminated)

            ServerProfile.PROTOCOL_VNC -> {
                // libnative-vnc.so is optional (see the source-aware VNC block in
                // src/main/cpp/CMakeLists.txt). Probing it here keeps the protocol
                // check in one place: everything downstream either gets a working
                // client or a clean error, and GameStream is untouched either way.
                if (!NativeLibraryProbe.isVncAvailable())
                    throw UnsupportedProtocolException(
                            App.appContext.getString(R.string.msg_vnc_unavailable))

                RfbClientNative(observer, onConnectionStarted, onConnectionTerminated)
            }

            else ->
                throw UnsupportedProtocolException(
                        App.appContext.getString(R.string.error_unknown_protocol, profile.protocol))
        }
    }
}
