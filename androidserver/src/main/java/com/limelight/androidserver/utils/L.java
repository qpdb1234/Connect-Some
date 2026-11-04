/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver.utils;

import android.text.format.DateFormat;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class L {

    public static void i(String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("INFO: ");
        sb.append("<").append(DateFormat.format("HH:mm:ss", new java.util.Date())).append("> ");
        sb.append(message);
        Log.i("ConnectSomeServer", message);
        flush(sb);
    }

    public static void d(String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("DEBUG: ");
        sb.append("<").append(DateFormat.format("HH:mm:ss", new java.util.Date())).append("> ");
        sb.append(message);
        Log.d("ConnectSomeServer", message);
        flush(sb);
    }

    public static void w(String message, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("WARN: ");
        sb.append("<").append(DateFormat.format("HH:mm:ss", new java.util.Date())).append("> ");
        sb.append(message);
        if (throwable != null) {
            sb.append(": ").append(Log.getStackTraceString(throwable));
        }
        Log.w("ConnectSomeServer", message, throwable);
        flush(sb);
    }

    public static void w(String message) {
        w(message, null);
    }

    public static void w(Throwable throwable) {
        w("", throwable);
    }

    public static void e(String message, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR: ");
        sb.append("<").append(DateFormat.format("HH:mm:ss", new java.util.Date())).append("> ");
        sb.append(message);
        if (throwable != null) {
            sb.append(": ").append(Log.getStackTraceString(throwable));
        }
        Log.e("ConnectSomeServer", message, throwable);
        flush(sb);
    }

    public static void e(String message) {
        e(message, null);
    }

    public static void e(Throwable throwable) {
        e("", throwable);
    }

    public static int logMode = 0;

    private static PrintStream fileOut;

    public static void flush(StringBuilder sb) {
        if (logMode != 2) {
            System.out.println(sb);
            System.out.flush();
        }

        if (logMode == 1) return;
        try {
            if (fileOut == null) {
                fileOut = new PrintStream(new FileOutputStream("/data/local/tmp/connectsome_log", false));
            }
            fileOut.println(sb);
            // A PrintStream swallows every IOException, so without this check a full disk or a
            // deleted log file leaves the stream open but deaf: the rest of the run writes into
            // nothing and there is no way to notice. Dropping it forces a fresh attempt.
            if (fileOut.checkError()) {
                try {
                    fileOut.close();
                } catch (Exception ignored) {
                }
                fileOut = null;
            }
        } catch (Exception ignored) {
        }
    }

    public static void postLog() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        Thread t = new Thread(() -> {
            RandomAccessFile raf = null;
            try {
                File file = new File("/data/local/tmp/connectsome_log");
                long lastLength = 0;
                while (true) {
                    long fileLength = file.length();
                    // The log is recreated (not appended) whenever a process opens it, so a
                    // truncated file means we are looking at a new run and must not keep
                    // waiting for the old, larger offset to be reached again.
                    if (fileLength < lastLength) lastLength = 0;
                    if (fileLength > lastLength) {
                        if (raf == null) raf = new RandomAccessFile(file, "r");
                        raf.seek(lastLength);
                        byte[] pending = new byte[(int) (fileLength - lastLength)];
                        raf.readFully(pending);
                        // Only hand out complete lines. Printing a half written line and then
                        // advancing past it would show the tail of it as a separate entry.
                        int lastNewline = -1;
                        for (int i = pending.length - 1; i >= 0; i--) {
                            if (pending[i] == '\n') {
                                lastNewline = i;
                                break;
                            }
                        }
                        if (lastNewline >= 0) {
                            String chunk = new String(pending, 0, lastNewline + 1, "UTF-8");
                            for (String line : chunk.split("\n")) {
                                System.out.println(line);
                            }
                            System.out.flush();
                            lastLength += lastNewline + 1;
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                // Ending the log relay is a legitimate request to stop; nothing to report.
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                L.e("postLog error", e);
            } finally {
                if (raf != null) {
                    try {
                        raf.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
        // Must not keep the JVM alive on its own: the process ends through release(), and a
        // non-daemon tail thread would hold it open if the shutdown path ever missed exit().
        t.setDaemon(true);
        t.start();
    }
}