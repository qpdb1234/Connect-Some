package org.cgutman.shieldcontrollerextensions;

import android.content.Context;
import android.view.InputDevice;

/**
 * Stub for ShieldControllerExtensions.
 * On non-Shield devices, all methods return UNKNOWN/null.
 */
public class SceManager {
    public SceManager(Context context) {}

    public void start() {}
    public void stop() {}

    public boolean isRecognizedDevice(InputDevice device) { return false; }

    public int getBatteryPercentage(InputDevice device) { return -1; }

    public boolean rumble(InputDevice device, short lowFreq, short highFreq) { return false; }

    public SceConnectionType getConnectionType(InputDevice device) {
        return SceConnectionType.UNKNOWN;
    }

    public SceChargingState getChargingState(InputDevice device) {
        return SceChargingState.UNKNOWN;
    }
}
