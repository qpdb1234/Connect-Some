/*
 * Capabilities only the Android-device protocol has.
 *
 * Kept out of the [VncClient] facade on purpose, exactly like [GameStreamExtras]:
 * a phone has no keysyms, no pointer button mask and no RFB framebuffer
 * encodings, so its abilities must not be part of the shared contract. Callers
 * use `client as? AndroidControlExtras` and degrade silently when the cast fails,
 * which keeps the protocol test inside [VncClientFactory].
 *
 * The two render-target hooks ([VncClient]-adjacent ones) are *not* repeated
 * here: [VideoSink] owns `attachActivity` / `setVideoSurface`, and
 * [com.limelight.session.RemoteSession] uses that narrow interface for both
 * hardware-decoding protocols.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 */
package com.limelight.vnc

import com.limelight.App
import com.limelight.model.ServerProfile
import com.limelight.util.AppPreferences

/**
 * Stream parameters translated into the `key=value` arguments of the
 * controlled-side encoder. Field meanings are fixed by the helper's
 * `Options.parse`, whose defaults are spelled out below: an argument is only
 * sent when it differs from that default, because the helper rejects any
 * argument without an `=`.
 */
data class AndroidStreamConfig(
        /**
         * Long-edge pixel cap (`maxSize`). The helper scales the screen down to
         * this and rounds each edge to a multiple of 8.
         */
        val maxSize: Int = 1600,

        /** Frame rate ceiling (`maxFps`); the helper clamps nothing, so neither should we. */
        val maxFps: Int = 60,

        /** Video bitrate in *megabits* per second - the helper multiplies by 1_000_000. */
        val bitrateMbps: Int = 4,

        /** Ask for HEVC. Only meaningful when the helper also has an HEVC encoder. */
        val useH265: Boolean = false,

        /** Ask for Opus audio; AAC otherwise (see [android.os.Build.VERSION_CODES.Q]). */
        val useOpus: Boolean = true,

        /** Capture the device's system audio at all. */
        val audioEnabled: Boolean = true,

        /**
         * Play the forwarded audio here. False keeps the capture running but
         * drops every packet - the same split as GameStream's `playLocalAudio`.
         */
        val playAudioLocally: Boolean = true,

        /** Push the device's screen-off timeout aside while mirrored. */
        val keepAwake: Boolean = true,

        /**
         * Four decimal digits: start with screen on / start with screen off /
         * stop with screen off / stop with screen on. 1001 is the helper default.
         */
        val screenMode: Int = 1001,

        /** Mirror through a virtual display instead of the surface-control path. */
        val useMirrorMode: Boolean = false,

        /**
         * Largest clipboard payload one packet may carry, in UTF-8 bytes (`clipMax`).
         * Both directions honour it: this client trims what it sends, the helper refuses
         * to send more, and a longer inbound packet means the two are no longer aligned.
         */
        val clipboardMaxBytes: Int = AppPreferences.CLIPBOARD_LIMIT_DEFAULT,
) {
    companion object {
        /**
         * Latency ceiling for the ADB mirror, matching the reference implementation's
         * defaults (Easycontrol: `maxSize` 1600, 4 Mbps). The shared profile fields are
         * GameStream-oriented - 1920x1080 @ 20 Mbps by default - which is far more picture
         * than a phone reached over WiFi/ADB can carry before the pipeline backs up, and a
         * backed-up pipeline is exactly the delay the viewer feels on the other phone. This
         * applies only to a profile nobody has tuned: once the quality panel has written
         * [ServerProfile.fAndroidStreamTuned], the numbers there are the user's own and are
         * obeyed up to the helper's limits below.
         */
        private const val ADB_MAX_SIZE_CEILING = 1600
        private const val ADB_BITRATE_CEILING_MBPS = 4

        /** Frame rate ceiling of an untouched profile - the phone display being mirrored. */
        private const val ADB_UNTUNED_MAX_FPS = 60

        /** Highest frame rate worth asking a phone encoder for, tuned or not. */
        private const val ADB_MAX_FPS = 120

        /**
         * Bitrate bounds the helper's parser accepts (`maxVideoBit` is read as megabits
         * and clamped to 1..100 there); the quality panel refuses the same window so a
         * typed-in number is not silently rewritten on the other side.
         */
        const val ADB_MIN_BITRATE_MBPS = 1
        const val ADB_MAX_BITRATE_MBPS = 100

        /**
         * The saved profile is the only source of stream quality settings, so both
         * [com.limelight.session.RemoteSession] (which pushes the config) and
         * [AndroidClient] (which needs a value before the first call) derive it here.
         *
         * The clipboard ceiling is the one exception: it is a global server preference
         * like [AppPreferences.Server.clipboardSync], not a per-profile quality choice.
         */
        fun forProfile(profile: ServerProfile): AndroidStreamConfig {
            // A hand-picked quality is taken at its word; an inherited one is trimmed.
            val tuned = profile.fAndroidStreamTuned

            // The helper caps the *long* edge; a profile's resolution is
            // orientation-dependent, so the larger of the two is the honest cap.
            // Bounded again because this runs before the session has read the row: a
            // bogus width is an argument the helper's parser rejects, which kills the
            // stream for a setting the user cannot even see.
            val maxSize = minOf(
                    maxOf(profile.streamWidth, profile.streamHeight)
                            .coerceIn(ServerProfile.MIN_STREAM_DIMENSION, ServerProfile.MAX_STREAM_DIMENSION),
                    if (tuned) ServerProfile.MAX_STREAM_DIMENSION else ADB_MAX_SIZE_CEILING)

            return AndroidStreamConfig(
                    maxSize = maxSize,
                    maxFps = profile.streamFps.coerceIn(1,
                            if (tuned) ADB_MAX_FPS else ADB_UNTUNED_MAX_FPS),
                    // The wire carries megabits per second, the profile stores kilobits; the
                    // untuned ceiling keeps a 20 Mbps default from saturating the WiFi link.
                    bitrateMbps = minOf(
                            (profile.bitrateKbps / 1000).coerceIn(ADB_MIN_BITRATE_MBPS, ADB_MAX_BITRATE_MBPS),
                            if (tuned) ADB_MAX_BITRATE_MBPS else ADB_BITRATE_CEILING_MBPS),
                    useH265 = profile.androidUseH265,
                    useOpus = profile.androidUseOpus,
                    audioEnabled = profile.androidAudio,
                    playAudioLocally = profile.playLocalAudio,
                    keepAwake = profile.androidKeepAwake,
                    clipboardMaxBytes = AppPreferences(App.appContext).server.let {
                            if (it.clipboardCustomLimit) it.clipboardLimitBytes
                            else AppPreferences.CLIPBOARD_LIMIT_DEFAULT
                    },
            )
        }
    }
}

