package com.limelight.ui;

/**
 * Callback interface used by the Moonlight input layer ({@code ControllerHandler})
 * to request UI-level gesture actions from the host activity.
 *
 * Restored as part of the Moonlight protocol-layer integration: the copied
 * {@link com.limelight.binding.input.ControllerHandler} depends on this interface.
 */
public interface GameGestures {
    void toggleKeyboard();
}
