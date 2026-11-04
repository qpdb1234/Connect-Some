package com.limelight.stream

/**
 * Stream parameters corresponding to Moonlight's StreamConfiguration.
 * Populated from the extended ServerProfile.
 */
data class StreamConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitrateKbps: Int = 20000,
    val enableHdr: Boolean = false,
    val enableSops: Boolean = true,                 // Optimize game settings
    val playLocalAudio: Boolean = false,
    val audioConfiguration: AudioConfig = AudioConfig.STEREO,
    val supportedVideoFormats: Int = VIDEO_FORMAT_H264 or VIDEO_FORMAT_H265,
    val remoteStreaming: RemoteMode = RemoteMode.AUTO,
    val maxPacketSize: Int = 1024,
    val attachedGamepadMask: Int = 0,
    val colorRange: ColorRange = ColorRange.LIMITED,
    val colorSpace: ColorSpace = ColorSpace.REC_709,
    val persistGamepadsAfterDisconnect: Boolean = false
) {
    enum class AudioConfig(val channels: Int, val mask: Int) {
        STEREO(2, 0x3),
        SURROUND_5_1(6, 0x3F),
        SURROUND_7_1(8, 0x63F)
    }

    enum class RemoteMode {
        LOCAL, REMOTE, AUTO
    }

    enum class ColorRange { LIMITED, FULL }
    enum class ColorSpace { REC_601, REC_709, REC_2020 }

    companion object {
        const val VIDEO_FORMAT_H264 = 0x0001
        const val VIDEO_FORMAT_H265 = 0x0100
        const val VIDEO_FORMAT_H265_MAIN10 = 0x0200
        const val VIDEO_FORMAT_AV1_MAIN8 = 0x1000
        const val VIDEO_FORMAT_AV1_MAIN10 = 0x2000
    }
}
