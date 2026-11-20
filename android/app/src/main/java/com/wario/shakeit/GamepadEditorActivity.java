package com.wario.shakeit;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/**
 * Editor of the gamepad's buttons: for each button of the sideways Wii Remote the player picks the
 * gamepad button that presses it. It writes [gamepad] "<style>_<action>" in Config.toml, the keys the
 * runtime reads (virtual_remote.cpp, PadLayouts). Opened from the start menu's Controls page, which
 * offers it when a gamepad is connected. Empty value = the built-in button.
 */
public class GamepadEditorActivity extends Activity {

    // The runtime's pad styles (virtual_remote.cpp kPadStyleNames) and their labels.
    private static final String[] STYLES = {"xbox", "playstation", "nintendo"};
    private static final String[] STYLE_LABELS = {"Xbox", "PlayStation", "Nintendo"};

    // The runtime's action keys (virtual_remote.cpp kPadActionKeys), in the order shown.
    private static final String[] ACTIONS = {
        "up", "down", "left", "right", "jump", "run", "a", "shake", "tilt_left", "tilt_right", "plus", "minus",
    };
    // {Spanish, English} label per action.
    private static final String[][] LABELS = {
        {"Cruceta arriba", "D-pad up"},
        {"Cruceta abajo", "D-pad down"},
        {"Cruceta izquierda", "D-pad left"},
        {"Cruceta derecha", "D-pad right"},
        {"Botón 2 (saltar)", "Button 2 (jump)"},
        {"Botón 1 (correr)", "Button 1 (run)"},
        {"Botón A", "Button A"},
        {"Sacudir", "Shake"},
        {"Inclinar a la izquierda", "Tilt left"},
        {"Inclinar a la derecha", "Tilt right"},
        {"+", "+"},
        {"-", "-"},
    };

    // Colors of the start menu (LauncherActivity).
    private static final int BACK = 0xFF180E22;
    private static final int GOLD = 0xFFF6C426;
    private static final int PURPLE = 0xFF7634AA;
    private static final int TEXT = 0xFFF0EAF8;
    private static final int MUTED = 0xFFA092B6;
    private static final int GOOD = 0xFF78D678;

    private File config;
    private int styleIndex;
    // The action waiting for a gamepad button, or null.
    private String waiting;
    private TextView status;
    private TextView styleButton;
    private final TextView[] values = new TextView[ACTIONS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = SetupActivity.configFile(this);
        styleIndex = styleFor(firstGamepad());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACK);
        root.setPadding(dp(24), dp(16), dp(24), dp(16));

        root.addView(text(LauncherActivity.t("Botones del mando", "Gamepad buttons"), 22, TEXT, true));
        status = text("", 13, MUTED, false);
        root.addView(status, wrap(0, dp(4), 0, dp(8)));

