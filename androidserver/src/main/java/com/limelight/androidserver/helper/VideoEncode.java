/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver.helper;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.system.ErrnoException;
import android.view.Surface;
import com.limelight.androidserver.Scrcpy;
import com.limelight.androidserver.entity.Device;
import com.limelight.androidserver.entity.Options;
import com.limelight.androidserver.utils.L;
import com.limelight.androidserver.wrappers.DisplayManager;
import com.limelight.androidserver.wrappers.SurfaceControl;
import com.limelight.androidserver.wrappers.WindowManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

public final class VideoEncode {
    private static MediaCodec encoder;
    private static MediaFormat encoderFormat;
    public static boolean isHasChangeConfig = false;
    private static boolean useH265;

    private static IBinder display;
    // Written by the encode thread, read by release() on the main thread: a plain HashMap
    // being resized on one thread while another walks it can spin forever.
    private static final ConcurrentHashMap<Integer, VirtualDisplay> virtualDisplays = new ConcurrentHashMap<>();

    public static void init() throws Exception {
        // Device.init() normally settles the geometry. A display that disappeared during
        // startup leaves it unset, and the null dereference below would surface to the
        // user as an unexplained connection failure rather than as the missing display
        // that it is.
        if (Device.videoSize == null)
            throw new Exception("cannot determine the size of display " + Device.displayId);
        useH265 = Options.useH265 && Device.isEncoderSupport("hevc");
        ByteBuffer byteBuffer = ByteBuffer.allocate(9);
        byteBuffer.put((byte) (useH265 ? 1 : 0));
        byteBuffer.putInt(Device.videoSize.first);
        byteBuffer.putInt(Device.videoSize.second);
        byteBuffer.flip();
        Scrcpy.writeVideo(byteBuffer);
        // 创建显示器
        try {
            display = SurfaceControl.createDisplay("connectsome", Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(Build.VERSION.CODENAME)));
        } catch (Exception e) {
            L.w("createDisplay by SurfaceControl error", e);
            Options.mirrorMode = 1;
        }
        // 创建Codec
        createEncoderFormat(true);
        startEncode();
    }

    private static void createEncoderFormat(boolean withHints) throws IOException {
        String codecMime = useH265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        encoder = MediaCodec.createEncoderByType(codecMime);
        encoderFormat = new MediaFormat();

        encoderFormat.setString(MediaFormat.KEY_MIME, codecMime);

        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, Options.maxVideoBit);
        // Constant bitrate. The default (VBR) spends bursts above the target on busy
        // moments, and on a lossy WiFi link those bursts are exactly what overruns the
        // radio and turns into a retransmit stall - a visible hitch. CBR keeps the
        // per-second load flat, so the link is sized for the steady rate, not the peak.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            encoderFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, Options.maxFps);
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            encoderFormat.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, Options.maxFps * 3);
        encoderFormat.setFloat("max-fps-to-encoder", Options.maxFps);

        encoderFormat.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 50_000);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

        // Real-time, low-latency encode - the single biggest end-to-end latency lever on
        // this side. Priority 0 (real-time) tells the hardware encoder to spend what it has
        // hitting the frame deadline instead of holding frames for rate-control smoothing or
        // motion-estimation lookahead; KEY_LATENCY (API 26+) is the explicit "as few frames
        // in flight as possible" hint. A mirror that shows *now* beats one that is marginally
        // smoother but a frame behind. Guarded because this jar also runs on pre-26
        // devices, and rejected outright (not just ignored) by some vendor codecs -
        // startEncode() retries without the hints when that happens.
        if (withHints) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                encoderFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                encoderFormat.setInteger(MediaFormat.KEY_LATENCY, 1);
        }
    }

    // 初始化编码器
    private static Surface surface;

    public static void startEncode() throws Exception {
        // Also reached from the config-change path, where a fold can have left the requested
        // display gone. Restarting the codec with a null size is an NPE, not an error.
        if (Device.videoSize == null)
            throw new Exception("cannot determine the size of display " + Device.displayId);
        encoderFormat.setInteger(MediaFormat.KEY_WIDTH, Device.videoSize.first);
        encoderFormat.setInteger(MediaFormat.KEY_HEIGHT, Device.videoSize.second);
        try {
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            // Some vendor encoders fail configure outright on the latency hints instead
            // of ignoring them. A failed configure invalidates the instance, so rebuild
            // codec and format without the hints - late picture beats no picture.
            L.w("low-latency encoder configure failed, retrying without hints", e);
            try {
                encoder.release();
            } catch (Exception ignored) {
            }
            createEncoderFormat(false);
            encoderFormat.setInteger(MediaFormat.KEY_WIDTH, Device.videoSize.first);
            encoderFormat.setInteger(MediaFormat.KEY_HEIGHT, Device.videoSize.second);
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        // 绑定Display和Surface
        surface = encoder.createInputSurface();
        if (Device.displayId != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            Options.mirrorMode = 1;
        if (Options.mirrorMode == 1) {
            try {
                VirtualDisplay virtualDisplay = virtualDisplays.get(Device.displayId);
                if (virtualDisplay != null) virtualDisplay.release();
                virtualDisplay = DisplayManager.createVirtualDisplay("connectsome",
                        Device.videoSize.first, Device.videoSize.second, Device.displayId, surface);
                virtualDisplays.put(Device.displayId, virtualDisplay);
                int displayId = virtualDisplay.getDisplay().getDisplayId();
                WindowManager.freezeRotation(displayId, 0);
                Device.display2virtualDisplay.put(Device.displayId, displayId);
                L.d("mirroring display " + Device.displayId + " to " + displayId + " with size " + Device.videoSize.first + "x" + Device.videoSize.second);
            } catch (Exception e) {
                L.e("createVirtualDisplay by DisplayManager error", e);
                throw e;
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                SurfaceControl.destroyDisplay(display);
                display = SurfaceControl.createDisplay("connectsome", false);
            }
            setDisplaySurface(display, surface);
        }
        // 启动编码
        encoder.start();
        ControlPacket.sendVideoSizeEvent();
    }

    public static void stopEncode() {
        // Both calls throw when the codec never got configured (a failed startEncode).
        // The caller needs the surface released either way, so keep going.
        try {
            encoder.stop();
            encoder.reset();
        } catch (IllegalStateException e) {
            L.w("stopEncode on an unusable codec", e);
        }
        try {
            surface.release();
        } catch (Exception e) {
            L.w("release surface error", e);
        } finally {
            surface = null;
        }
    }

    private static void setDisplaySurface(IBinder display, Surface surface) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, new Rect(0, 0, Device.deviceSize.first, Device.deviceSize.second), new Rect(0, 0, Device.videoSize.first, Device.videoSize.second));
            SurfaceControl.setDisplayLayerStack(display, Device.layerStack);
        } finally {
            SurfaceControl.closeTransaction();
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
            // The buffer belongs to the codec until it is handed back. Returning without
            // this call permanently starves the output queue once the pool runs dry - and
            // an empty pool makes dequeueOutput() block forever, which reads as a frozen
            // picture rather than an error.
            if (buffer != null && buffer.remaining() > 0) ControlPacket.sendVideoEvent(bufferInfo.presentationTimeUs, buffer);
        } finally {
            encoder.releaseOutputBuffer(outIndex, false);
        }
    }

    /**
     * Blocks until the codec has an output buffer. A codec that left its started state
     * throws immediately, and the surrounding loops would turn that into a spin that
     * burns a core - so the exception is translated into an end of the session.
     */
    private static int dequeueOutput() throws IOException {
        while (true) {
            int outIndex;
            try {
                outIndex = encoder.dequeueOutputBuffer(bufferInfo, -1);
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new IOException("video codec is not usable", e);
            }
            if (outIndex >= 0) return outIndex;
        }
    }

    public static void release() {
        try {
            if (encoder != null) stopEncode();
        } catch (Exception e) {
            L.e("stopEncode error", e);
        }
        try {
            if (encoder != null) encoder.release();
        } catch (Exception e) {
            L.e("encoder release error", e);
        }
        try {
            if (display != null) SurfaceControl.destroyDisplay(display);
        } catch (Exception e) {
            L.e("destroyDisplay error", e);
        }
        // Every one of these gets an independent try: a virtual display that is not
        // released stays registered on the device after this process is gone.
        for (VirtualDisplay virtualDisplay : virtualDisplays.values()) {
            try {
                virtualDisplay.release();
            } catch (Exception e) {
                L.e("virtual display release error", e);
            }
        }
        virtualDisplays.clear();
    }

}
