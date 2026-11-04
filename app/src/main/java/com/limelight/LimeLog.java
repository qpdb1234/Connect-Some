package com.limelight;

import com.limelight.util.Log;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

public class LimeLog {
    private static final Logger LOGGER = Logger.getLogger(LimeLog.class.getName());

    // Every method defers to the recording switch (see AppLogging), so an install with
    // log recording off produces no logcat lines from here either.
    public static void info(String msg) {
        if (!Log.getRecordingEnabled()) return;
        LOGGER.info(msg);
    }
    
    public static void warning(String msg) {
        if (!Log.getRecordingEnabled()) return;
        LOGGER.warning(msg);
    }
    
    public static void severe(String msg) {
        if (!Log.getRecordingEnabled()) return;
        LOGGER.severe(msg);
    }
    
    public static void setFileHandler(String fileName) throws IOException {
        LOGGER.addHandler(new FileHandler(fileName));
    }
}