/**
 * What a connected [AndroidClient] can do beyond the [VncClient] contract.
 *
 * Every method is fire-and-forget: a protocol packet either goes out or the
 * session is already ending, so there is nothing to return.
 */
interface AndroidControlExtras {

    /** Applies [config] for the *next* connection; a live session is not reconfigured. */
    fun setAndroidStreamConfig(config: AndroidStreamConfig)

    /**
     * One pointer of a multi-touch sequence.
     *
     * @param action [android.view.MotionEvent] action, masked to the action bits
     * @param pointerId the pointer's id, stable for the whole gesture
     * @param xNorm horizontal position, normalized to `[0, 1]`
     * @param yNorm vertical position, normalized to `[0, 1]`
     * @param offsetTimeMs milliseconds between the gesture's down time and this event
     */
    fun sendMultiTouch(action: Int, pointerId: Int, xNorm: Float, yNorm: Float, offsetTimeMs: Int)

    /**
     * Presses a key on the device. The protocol has no release: the helper
     * injects a DOWN and an UP back to back, so key holds and repeats are
     * impossible - a limitation of the wire, not of this client.
     */
    fun sendAndroidKey(keyCode: Int, metaState: Int)

    /** -1 toggles between the current orientation and its portrait/landscape swap. */
    fun sendRotate(rotation: Int)

    /** Sends the power *menu* (the key combo that opens it), not a screen toggle. */
    fun sendPower()

    /** Backlight only: an [android.view.Display] power state, e.g. `STATE_ON` / `STATE_OFF`. */
    fun sendScreenPower(state: Int)

    /**
     * Night mode of the device: a [android.app.UiModeManager] mode number, as passed
     * straight through by the helper. The helper remembers the device's previous mode
     * and restores it when the last session to that device ends.
     */
    fun sendNightMode(mode: Int)

    /**
     * Night mode the device is in right now, as the same [android.app.UiModeManager]
     * number - or null when nothing could answer.
     *
     * This is the only piece of remote *state* the protocol reports back. Everything
     * above is fire-and-forget on the video channel, so the question goes to the
     * helper's resident RPC process instead, which means it can also fail on its own:
     * no stream may be up, and an older helper jar may not know the request.
     */
    suspend fun queryNightMode(): Int?

    /**
     * Whether the device's screen is currently lit, or null when nothing could answer.
     *
     * The stream itself never reports this - the picture simply stops - so the read goes
     * to the helper's resident process as well, and can fail the same way: no stream may be
     * up, and the answer describes the device rather than the session.
     */
    suspend fun queryScreenOn(): Boolean?

    /** Tells the helper to re-read its display configuration. */
    fun sendConfigChange(mode: Int)
}
