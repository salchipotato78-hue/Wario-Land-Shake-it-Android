package com.wario.shakeit;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;

/**
 * Whether the on-screen touch buttons are shown: always, never, or only while no gamepad is
 * connected. The choice is kept with the launcher's own settings, so the start menu and the game
 * read the same value.
 */
final class TouchMode {

    static final int SHOW = 0;
    static final int HIDE = 1;
    static final int AUTO = 2;

    private static final String PREFS = "launcher";
    private static final String KEY = "touch_mode";

    private TouchMode() {
    }

    static int get(Context context) {
        int mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, SHOW);
        return mode == HIDE || mode == AUTO ? mode : SHOW;
    }

    static void set(Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply();
    }

    /** True when the touch buttons should be drawn in the game right now. */
    static boolean shouldShow(Context context) {
        switch (get(context)) {
            case HIDE:
                return false;
            case AUTO:
                return !gamepadConnected(context);
            default:
                return true;
        }
    }

    /** True when a physical gamepad or joystick is connected (virtual devices are ignored). */
    static boolean gamepadConnected(Context context) {
        InputManager manager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        if (manager == null) {
            return false;
        }
        for (int id : manager.getInputDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }
}
