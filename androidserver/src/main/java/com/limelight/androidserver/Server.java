/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver;

import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.view.Display;
import org.json.JSONArray;
import org.json.JSONObject;
import com.limelight.androidserver.entity.DisplayInfo;
import com.limelight.androidserver.utils.L;
import com.limelight.androidserver.utils.Workarounds;
import com.limelight.androidserver.wrappers.DisplayManager;
import com.limelight.androidserver.wrappers.ServiceManager;
import com.limelight.androidserver.wrappers.UiModeManager;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;

public class Server {
    Channel channel;
    public static DataOutputStream outputStream;

    public static void main(String... args) throws Exception {
        L.logMode = 2;
        outputStream = new DataOutputStream(System.out);
        Workarounds.apply(0);
        ServiceManager.setManagers();
        new Server();
        while (true) {
            Thread.sleep(1000);
        }
    }

    public Server() {
        inputHandler();
        this.channel = new Channel();
    }

    private void inputHandler() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Scanner scanner = new Scanner(System.in);
                while (true) {
                    String input;
                    try {
                        // nextLine() throws once the host closes the pipe. That has to end
                        // the loop: the exception is not recoverable and retrying turns the
                        // thread into a spin that keeps the process - and its shell session -
                        // alive forever.
                        input = scanner.nextLine();
                    } catch (java.util.NoSuchElementException e) {
                        L.d("input stream closed, exiting");
                        System.exit(0);
                        return;
                    } catch (Exception e) {
                        L.e("consoleInputHandler error", e);
                        continue;
                    }
                    L.d("INPUT: " + input);
                    try {
                        if (input.startsWith("/exit")) System.exit(0);
                        else if (input.startsWith("/")) handleRequest(parseRequest(input));
                        else L.w("Unknown command: " + input);
                    } catch (Exception e) {
                        L.e("consoleInputHandler error", e);
                    }
                }
            }
        }).start();
    }

    private void postResponse(String response) {
        try {
            if (response == null) response = "null";
            L.d("RESPONSE: " + response);
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            outputStream.writeInt(responseBytes.length);
            outputStream.writeInt(responseBytes.length);
            outputStream.write(responseBytes);
        } catch (Exception e) {
            L.e("postResponse error", e);
        }
    }

    private static HashMap<String, String> parseRequest(String input) {
        HashMap<String, String> request = new HashMap<>();

        String[] parts = input.split("\\?");
        String path = parts[0];
        request.put("request", path);
        if (parts.length == 1) return request;

        String params = parts[1];
        String[] keyValuePairs = params.split("&");
        for (String pair : keyValuePairs) {
            // Only the first separator divides key from value: a shell command passed as
            // `cmd=` legitimately contains `=` of its own, and demanding exactly two pieces
            // would silently discard the whole parameter.
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) continue;
            request.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return request;
    }

    Map<Integer, VirtualDisplay> cache = new HashMap<>();

    /**
     * A display geometry that is a plausible screen.
     *
     * These numbers reach a real display: {@code wm size} rewrites the geometry of the
     * built-in screen of the device this helper is running on, and
     * {@code VirtualDisplay.resize} allocates a surface of the requested size. A zero, a
     * negative or a nine-digit value therefore either makes the device unusable until it is
     * rebooted (a portrait {@code wm size 0x0} cannot be corrected from a screen that is no
     * longer drawn) or asks the graphics allocator for more than the phone has. The upper
     * bound is 4x the longest edge of any current phone; nothing legitimate is above it.
     */
    private static final int MIN_DIMENSION = 100;
    private static final int MAX_DIMENSION = 16384;
    private static final int MIN_DENSITY = 40;
    private static final int MAX_DENSITY = 1000;

    /** Parse a request parameter as an integer inside the only range that is safe to act on. */
    private static int parseBounded(String value, int min, int max, String name) throws Exception {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new Exception("'" + value + "' is not a number for parameter '" + name + "'");
        }
        if (parsed < min || parsed > max)
            throw new Exception("parameter '" + name + "' must be between " + min + " and " + max + ", got " + parsed);
        return parsed;
    }

    private void handleRequest(HashMap<String, String> request) {
        try {
            switch (Objects.requireNonNull(request.get("request"))) {
                case "/getPhoneInfo": {
                    postResponse(Channel.getPhoneInfo().toString());
                    break;
                }
                case "/getRecentTasks": {
                    String line1 = request.get("maxNum");
                    String line2 = request.get("flags");
                    String line3 = request.get("userId");

                    int maxNum = 25;
                    if (line1 != null) maxNum = Integer.parseInt(line1);
                    // The service will happily build a list of whatever length is asked for.
                    maxNum = Math.max(Math.min(maxNum, 200), 1);
                    int flags = 0;
                    if (line2 != null) flags = Integer.parseInt(line2);
                    int userId = 0;
                    if (line3 != null) userId = Integer.parseInt(line3);

                    postResponse(channel.getRecentTasksJson(maxNum, flags, userId).toString());
                    break;
                }
                case "/getIcon": {
                    String packageName = request.get("package");
                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    postResponse(channel.Bitmap2file(packageName));
                    break;
                }
                case "/getAllAppInfo": {
                    String line = request.get("app_type");
                    if (line == null) throw new Exception("parameter 'app_type' not found");
                    // 0 = every app, 1 = user apps only, anything else = system apps only.
                    // Anything outside that set is refused rather than silently treated as
                    // "system", which is a different answer than the caller asked for.
                    int appType = parseBounded(line, 0, 2, "app_type");
                    postResponse(channel.getAllAppInfo(appType));
                    break;
                }
                case "/getAppDetail": {
                    String packageName = request.get("package");
                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    postResponse(channel.getAppDetail(packageName));
                    break;
                }
                case "/getAppMainActivity": {
                    String packageName = request.get("package");
                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    postResponse(channel.getAppMainActivity(packageName));
                    break;
                }
                case "/createVirtualDisplay": {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                        throw new Exception("Virtual display is not supported before Android 11");
                    String line1 = request.get("width");
                    String line2 = request.get("height");
                    String line3 = request.get("density");
                    if (line1 != null && line2 == null)
                        throw new Exception("parameter 'width' found, but 'height' not found");
                    if (line1 == null && line2 != null)
                        throw new Exception("parameter 'height' found, but 'width' not found");

                    DisplayInfo defaultDisplay = DisplayManager.getDisplayInfo(Display.DEFAULT_DISPLAY);
                    if (defaultDisplay == null || defaultDisplay.size == null)
                        throw new Exception("cannot read the geometry of the built-in display");
                    int width, height, density;
                    if (line1 != null) {
                        width = parseBounded(line1, MIN_DIMENSION, MAX_DIMENSION, "width");
                        height = parseBounded(line2, MIN_DIMENSION, MAX_DIMENSION, "height");
                    } else {
                        int rotation = defaultDisplay.rotation;
                        if (rotation == 1 || rotation == 3) {
                            width = defaultDisplay.size.second;
                            height = defaultDisplay.size.first;
                        } else {
                            width = defaultDisplay.size.first;
                            height = defaultDisplay.size.second;
                        }
                    }
                    if (line3 != null) density = parseBounded(line3, MIN_DENSITY, MAX_DENSITY, "density");
                    else density = defaultDisplay.density;

                    VirtualDisplay display = channel.createVirtualDisplay(width, height, density);
                    if (display == null) throw new Exception("Failed to create virtual display");
                    int createdDisplayId = display.getDisplay().getDisplayId();
                    cache.put(createdDisplayId, display);
                    int[] displayIds = DisplayManager.getDisplayIds();
                    for (int displayId : displayIds) {
                        L.d(">>>display -> " + displayId);
                    }
                    postResponse("success create display, id -> " + createdDisplayId);
                    break;
                }
                case "/resizeDisplay": {
                    String line1 = request.get("id");
                    String line2 = request.get("width");
                    String line3 = request.get("height");
                    String line4 = request.get("density");
                    int id = 0;
                    if (line1 != null) id = parseBounded(line1, 0, 100, "id");
                    DisplayInfo display = DisplayManager.getDisplayInfo(id);
                    if (display == null) throw new Exception("specified display not found");

                    if (line2 == null && line3 == null && line4 == null)
                        throw new Exception("please give parameter 'width'&'height' or 'density'");
                    if (line2 != null && line3 == null)
                        throw new Exception("parameter 'width' found, but 'height' not found");
                    if (line2 == null && line3 != null)
                        throw new Exception("parameter 'height' found, but 'width' not found");

                    int width, height, density;
                    if (line2 != null) {
                        width = parseBounded(line2, MIN_DIMENSION, MAX_DIMENSION, "width");
                        height = parseBounded(line3, MIN_DIMENSION, MAX_DIMENSION, "height");
                    } else {
                        width = display.size.first;
                        height = display.size.second;
                    }
                    if (line4 != null) density = parseBounded(line4, MIN_DENSITY, MAX_DENSITY, "density");
                    else density = display.density;

                    if (id == 0) {
                        if (line2 != null) Channel.execReadOutput("wm size " + width + "x" + height);
                        if (line4 != null) Channel.execReadOutput("wm density " + density);
                    } else {
                        VirtualDisplay virtualDisplay = cache.get(id);
                        if (virtualDisplay == null)
                            throw new Exception("specified virtual display not found, it might not be created by this server");
                        virtualDisplay.resize(width, height, density);
                    }
                    postResponse("success resize display, id -> " + id);
                    break;
                }
                case "/releaseVirtualDisplay": {
                    String id = request.get("id");
                    if (id == null) throw new Exception("parameter 'id' not found");
                    int displayId = parseBounded(id, 0, 100, "id");
                    VirtualDisplay display = cache.get(displayId);
                    if (display == null)
                        throw new Exception("specified virtual display not found, it might not be created by this server");
                    JSONObject tasks = channel.getRecentTasksJson(25, 0, 0);
                    JSONArray tasks_data = tasks.getJSONArray("data");
                    for (int i = 0; i < tasks_data.length(); i++) {
                        JSONObject task = tasks_data.getJSONObject(i);
                        if (id.equals(String.valueOf(task.getInt("displayId")))) {
                            try {
                                Channel.execReadOutput("am display move-stack " + task.getInt("id") + " 0");
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    display.release();
                    cache.remove(displayId);
                    postResponse("success release display, id -> " + id);
                    break;
                }
                case "/openAppByPackage": {
                    String packageName = request.get("package");
                    String activity = request.get("activity");
                    String id = request.get("displayId");

                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    if (activity == null) activity = channel.getAppMainActivity(packageName);
                    if (id == null) id = "0";

                    String error = channel.openApp(packageName, activity, parseBounded(id, 0, 100, "displayId"));
                    if (error != null) throw new Exception(error);
                    postResponse("success");
                    break;
                }
                case "/stopAppByPackage": {
                    String packageName = request.get("package");
                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    if (!Channel.isSafePackageName(packageName)) throw new Exception("invalid package name");
                    String cmd = "am force-stop " + packageName;
                    L.d("stopActivity activity cmd: " + cmd);
                    Channel.execReadOutput(cmd);
                    postResponse("success");
                    break;
                }
                case "/getDisplayInfo": {
                    int[] displayIds = DisplayManager.getDisplayIds();
                    JSONArray jsonArray = new JSONArray();
                    for (int displayId : displayIds) {
                        DisplayInfo display = DisplayManager.getDisplayInfo(displayId);
                        if (display == null) continue;
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("id", displayId);
                        jsonObject.put("width", display.size.first);
                        jsonObject.put("height", display.size.second);
                        jsonObject.put("density", display.density);
                        jsonObject.put("rotation", display.rotation);
                        jsonArray.put(jsonObject);
                    }
                    postResponse(jsonArray.toString());
                    break;
                }
                case "/runShell": {
                    String cmd = request.get("cmd");
                    if (cmd == null) throw new Exception("parameter 'cmd' not found");
                    L.d("runShell cmd: " + cmd);
                    postResponse(Channel.execReadOutput(cmd));
                    break;
                }
                case "/getNightMode": {
                    int nightMode = UiModeManager.getNightMode();
                    L.d("nightMode: " + nightMode);
                    postResponse(String.valueOf(nightMode));
                    break;
                }
                case "/setNightMode": {
                    String nightMode = request.get("nightMode");
                    if (nightMode == null) throw new Exception("parameter 'nightMode' not found");
                    // UiModeManagerService only defines a handful of night modes; anything
                    // else is either rejected by the service or lands the device in a UI
                    // mode no setting can name. Bound it to what the framework accepts
                    // instead of handing the number straight over.
                    UiModeManager.setNightMode(parseBounded(nightMode, UiModeManager.MIN_NIGHT_MODE,
                            UiModeManager.MAX_NIGHT_MODE, "nightMode"));
                    postResponse("success");
                    break;
                }
                default:
                    postResponse("Unknown request");
            }
        } catch (Exception e) {
            postResponse(e.getMessage());
            L.e("handleRequest error", e);
        }
    }
}
