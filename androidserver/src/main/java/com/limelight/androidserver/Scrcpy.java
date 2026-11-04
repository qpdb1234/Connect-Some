/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Credentials;
import android.system.ErrnoException;
import android.system.Os;
import android.view.Display;
import com.limelight.androidserver.entity.Device;
import com.limelight.androidserver.entity.Options;
import com.limelight.androidserver.helper.AudioEncode;
import com.limelight.androidserver.helper.ControlPacket;
import com.limelight.androidserver.helper.VideoEncode;
import com.limelight.androidserver.utils.L;
import com.limelight.androidserver.utils.Workarounds;
import com.limelight.androidserver.wrappers.ServiceManager;
import com.limelight.androidserver.wrappers.UiModeManager;

import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Scrcpy {
    private static final Object object = new Object();
    private static final int timeoutDelay = 5 * 1000;

    // Set once the session is fully up, before the startup watchdog is interrupted. Without it
    // a slow device (a codec that takes its time, a display query that stalls) reaches the five
    // second deadline while the main thread is still on its way out of VideoEncode.init(), and
    // the watchdog ends a session that was in fact being served.
    private static volatile boolean startupFinished = false;

    public static void main(String... args) {
        L.logMode = 1;
        L.postLog();
        try {
            Thread timeOutThread = new Thread(() -> {
                try {
                    Thread.sleep(timeoutDelay);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (startupFinished) return;
                L.w("the controller did not attach in time, giving up");
                release();
            });
            timeOutThread.start();
            // 解析参数
            Options.parse(args);
            // 初始化
            Workarounds.apply(1);
            ServiceManager.setManagers();
            Device.init();
            // 连接
            connectClient();
            // 初始化子服务
            boolean canAudio = AudioEncode.init();
            VideoEncode.init();
            // 启动
            ArrayList<Thread> threads = new ArrayList<>();
            threads.add(new Thread(Scrcpy::executeVideoOut));
            if (canAudio) {
                threads.add(new Thread(Scrcpy::executeAudioIn));
                threads.add(new Thread(Scrcpy::executeAudioOut));
            }
            threads.add(new Thread(Scrcpy::executeControlIn));
            for (Thread thread : threads) thread.setPriority(Thread.MAX_PRIORITY);
            for (Thread thread : threads) thread.start();
            // 程序运行
            startupFinished = true;
            timeOutThread.interrupt();
            if (Options.TurnOnScreenIfStart) {
                Device.keyEvent(224, 0, 0);
                if (Options.TurnOffScreenIfStart)
                    postDelayed(() -> Device.changeScreenPowerMode(Display.STATE_UNKNOWN), 2000);
            }
            synchronized (object) {
                object.wait();
            }
            // 终止子服务
            for (Thread thread : threads) thread.interrupt();
        } catch (Throwable e) {
            // Not just Exception: the workaround probes below run reflection during class
            // initialization and can end in an Error (NoClassDefFoundError on a ROM where a
            // hidden service moved, AssertionError from a sanity check). Letting those escape
            // would skip release(), leaving the display hacked to our size and the screen
            // timeout at whatever value we set.
            L.e("startScrcpy error", e);
        } finally {
            // 释放资源
            release();
        }
    }

    public static void postDelayed(Runnable runnable, long delayMillis) {
        new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
                runnable.run();
            } catch (InterruptedException e) {
                L.e("postDelayed error", e);
            }
        }).start();
    }

    private static LocalSocket mainSocket;
    private static FileDescriptor mainFD;
    private static LocalSocket videoSocket;
    private static FileDescriptor videoFD;
    public static DataInputStream inputStream;

    /** adbd's own identities: the daemon runs as root on some builds, as shell on others. */
    private static final int AID_ROOT = 0;
    private static final int AID_SHELL = 2000;

    private static void connectClient() throws IOException {
        try (LocalServerSocket serverSocket = new LocalServerSocket("connectsome_scrcpy")) {
            mainSocket = acceptTrustedClient(serverSocket);
            videoSocket = acceptTrustedClient(serverSocket);
            mainFD = mainSocket.getFileDescriptor();
            videoFD = videoSocket.getFileDescriptor();
            inputStream = new DataInputStream(mainSocket.getInputStream());
        }
    }

    /**
     * Accept a client only if it is the daemon that bridges the controller to this device.
     *
     * An abstract-namespace socket has no filesystem permissions: every process on the
     * device can reach it by name while we are listening, and whichever one arrives first
     * is handed the mirrored screen and a channel to push touch, key and clipboard events
     * through a process holding shell privileges - no user consent involved. The name is
     * fixed and documented in the client, so it is not a secret. Peer credentials are the
     * only thing that distinguishes adbd from a bystander app, so those are checked.
     */
    private static LocalSocket acceptTrustedClient(LocalServerSocket serverSocket) throws IOException {
        LocalSocket socket = serverSocket.accept();
        int uid;
        int pid;
        try {
            Credentials credentials = socket.getPeerCredentials();
            uid = credentials.getUid();
            pid = credentials.getPid();
        } catch (Exception e) {
            // Refusing on an unreadable peer would break the feature on an exotic ROM, but
            // accepting an unverified one is exactly the hole being closed. Credentials of
            // a connected unix socket are kernel-provided, so not being able to read them
            // means something is wrong with the peer rather than with the platform.
            L.e("cannot identify the scrcpy client, refusing it", e);
            runCatchingClose(socket);
            throw new IOException("Cannot verify scrcpy client", e);
        }

        if (uid != AID_ROOT && uid != AID_SHELL && uid != android.os.Process.myUid()) {
            L.e("refusing scrcpy client from uid " + uid + " pid " + pid);
            runCatchingClose(socket);
            throw new IOException("Untrusted scrcpy client");
        }
        return socket;
    }

    private static void runCatchingClose(LocalSocket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private static void executeVideoOut() {
        try {
            int frame = 0;
            while (!Thread.interrupted()) {
                if (VideoEncode.isHasChangeConfig) {
                    VideoEncode.isHasChangeConfig = false;
                    VideoEncode.stopEncode();
                    VideoEncode.startEncode();
                }
                VideoEncode.encodeOut();
                frame++;
                if (frame > 120) {
                    if (System.currentTimeMillis() - lastKeepAliveTime > timeoutDelay) {
                        timeoutClose = true;
                        throw new IOException("Connection disconnected");
                    }
                    frame = 0;
                }
            }
        } catch (Exception e) {
            errorClose(e);
        }
    }

    private static void executeAudioIn() {
        try {
            while (!Thread.interrupted()) AudioEncode.encodeIn();
        } catch (Exception e) {
            L.e("audio input ended", e);
        }
    }

    private static void executeAudioOut() {
        try {
            while (!Thread.interrupted()) AudioEncode.encodeOut();
        } catch (Exception e) {
            // Anything escaping here means the audio path is broken; the session can still
            // be served without sound, so only stop this thread rather than tearing down
            // the encoder process.
            L.e("audio output ended", e);
        }
    }

    // Written by the control thread, read by the video thread's watchdog; without a
    // volatile the watchdog can keep comparing against a stale copy and drop a live
    // session that is being kept alive perfectly well.
    private static volatile long lastKeepAliveTime = System.currentTimeMillis();

    private static void executeControlIn() {
        try {
            while (!Thread.interrupted()) {
                byte type = inputStream.readByte();
                switch (type) {
                    case 1:
                        ControlPacket.handleTouchEvent();
                        break;
                    case 2:
                        ControlPacket.handleKeyEvent();
                        break;
                    case 3:
                        ControlPacket.handleClipboardEvent();
                        break;
                    case 4:
                        ControlPacket.sendKeepAlive();
                        lastKeepAliveTime = System.currentTimeMillis();
                        break;
                    case 5:
                        Device.handleConfigChanged(inputStream.readInt());
                        break;
                    case 6:
                        Device.rotateDevice(inputStream.readInt());
                        break;
                    case 7:
                        Device.changeScreenPowerMode(inputStream.readByte());
                        break;
                    case 8:
                        Device.changePower();
                        break;
                    case 9:
                        if (Device.oldNightMode == -1) Device.oldNightMode = UiModeManager.getNightMode();
                        UiModeManager.setNightMode(inputStream.readByte());
                        break;
                    default:
                        // Every known packet has a fixed payload size. Continuing on an
                        // unknown type would leave those payload bytes to be read as the
                        // next header, after which the stream is permanently misaligned -
                        // the input then produces nonsense events forever. Better a clean
                        // end of the session.
                        throw new IOException("unknown control packet type: " + type);
                }
            }
        } catch (Exception e) {
            errorClose(e);
        }
    }

    /**
     * Both descriptors only exist once {@link #connectClient()} has returned, but
     * Device.init() registers its rotation and clipboard listeners before that - and
     * accept() then blocks for as long as the host needs to connect. A clipboard change
     * in that window would otherwise write to a null descriptor, and the callers only
     * handle IO errors, so the NPE would escape uncaught on a binder thread and kill the
     * whole encoder process. Writing with no client attached has nothing to deliver, so
     * these simply drop the packet.
     */
    public synchronized static void writeMain(ByteBuffer byteBuffer) throws IOException, ErrnoException {
        if (mainFD == null) return;
        while (byteBuffer.remaining() > 0) Os.write(mainFD, byteBuffer);
    }

    public synchronized static void writeVideo(ByteBuffer byteBuffer) throws IOException, ErrnoException {
        if (videoFD == null) return;
        while (byteBuffer.remaining() > 0) Os.write(videoFD, byteBuffer);
    }

    public static void errorClose(Exception e) {
        L.e("errorClose: ", e);
        synchronized (object) {
            object.notify();
        }
    }

    private static volatile boolean timeoutClose = false;

    // 释放资源
    private static final AtomicBoolean releaseStarted = new AtomicBoolean(false);

    private static void release() {
        // Reached from the startup watchdog, from the failure path of main() and from the
        // normal end of the session. Two of them running at once would repeat the parts of
        // this that change the device (restoring the screen timeout and the display size,
        // killing helper processes) while the other one is still using them, so the second
        // caller is simply turned away - the first one ends the process anyway.
        if (!releaseStarted.compareAndSet(false, true)) return;

        boolean lastScrcpy = false;
        try {
            lastScrcpy = Integer.parseInt(Channel.execReadOutput("ps -ef | grep com.limelight.androidserver.Scrcpy | grep -v grep | grep -c 'com.limelight.androidserver.Scrcpy'").replace("<!@n@!>", "")) == 1;
        } catch (Exception e) {
            L.w("get lastScrcpy error", e);
        }

        // 1
        try {
            inputStream.close();
            mainSocket.close();
            videoSocket.close();
        } catch (Exception e) {
            L.e("release error", e);
        }

        // 2
        VideoEncode.release();
        AudioEncode.release();

        // 3
        if (Device.needReset) {
            try {
                if (Device.realDeviceSize != null)
                    Channel.execReadOutput("wm size " + Device.realDeviceSize.first + "x" + Device.realDeviceSize.second);
                else
                    Channel.execReadOutput("wm size reset");
            } catch (Exception e) {
                L.e("release error", e);
            }

            try {
                if (Device.realDeviceDensity != 0)
                    Channel.execReadOutput("wm density " + Device.realDeviceDensity);
                else
                    Channel.execReadOutput("wm density reset");
            } catch (Exception e) {
                L.e("release error", e);
            }
        }
        if (lastScrcpy && Device.oldNightMode != -1 && UiModeManager.getNightMode() != Device.oldNightMode) {
            UiModeManager.setNightMode(Device.oldNightMode);
        }

        // 4
        if (Options.keepAwake) {
            try {
                Channel.execReadOutput("settings put system screen_off_timeout " + Device.oldScreenOffTimeout);
            } catch (Exception e) {
                L.e("release error", e);
            }
        }

        // 5
        try {
            if (timeoutClose || lastScrcpy) {
                if (Options.TurnOffScreenIfStop) Device.keyEvent(223, 0, 0);
                else if (Options.TurnOnScreenIfStop) Device.changeScreenPowerMode(Display.STATE_ON);
            }
        } catch (Exception e) {
            L.e("release error", e);
        }

        // 6
        if (timeoutClose) {
            try {
                Channel.execReadOutput("ps -ef | grep com.limelight.androidserver.Scrcpy | grep -v grep | grep -E \"^[a-z]+ +[0-9]+\" -o | grep -E \"[0-9]+\" -o | xargs kill -9");
            } catch (Exception e) {
                L.e("release error", e);
            }
        }

        // 7
        L.d("scrcpy release success");
        System.exit(0);
    }

}