        // Style of the gamepad's own layout, so each family of pads keeps its own buttons.
        LinearLayout styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        styleRow.setGravity(Gravity.CENTER_VERTICAL);
        styleRow.addView(text(LauncherActivity.t("Tipo de mando", "Gamepad type"), 14, TEXT, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        styleButton = flat(STYLE_LABELS[styleIndex]);
        styleButton.setOnClickListener(v -> {
            styleIndex = (styleIndex + 1) % STYLES.length;
            styleButton.setText(STYLE_LABELS[styleIndex]);
            waiting = null;
            refresh();
        });
        styleRow.addView(styleButton, new LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(styleRow, wrap(0, 0, 0, dp(8)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < ACTIONS.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            row.addView(text(LauncherActivity.t(LABELS[i][0], LABELS[i][1]), 14, TEXT, false),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            values[i] = text("", 13, GOLD, true);
            values[i].setMinWidth(dp(110));
            values[i].setGravity(Gravity.CENTER);
            row.addView(values[i], new LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView change = flat(LauncherActivity.t("Cambiar", "Change"));
            change.setOnClickListener(v -> startWaiting(ACTIONS[index]));
            row.addView(change, new LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView reset = flat(LauncherActivity.t("Por defecto", "Default"));
            reset.setOnClickListener(v -> assign(ACTIONS[index], ""));
            row.addView(reset, new LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));
            list.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        root.addView(text(LauncherActivity.t(
                "Pulsa «Cambiar» y luego el botón del mando. Atrás cancela. Los cambios se usan al jugar.",
                "Press «Change», then the gamepad button. Back cancels. Changes apply when you play."),
                12, MUTED, false), wrap(0, dp(8), 0, 0));

        setContentView(root);
        refresh();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (waiting != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && isGamepadSource(event.getSource())) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                waiting = null;
                refresh();
                return true;
            }
            String name = sdlName(event.getKeyCode());
            if (name == null) {
                Toast.makeText(this, LauncherActivity.t("Ese botón no se puede usar.", "That button can't be used."),
                        Toast.LENGTH_SHORT).show();
            } else {
                assign(waiting, name);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Analog triggers (L2/R2 as axes) can be picked too.
        if (waiting != null && event.getActionMasked() == MotionEvent.ACTION_MOVE
                && isGamepadSource(event.getSource())) {
            if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.6f) {
                assign(waiting, "lefttrigger");
                return true;
            }
            if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.6f) {
                assign(waiting, "righttrigger");
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void startWaiting(String action) {
        waiting = action;
        refresh();
    }

    private void assign(String action, String name) {
        try {
            // An empty name removes the override, so the built-in button is used again.
            DataImporter.writeSetting(config, "gamepad", STYLES[styleIndex] + "_" + action,
                    "\"" + name + "\"");
        } catch (IOException e) {
            Toast.makeText(this, LauncherActivity.t("No se pudo guardar Config.toml", "Could not save Config.toml"),
                    Toast.LENGTH_SHORT).show();
        }
        waiting = null;
        refresh();
    }

    private void refresh() {
        InputDevice pad = firstGamepad();
        if (waiting != null) {
            status.setText(LauncherActivity.t("Pulsa un botón del mando…", "Press a gamepad button…"));
            status.setTextColor(GOLD);
        } else if (pad != null) {
            status.setText(LauncherActivity.t("Mando: ", "Gamepad: ") + pad.getName());
            status.setTextColor(GOOD);
        } else {
            status.setText(LauncherActivity.t("No hay ningún mando conectado.", "No gamepad connected."));
            status.setTextColor(MUTED);
        }
        for (int i = 0; i < ACTIONS.length; i++) {
            String key = STYLES[styleIndex] + "_" + ACTIONS[i];
            String value;
            if (ACTIONS[i].equals(waiting)) {
                value = "…";
            } else {
                String saved = readValue(key);
                value = saved == null || saved.isEmpty() ? LauncherActivity.t("por defecto", "default") : saved;
            }
            values[i].setText(value);
        }
    }

    private String readValue(String key) {
        try {
            String v = DataImporter.readSetting(config, "gamepad", key);
            if (v == null) {
                return null;
            }
            v = v.trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            return v;
        } catch (IOException e) {
            return null;
        }
    }

    // ---- Helpers -----------------------------------------------------------------------

    private static boolean isGamepadSource(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    /** The first connected gamepad (virtual devices ignored), or null. */
    static InputDevice firstGamepad(Context context) {
        InputManager manager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        if (manager == null) {
            return null;
        }
        for (int id : manager.getInputDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            // A gamepad or joystick only: a D-pad alone (remotes, some keys) is not a pad.
            int sources = device == null ? 0 : device.getSources();
            if (device != null && !device.isVirtual()
                    && ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)) {
                return device;
            }
        }
        return null;
    }

    private InputDevice firstGamepad() {
        return firstGamepad(this);
    }

    // The pad family from the device's name: its own layout is used when it is one of those.
    private static int styleFor(InputDevice device) {
        if (device == null) {
            return 0;
        }
        String name = device.getName() == null ? "" : device.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("dualsense") || name.contains("dualshock") || name.contains("playstation")
                || name.contains("wireless controller")) {
            return 1;
        }
        if (name.contains("switch") || name.contains("nintendo") || name.contains("pro controller")
                || name.contains("joy-con")) {
            return 2;
        }
        return 0;
    }

    /** Android key code of a gamepad button -> the SDL name the runtime reads; null if unsupported. */
    private static String sdlName(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "a";
            case KeyEvent.KEYCODE_BUTTON_B: return "b";
            case KeyEvent.KEYCODE_BUTTON_X: return "x";
            case KeyEvent.KEYCODE_BUTTON_Y: return "y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "leftshoulder";
            case KeyEvent.KEYCODE_BUTTON_R1: return "rightshoulder";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "leftstick";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "rightstick";
            case KeyEvent.KEYCODE_BUTTON_START: return "start";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "back";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "guide";
            case KeyEvent.KEYCODE_DPAD_UP: return "dpup";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "dpdown";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "dpleft";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "dpright";
            default: return null;
        }
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView flat(String value) {
        TextView view = text(value, 13, TEXT, false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PURPLE);
        bg.setCornerRadius(dp(4));
        view.setBackground(bg);
        return view;
    }

    private static LinearLayout.LayoutParams wrap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(left, top, right, bottom);
        return lp;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
