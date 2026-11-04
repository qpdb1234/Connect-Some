/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver.entity;

import android.content.IOnPrimaryClipChangedListener;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Pair;
import android.view.*;
import com.limelight.androidserver.Channel;
import com.limelight.androidserver.Scrcpy;
import com.limelight.androidserver.helper.ControlPacket;
import com.limelight.androidserver.helper.VideoEncode;
import com.limelight.androidserver.utils.L;
import com.limelight.androidserver.wrappers.SurfaceControl;
import com.limelight.androidserver.wrappers.WindowManager;
import com.limelight.androidserver.wrappers.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Device {
    public static Pair<Integer, Integer> realDeviceSize;
    public static int realDeviceDensity;
    public static Pair<Integer, Integer> deviceSize;
    public static int deviceRotation;
    public static Pair<Integer, Integer> videoSize;
    public static boolean needReset = false;
    public static int oldScreenOffTimeout = 60000;
    public static int oldNightMode = -1;

    public static int displayId;
    public static int layerStack;
    // The encode thread publishes entries while the control thread reads them to route
    // injections; a HashMap being mutated underneath a walk can hang the reader forever.
    public static Map<Integer, Integer> display2virtualDisplay = new ConcurrentHashMap<>();

    public static void init() throws IOException, InterruptedException {
        displayId = Options.displayId;
        getRealDeviceSize();
        getDeviceSize();
        // 旋转监听
        setRotationListener();
        // 折叠监听
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setDisplayFoldListener();
        // 剪切板监听
        setClipBoardListener();
        // 设置不息屏
        if (Options.keepAwake) setKeepScreenLight();
    }

    // 获取真实的设备大小
    private static void getRealDeviceSize() {
        DisplayInfo displayInfo = DisplayManager.getDisplayInfo(Display.DEFAULT_DISPLAY);
        // Even the built-in display is described by a service call that can fail (a
        // hiccough during a fold, a permission change). Everything downstream reads
        // these statics as if they were always filled in, so say so loudly here rather
        // than letting the first touch throw.
        if (displayInfo == null || displayInfo.size == null) {
            L.w("no geometry for the built-in display");
            return;
        }
        realDeviceSize = displayInfo.size;
        realDeviceDensity = displayInfo.density;
        deviceRotation = displayInfo.rotation;
        if (deviceRotation == 1 || deviceRotation == 3)
            realDeviceSize = new Pair<>(realDeviceSize.second, realDeviceSize.first);
    }

    private static void getDeviceSize() {
        DisplayInfo displayInfo = DisplayManager.getDisplayInfo(displayId);
        if (displayInfo == null || displayInfo.size == null) {
            // A display that came and went between the rotation callback and here. The
            // previous geometry is stale but usable; a crash would end the whole session.
            L.w("no geometry for display " + displayId);
            if (deviceSize == null) {
                // The first call has no previous geometry to fall back on. Every reader of
                // deviceSize/videoSize assumes one exists - the codec dimensions, the
                // projection rect, the scaling of an injected touch - so borrow the
                // built-in display's numbers: a session with slightly wrong metrics beats
                // a NullPointerException on the way in.
                deviceSize = realDeviceSize;
                layerStack = 0;
                getVideoSize();
            }
            return;
        }
        deviceSize = displayInfo.size;
        deviceRotation = displayInfo.rotation;
        layerStack = displayInfo.layerStack;
        getVideoSize();
    }

    public static void handleConfigChanged(int mode) {
        if (mode <= 0) {
            try {
                DisplayInfo test = DisplayManager.getDisplayInfo(-mode);
                if (test == null) throw new Exception();
                displayId = -mode;
                WindowManager.removeRotationWatcher();
                setRotationListener();
            } catch (Throwable e) {
                L.w("failed to switch display");
            }
        }
        getDeviceSize();
        if (mode == 1) needReset = true;
        VideoEncode.isHasChangeConfig = true;
    }

    private static void getVideoSize() {
        if (Options.maxSize <= 0 || deviceSize == null) {
            videoSize = deviceSize;
            return;
        }
        boolean isPortrait = deviceSize.first < deviceSize.second;
        int major = isPortrait ? deviceSize.second : deviceSize.first;
        int minor = isPortrait ? deviceSize.first : deviceSize.second;
        if (major > Options.maxSize) {
            minor = minor * Options.maxSize / major;
            major = Options.maxSize;
        }
        // h264只接受8的倍数，所以需要缩放至最近参数
        minor = minor + 4 & ~7;
        major = major + 4 & ~7;
        // A zero or negative dimension reaches the codec as an IllegalArgumentException and
        // the failure is reported as a broken connection rather than as what it is.
        if (minor <= 0 || major <= 0) {
            L.w("refusing degenerate video size " + minor + "x" + major);
            return;
        }
        videoSize = isPortrait ? new Pair<>(minor, major) : new Pair<>(major, minor);
    }

    private static volatile String nowClipboardText = "";

    private static void setClipBoardListener() {
        ClipboardManager.addPrimaryClipChangedListener(new IOnPrimaryClipChangedListener.Stub() {
            public void dispatchPrimaryClipChanged() {
                // A binder thread: anything escaping here is an uncaught exception in the
                // callback dispatch, which the runtime turns into the death of this whole
                // process - and a copy that raced a closed socket is not worth that.
                try {
                    String newClipboardText = ClipboardManager.getText();
                    if (newClipboardText == null) return;
                    if (!newClipboardText.equals(nowClipboardText)) {
                        nowClipboardText = newClipboardText;
                        // 发送报文
                        ControlPacket.sendClipboardEvent(nowClipboardText);
                    }
                } catch (Throwable t) {
                    L.e("clipboard forwarding failed", t);
                }
            }
        });
    }

    public static void setClipboardText(String text) {
        nowClipboardText = text;
        try {
            ClipboardManager.setText(nowClipboardText);
        } catch (Exception e) {
            // Setting the clip can fail on a device with a locked-out clipboard (or one
            // whose clipboard service is not reachable). The controller asked for a copy,
            // the paste simply does not arrive - the session must survive it.
            L.e("failed to set clipboard text", e);
        }
    }

    private static void setRotationListener() {
        WindowManager.registerRotationWatcher(new IRotationWatcher.Stub() {
            public void onRotationChanged(int rotation) {
                // Called on a binder thread, possibly before init() has settled the
                // geometry (a rotation arriving while the display is being picked). The
                // swap reads the old pair, so it has to be guarded.
                if ((deviceRotation + rotation) % 2 != 0) {
                    if (deviceSize != null) deviceSize = new Pair<>(deviceSize.second, deviceSize.first);
                    if (videoSize != null) videoSize = new Pair<>(videoSize.second, videoSize.first);
                }
                deviceRotation = rotation;
                VideoEncode.isHasChangeConfig = true;
            }
        }, displayId);
    }

    private static void setDisplayFoldListener() {
        WindowManager.registerDisplayFoldListener(new IDisplayFoldListener.Stub() {
            @Override
            public void onDisplayFoldChanged(int displayId, boolean folded) {
                if (Device.displayId != displayId) return;
                Scrcpy.postDelayed(() -> {
                    getDeviceSize();
                    VideoEncode.isHasChangeConfig = true;
                }, 1000);
            }
        });
    }

    private static final PointersState pointersState = new PointersState();

    public static void touchEvent(int action, Float x, Float y, int pointerId, int offsetTime) {
        Pointer pointer = pointersState.get(pointerId);

        if (pointer == null) {
            if (action != MotionEvent.ACTION_DOWN) return;
            pointer = pointersState.newPointer(pointerId, SystemClock.uptimeMillis() - 50);
        }

        if (pointer == null) return;
        // Coordinates arrive as floats straight off the wire. A NaN or an out of range
        // value is not merely wrong: it lands in MotionEvent.PointerCoords and from there
        // into the input dispatcher, which does not validate them.
        float nx = sanitizeCoordinate(x);
        float ny = sanitizeCoordinate(y);
        // Which geometry to scale onto depends on the mode, and either of them can still be
        // unset when the display vanished during startup. Dropping the event is wrong in
        // one frame; throwing here kills the encoder thread and the session with it.
        Pair<Integer, Integer> scale = (Options.mirrorMode == 1) ? videoSize : deviceSize;
        if (scale == null) scale = (deviceSize != null) ? deviceSize : videoSize;
        if (scale == null) {
            L.w("no display geometry to scale a touch onto, event dropped");
            return;
        }
        long when = offsetTime > 0 ? offsetTime : 0;
        pointer.x = nx * scale.first;
        pointer.y = ny * scale.second;
        int pointerCount = pointersState.update();

        if (action == MotionEvent.ACTION_UP) {
            pointersState.remove(pointerId);
            if (pointerCount > 1)
                action = MotionEvent.ACTION_POINTER_UP | (pointer.id << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        } else if (action == MotionEvent.ACTION_DOWN) {
            if (pointerCount > 1)
                action = MotionEvent.ACTION_POINTER_DOWN | (pointer.id << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        MotionEvent event = MotionEvent.obtain(pointer.downTime, pointer.downTime + when, action, pointerCount, pointersState.pointerProperties, pointersState.pointerCoords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        if (Options.mirrorMode == 1 && display2virtualDisplay.containsKey(displayId))
            injectEvent(event, display2virtualDisplay.get(displayId));
        else
            injectEvent(event, displayId);
    }

    private static float sanitizeCoordinate(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        return Math.max(0f, Math.min(value, 1f));
    }

    public static void keyEvent(int keyCode, int meta, int displayIdToInject) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event1 = new KeyEvent(now, now, MotionEvent.ACTION_DOWN, keyCode, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent event2 = new KeyEvent(now, now, MotionEvent.ACTION_UP, keyCode, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
        injectEvent(event1, displayIdToInject);
        injectEvent(event2, displayIdToInject);
    }

    private static void injectEvent(InputEvent inputEvent, int displayIdToInject) {
        try {
            if (displayIdToInject != Display.DEFAULT_DISPLAY)
                InputManager.setDisplayId(inputEvent, displayIdToInject);
            InputManager.injectInputEvent(inputEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (Exception e) {
            L.e("injectEvent error", e);
        }
    }


    public static void changeScreenPowerMode(int mode) {
        // The mode comes straight off the wire and reaches a surfaceflinger call that
        // accepts any int. An out of range value does not merely do nothing: the power
        // manager can end up in a state where the panel stays blank while the session
        // keeps running, which looks to the user like the device froze.
        if (!isKnownPowerState(mode)) {
            L.w("ignoring unknown screen power state: " + mode);
            return;
        }
        if (mode == Display.STATE_ON) {
            // setDisplayPowerMode drives the panel only. A device that has actually gone to
            // sleep keeps its power state Asleep and re-blanks the panel a moment later, so
            // the request alone never brings the picture back. KEYCODE_WAKEUP is what the
            // power manager obeys, and on a device that is already awake it does nothing.
            keyEvent(224, 0, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                long[] physicalDisplayIds = SurfaceControl.getPhysicalDisplayIds();
                if (physicalDisplayIds == null) throw new Exception();
                for (long physicalDisplayId : physicalDisplayIds) {
                    IBinder token = SurfaceControl.getPhysicalDisplayToken(physicalDisplayId);
                    if (token != null) SurfaceControl.setDisplayPowerMode(token, mode);
                }
                return;
            } catch (Exception ignored) {
                L.w("change power mode for all screens error, try built-in display");
            }
        }
        try {
            IBinder d = SurfaceControl.getBuiltInDisplay();
            if (d != null) SurfaceControl.setDisplayPowerMode(d, mode);
        } catch (Exception e) {
            L.e("change screen power mode error", e);
        }
    }

    private static boolean isKnownPowerState(int mode) {
        return mode == Display.STATE_OFF || mode == Display.STATE_ON
                || mode == Display.STATE_DOZE || mode == Display.STATE_DOZE_SUSPEND
                || mode == Display.STATE_VR || mode == Display.STATE_UNKNOWN;
    }

    public static void changePower() {
        keyEvent(26, 0, 0);
    }

    public static void rotateDevice(int rotation) {
        // -1 means "turn to the other landscape", anything else is an absolute rotation.
        // WindowManagerService rejects a value outside 0..3 with an IllegalArgumentException
        // across the binder, which used to end the whole session over one bad packet.
        if (rotation != -1 && (rotation < Surface.ROTATION_0 || rotation > Surface.ROTATION_270)) {
            L.w("ignoring out of range rotation: " + rotation);
            return;
        }
        // null when the query failed: thawing is the same switch the user flips in quick
        // settings, so an unknown answer must not be read as "sensor rotation is on".
        Boolean locked = WindowManager.isRotationFrozenOrNull(displayId);
        if (rotation == -1) rotation = (getCurrentRotation(displayId) & 1) ^ 1; // 0->1, 1->0, 2->1, 3->0
        WindowManager.freezeRotation(displayId, rotation);
        if (Boolean.FALSE.equals(locked)) {
            WindowManager.thawRotation(displayId);
        } else if (locked == null) {
            L.w("rotation lock state unknown, left unchanged");
        }
    }

    private static int getCurrentRotation(int displayId) {
        if (displayId == 0) {
            int currentRotation = WindowManager.getRotation();
            if (currentRotation != -1) return currentRotation;
        }
        DisplayInfo displayInfo = DisplayManager.getDisplayInfo(displayId);
        if (displayInfo == null) return deviceRotation;
        return displayInfo.rotation;
    }

    private static void setKeepScreenLight() {
        try {
            String output = Channel.execReadOutput("settings get system screen_off_timeout");
            // 使用正则表达式匹配数字
            Matcher matcher = Pattern.compile("\\d+").matcher(output);
            if (matcher.find()) {
                int timeout = Integer.parseInt(matcher.group());
                if (timeout >= 1000 && timeout <= 1800000) {
                    oldScreenOffTimeout = timeout;
                    saveTimeoutToFile(timeout);
                } else {
                    oldScreenOffTimeout = readTimeoutFromFile();
                }
            }
            Channel.execReadOutput("settings put system screen_off_timeout 600000000");
        } catch (Exception e) {
            L.e("setKeepScreenLight error", e);
        }
    }

    private static void saveTimeoutToFile(int timeout) {
        try (FileWriter writer = new FileWriter("/data/local/tmp/connectsome_screen_off_timeout")) {
            writer.write(String.valueOf(timeout));
        } catch (IOException e) {
            L.e("saveTimeoutToFile error", e);
        }
    }

    private static int readTimeoutFromFile() {
        int timeout = 60000;
        try (BufferedReader reader = new BufferedReader(new FileReader("/data/local/tmp/connectsome_screen_off_timeout"))) {
            String line = reader.readLine();
            if (line != null) {
                int stored = Integer.parseInt(line.trim());
                // Whatever is in that file gets written back into the user's settings at
                // release. A stored value outside the plausible range is a leftover from an
                // older helper or a hand-edited file, so it is dropped for the default.
                timeout = (stored >= 1000 && stored <= 1800000) ? stored : 60000;
            }
        } catch (Exception e) {
            L.e("readTimeoutFromFile error", e);
        }
        return timeout;
    }

    public static boolean isEncoderSupport(String mimeName) {
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo mediaCodecInfo : mediaCodecList.getCodecInfos())
            if (mediaCodecInfo.isEncoder() && mediaCodecInfo.getName().contains(mimeName)) return true;
        return false;
    }
}
