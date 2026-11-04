/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver.helper;

import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.system.ErrnoException;
import com.limelight.androidserver.Scrcpy;
import com.limelight.androidserver.entity.Device;
import com.limelight.androidserver.entity.Options;
import com.limelight.androidserver.utils.L;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class AudioEncode {
    private static MediaCodec encoder;
    private static AudioRecord audioCapture;
    private static boolean useOpus;

    public static boolean init() throws IOException, ErrnoException {
        useOpus = Options.useOpus && Device.isEncoderSupport("opus");
        byte[] bytes = new byte[]{0};
        try {
            // 从安卓12开始支持音频
            if (!Options.isAudio) throw new Exception("audio not enabled");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw new Exception("audio not supported");
            setAudioEncoder();
            encoder.start();
            audioCapture = AudioCapture.init();
        } catch (Exception e) {
            L.w(e);
            // The encoder may already be started while the capture failed behind it. A
            // started MediaCodec is a reserved hardware instance, and this process keeps
            // running the video path after a failed audio start - so it has to go back.
            release();
            Scrcpy.writeMain(ByteBuffer.wrap(bytes));
            return false;
        }
        bytes[0] = 1;
        Scrcpy.writeMain(ByteBuffer.wrap(bytes));
        bytes[0] = (byte) (useOpus ? 1 : 0);
        Scrcpy.writeMain(ByteBuffer.wrap(bytes));
        return true;
    }

    private static void setAudioEncoder() throws IOException {
        String codecMime = useOpus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
        encoder = MediaCodec.createEncoderByType(codecMime);
        MediaFormat encoderFormat = MediaFormat.createAudioFormat(codecMime, AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameSize);
        if (!useOpus)
            encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private static final int frameSize = AudioCapture.millisToBytes(50);

    public static void encodeIn() throws IOException {
        int inIndex = dequeueInput();
        int read = -1;
        try {
            ByteBuffer buffer = encoder.getInputBuffer(inIndex);
            if (buffer != null) {
                int size = Math.min(buffer.remaining(), frameSize);
                read = audioCapture.read(buffer, size);
                if (read < 0) {
                    // A negative return is an AudioRecord error code, not a length.
                    L.w("audio capture read failed: " + read);
                }
            }
        } catch (IllegalStateException e) {
            throw new IOException("audio codec is not usable", e);
        }
        try {
            // The buffer has to be handed back to the codec in every case - there is no
            // release for input buffers - and an empty queue is what "nothing was captured"
            // looks like. Skipping this call would drain the input queue after a handful of
            // frames and silence audio for the rest of the session.
            encoder.queueInputBuffer(inIndex, 0, Math.max(read, 0), 0, 0);
        } catch (IllegalStateException e) {
            throw new IOException("audio codec is not usable", e);
        }
    }

    private static int dequeueInput() throws IOException {
        while (true) {
            int inIndex;
            try {
                inIndex = encoder.dequeueInputBuffer(-1);
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new IOException("audio codec is not usable", e);
            }
            if (inIndex >= 0) return inIndex;
        }
    }

    private static final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    public static void encodeOut() throws IOException, ErrnoException {
        int outIndex = dequeueOutput();
        ByteBuffer buffer;
        try {
            buffer = encoder.getOutputBuffer(outIndex);
        } catch (IllegalStateException e) {
            Scrcpy.errorClose(e);
            return;
        }
        try {
            if (buffer == null || buffer.remaining() <= 0) return;
            if (useOpus) {
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The CSD is wrapped in two 8 byte fields; skip them so that the host
                    // receives the raw configuration. Guarded, because a codec that does
                    // not follow the layout would otherwise underflow the buffer. By the
                    // time the two words have been read, `remaining()` already is the size
                    // of the payload they describe, so it is the bound to compare against.
                    if (buffer.remaining() >= 16) {
                        buffer.getLong();
                        int size = (int) buffer.getLong();
                        if (size > 0 && size <= buffer.remaining()) buffer.limit(buffer.position() + size);
                    }
                }
                // 当无声音时不发送
                if (buffer.remaining() < 5) return;
            }
            ControlPacket.sendAudioEvent(buffer);
        } catch (IllegalStateException e) {
            L.e("AudioEncode encodeOut error", e);
        } finally {
            encoder.releaseOutputBuffer(outIndex, false);
        }
    }

    private static int dequeueOutput() throws IOException {
        while (true) {
            int outIndex;
            try {
                outIndex = encoder.dequeueOutputBuffer(bufferInfo, -1);
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new IOException("audio codec is not usable", e);
            }
            if (outIndex >= 0) return outIndex;
        }
    }

    public static void release() {
        try {
            if (audioCapture != null) {
                audioCapture.stop();
                audioCapture.release();
                audioCapture = null;
            }
        } catch (Exception e) {
            L.e("audio capture release error", e);
        }
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
                encoder = null;
            }
        } catch (Exception e) {
            L.e("audio encoder release error", e);
        }
    }
}

