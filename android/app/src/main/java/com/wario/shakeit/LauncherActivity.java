package com.wario.shakeit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Start menu of the Android port, drawn like the PC launcher (launcher/MainForm.cs, Theme.cs): the
 * same colors, side menu, nine pages with the same names and the same Config.toml keys. English by
 * default; the language button at the bottom of the side menu switches to Spanish.
 */
public class LauncherActivity extends Activity {

    // launcher/Theme.cs
    private static final int BACK = 0xFF180E22;
    private static final int SIDE = 0xFF100918;
    private static final int SIDE_HOVER = 0xFF26163A;
    private static final int FIELD = 0xFF2C1E3E;
    private static final int FIELD_BORDER = 0xFF543870;
    private static final int GOLD = 0xFFF6C426;
    private static final int PURPLE = 0xFF7634AA;
    private static final int TEXT = 0xFFF0EAF8;
    private static final int MUTED = 0xFFA092B6;
    private static final int GOOD = 0xFF78D678;
    private static final int BAD = 0xFFF06060;
    private static final int ON_GOLD = 0xFF28103C;

    private static final String PREFS = "launcher";

    static boolean english = true;

    /** Loc.T: Spanish or English, as the PC launcher does. */
    static String t(String es, String en) {
        return english ? en : es;
    }

    private interface Pick {
        void on(int index);
    }

    private LinearLayout side;
    private LinearLayout pageHost;
    private ScrollView scroll;
    private LinearLayout page;
    private TextView status;
    private TextView hint;
    private TextView play;
    private final TextView[] navText = new TextView[9];
    private final View[] navBar = new View[9];
    private int selected = 0;

    // ---- Lifecycle ---------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SetupActivity.dataDir(this).mkdirs();
        english = !"es".equals(prefs().getString("lang", "en"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACK);

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.HORIZONTAL);
        // The side menu sits in its own ScrollView: on a short screen the lower items (language, version) used to be
        // cut off with no way to reach them. fillViewport keeps the spacer that pushes them down when everything fits.
        side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(SIDE);
        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setFillViewport(true);
        sideScroll.setVerticalScrollBarEnabled(true);
        // WRAP_CONTENT, not MATCH_PARENT: a matched child is exactly the screen height and would clip its items.
        sideScroll.addView(side, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        middle.addView(sideScroll, new LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setNestedScrollingEnabled(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(32), 0, dp(32), dp(24));
        scroll.addView(page);
        middle.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        root.addView(middle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom bar, full width, as in MainForm.BuildShell: hint under the side menu, status, JUGAR.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(SIDE);
        hint = text("", 11, MUTED);
        hint.setPadding(dp(14), 0, 0, 0);
        bottom.addView(hint, new LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.MATCH_PARENT));
        status = text("", 13, MUTED);
        status.setPadding(dp(16), 0, dp(8), 0);
        bottom.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        play = text("", 15, ON_GOLD);
        play.setTypeface(Typeface.DEFAULT_BOLD);
        play.setGravity(Gravity.CENTER);
        play.setBackgroundColor(GOLD);
        play.setOnClickListener(v -> play());
        bottom.addView(play, new LinearLayout.LayoutParams(dp(230), LinearLayout.LayoutParams.MATCH_PARENT));
        root.addView(bottom, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)));

        setContentView(root);
        buildSide();
        show(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        show(selected);  // the game and the data import can change the settings or the game folder
        offerGamepadEditor();
    }

    // Asked once per launch, the first time a gamepad is seen (Bluetooth, or a handheld's built-in pad).
    private static boolean askedAboutGamepad;

    private void offerGamepadEditor() {
        if (askedAboutGamepad || GamepadEditorActivity.firstGamepad(this) == null) {
            return;
        }
        askedAboutGamepad = true;
        new AlertDialog.Builder(this)
                .setMessage(t("Se detectó un mando. ¿Quieres configurar sus botones?",
                        "A gamepad was detected. Do you want to configure its buttons?"))
                .setPositiveButton(t("Sí", "Yes"),
                        (dialog, which) -> startActivity(new Intent(this, GamepadEditorActivity.class)))
                .setNegativeButton(t("No", "No"), null)
                .show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    /** Full-screen launcher, like the game: status and navigation bars hidden until swiped. */
    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void play() {
        startActivity(SetupActivity.dataReady(this)
                ? new Intent(this, GameActivity.class)
                : new Intent(this, SetupActivity.class));
    }

    // ---- Side menu (MainForm.BuildShell) -------------------------------------------------

    private void buildSide() {
        side.removeAllViews();
        String[] pages = pageTitles();

        TextView logo = text("WARIO LAND\nSHAKE IT!", 20, GOLD);
        logo.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        logo.setGravity(Gravity.CENTER_VERTICAL);
        logo.setPadding(dp(16), 0, 0, 0);
        side.addView(logo, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(92)));

        for (int i = 0; i < pages.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            navBar[i] = new View(this);
            item.addView(navBar[i], new LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT));
            navText[i] = text(pages[i], 14, TEXT);
            navText[i].setPadding(dp(14), 0, 0, 0);
            item.addView(navText[i], new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            item.setOnClickListener(v -> show(index));
            side.addView(item, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        }

        View spacer = new View(this);
        side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView lang = flat(english ? "🌐  English  →  Español" : "🌐  Español  →  English");
        lang.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        lang.setGravity(Gravity.CENTER);
        lang.setOnClickListener(v -> {
            english = !english;
            prefs().edit().putString("lang", english ? "en" : "es").apply();
            buildSide();
            show(selected);
        });
        side.addView(lang, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        TextView version = text("Android port · v" + appVersion(), 11, MUTED);
        version.setGravity(Gravity.CENTER);
        side.addView(version, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));
    }

    /** The app's version as the build set it (CI: 0.1.<run number>), read from the installed package. */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private String[] pageTitles() {
        return new String[] {
            t("Inicio", "Home"), t("Pantalla", "Display"), t("Gráficos", "Graphics"),
            t("Audio", "Audio"), t("Juego", "Game"), t("Controles", "Controls"),
            t("Avanzado", "Advanced"), t("Instalación", "Install"), t("Registro", "Log"),
        };
    }

    private void show(int index) {
        selected = index;
        for (int i = 0; i < navText.length; i++) {
            boolean on = i == index;
            navText[i].setTextColor(on ? GOLD : TEXT);
            navBar[i].setBackgroundColor(on ? GOLD : Color.TRANSPARENT);
            ((View) navText[i].getParent()).setBackgroundColor(on ? SIDE_HOVER : SIDE);
        }
        hint.setText(t("En el juego: el botón ⚙ abre los\najustes de los controles táctiles.",
                       "In game: the ⚙ button opens the\ntouch control settings."));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        updateStatus();
        play.setText(t("JUGAR", "PLAY"));
        play.setOnClickListener(v -> play());

        page.removeAllViews();
        switch (index) {
            case 0: buildHome(); break;
            case 1: buildDisplay(); break;
            case 2: buildGraphics(); break;
            case 3: buildAudio(); break;
            case 4: buildGame(); break;
            case 5: buildControls(); break;
            case 6: buildAdvanced(); break;
            case 7: buildInstall(); break;
            default: buildLog(); break;
        }
        scroll.scrollTo(0, 0);
    }

    private void updateStatus() {
        int aspect = getInt("video", "aspect_mode", 1);
        String aspectText = aspect == 0 ? "4:3" : aspect == 1 ? "16:9" : aspect == 3 ? "2.39:1" : t("llenar", "fill");
        double scale = getDouble("video", "resolution_multiplier", 1.5);
        String res = scale <= 0 ? t("resolución automática", "auto resolution")
                : (int) Math.round(480 * scale) + "p";
        status.setText(res + "  ·  " + aspectText + "  ·  " + t("pantalla completa", "fullscreen")
                + "  ·  " + t("controles táctiles", "touch controls"));
    }

    // ---- Pages (MainForm.Build*) ----------------------------------------------------------

    private void buildHome() {
        FrameLayout banner = new FrameLayout(this);
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.launcher_banner);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.addView(image, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        // Dark gradient at the bottom so the subtitle reads over the picture (Banner in Theme.cs).
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00180E22, 0xE6180E22}));
        banner.addView(shade, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        TextView subtitle = text("RWLE01 · " + t("recompilación nativa para Android", "native Android recompilation")
                + "  ·  by Santiago109  ·  " + t("en pruebas", "in testing"), 12, TEXT);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        sp.setMargins(dp(0), 0, 0, dp(12));
        banner.addView(subtitle, sp);
        page.addView(banner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));
        // Hide the top of the banner's padding: the banner runs edge to edge like on PC.
        page.setPadding(dp(32), 0, dp(32), dp(24));

        section(t("Ajustes rápidos", "Quick settings"));
        row(t("Resolución interna", "Render resolution"), scalePicker());
        row(t("Relación de aspecto", "Aspect ratio"), aspectPicker());
        widescreenCheck();

        section(t("Estado", "Status"));
        statusLine();
    }

    private void buildDisplay() {
        header(t("Pantalla", "Display"), t("Cómo se ve el juego en tu pantalla.", "How the game appears on your screen."));
        section(t("Imagen", "Picture"));
        row(t("Relación de aspecto", "Aspect ratio"), aspectPicker());
        widescreenCheck();
        check(t("Sincronización vertical (VSync)", "Vertical sync (VSync)"), "video", "vsync", true,
                t("Desactivada puede haber cortes en la imagen.", "Off can show tearing."));
        section(t("Superposición", "Overlay"));
        row(t("Velocidad de imagen (FPS)", "Frame rate"), frameRatePicker());
        info(t("120 FPS interpola cuadros (experimental). El juego sigue marcando su ritmo de 60 Hz.",
               "120 FPS interpolates frames (experimental). The game keeps its 60 Hz timing."), MUTED);
        check(t("Mostrar FPS", "Show FPS"), "video", "show_fps", false, null);
        check(t("Gráfica de tiempo por cuadro", "Frame time graph"), "video", "frame_graph", false,
                t("Útil para ver tirones.", "Useful to spot stutter."));
        info(t("En Android el juego se centra en 16:9 por defecto, con bandas negras si la pantalla es más ancha.",
               "On Android the game is centered at 16:9 by default, with black bars on wider screens."), MUTED);
    }

    private void buildGraphics() {
        header(t("Gráficos", "Graphics"), t("Resolución y renderizador.", "Resolution and renderer."));
        section(t("Resolución", "Resolution"));
        row(t("Resolución interna", "Render resolution"), scalePicker());
        section(t("Compatibilidad", "Compatibility"));
        check(t("No esperar a los shaders nuevos", "Don't wait for new shaders"), "video", "skip_unready_pipelines", true,
                t("Menos tirones.", "Fewer stutters."));
        check(t("Filtro de copia original", "Original copy filter"), "video", "disable_copy_filter", false,
                t("Imagen más suave, como en la Wii.", "Softer picture, like on the Wii."));
        section(t("Texturas", "Textures"));
        check(t("Usar texturas de reemplazo", "Use replacement textures"), "video", "texture_replacements", true, null);
        section(t("Pack de texturas", "Texture pack"));
        texturePackLabel = text(texturePackSummary(), 13, MUTED);
        page.addView(texturePackLabel, wrap(0, dp(6), 0, dp(4)));
        flatButton(t("Importar pack (.zip)", "Import pack (.zip)"),
                () -> startActivity(new Intent(this, TexturePackActivity.class)));
        flatButton(t("Quitar pack", "Remove pack"), () -> {
            TexturePackActivity.remove(this);
            refreshTexturePackLabel();
        });
        info(t("Las texturas se cargan al iniciar el juego: reinícialo para ver el cambio.",
               "Textures load when the game starts: restart it to see changes."), MUTED);
    }

    private void buildAudio() {
        header(t("Audio", "Audio"), t("Volumen y mezcla.", "Volume and mixing."));
        section(t("Volumen", "Volume"));
        int initial = (int) Math.round(getDouble("audio", "volume", 1.0) * 100);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.max(0, Math.min(100, initial)));
        bar.getProgressDrawable().setColorFilter(GOLD, PorterDuff.Mode.SRC_IN);
        bar.getThumb().setColorFilter(GOLD, PorterDuff.Mode.SRC_IN);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                put("audio", "volume", String.format(java.util.Locale.US, "%.2f", s.getProgress() / 100.0));
            }
        });
        row(t("Volumen general", "Master volume"), bar);
        check(t("Silenciar", "Mute"), "audio", "muted", false, null);
        section(t("Rendimiento", "Performance"));
        check(t("Mezclar el audio en su propio hilo", "Mix audio on its own thread"), "audio", "mix_worker", true,
                t("Aprovecha otro núcleo del procesador; apágalo solo si el sonido falla.",
                  "Uses another CPU core; turn off only if sound breaks."));
    }

    private void buildGame() {
        header(t("Juego", "Game"), t("Tu copia del juego e idioma.", "Your game copy and language."));
        section(t("Carpetas", "Folders"));
        statusLine();
        flatButton(t("Cambiar datos del juego…", "Change game data…"), () -> {
            Intent setup = new Intent(this, SetupActivity.class);
            setup.putExtra(SetupActivity.EXTRA_CHOOSE, true);
            startActivity(setup);
        });

        section(t("Partidas", "Saves"));
        CheckBox backup = new CheckBox(this);
        backup.setText(t("Copiar las partidas antes de cada inicio", "Back up saves before every start"));
        backup.setTextColor(TEXT);
        backup.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        backup.setButtonTintList(ColorStateList.valueOf(GOLD));
        backup.setChecked(Saves.backupOnStart(this));
        backup.setOnCheckedChangeListener((b, on) -> Saves.setBackupOnStart(this, on));
        page.addView(backup, wrap(0, dp(8), 0, 0));
        info(t("Se guardan las últimas 10 copias. Antes de importar también se guarda una copia.",
               "The last 10 copies are kept. Before an import, a copy is made too."), MUTED);
        info(t("Copias guardadas: ", "Copies kept: ") + Saves.backupCount(this), MUTED);
        flatButton(t("Exportar partidas (.zip)…", "Export saves (.zip)…"),
                () -> startActivity(new Intent(this, SavesActivity.class)));
        flatButton(t("Importar partidas (.zip)…", "Import saves (.zip)…"), () -> {
            Intent pick = new Intent(this, SavesActivity.class);
            pick.putExtra(SavesActivity.EXTRA_IMPORT, true);
            startActivity(pick);
        });

        section(t("Idioma", "Language"));
        final String[] codes = {"en", "es", "fr"};
        String[] labels = {"English", "Español", "Français"};
        row(t("Idioma del juego", "Game language"),
                picker(labels, indexOf(codes, getString("game", "language", "en")), i ->
                        putString("game", "language", codes[i])));
    }

    private void buildControls() {
        header(t("Controles", "Controls"), t("Qué hace cada botón táctil. El juego usa un Wii Remote de lado.",
                "What each touch button does. The game uses a sideways Wii Remote."));
        section(t("Movimiento", "Motion"));
        motionCheck();
        check(t("Vibración del celular", "Phone vibration"), "controller", "rumble", true,
                t("El celular vibra cuando el juego vibra el mando.", "The phone buzzes when the game rumbles the remote."));
        section(t("Mando", "Gamepad"));
        row(t("Stick que apunta / inclina", "Stick that aims / tilts"),
                choicePicker("wario_aim_stick", new String[] {"left", "right"},
                        new String[] {t("Izquierdo", "Left"), t("Derecho", "Right")}, "left"));
        row(t("Qué hace ese stick", "What that stick does"),
                choicePicker("wario_left_stick", new String[] {"aim", "tilt"},
                        new String[] {t("Apuntar (dirección)", "Aim (direction)"), t("Solo inclinar (eje X)", "Tilt only (X axis)")},
                        "aim"));
        row(t("Stick que mueve a Wario", "Stick that moves Wario"),
                choicePicker("wario_move_stick", new String[] {"none", "left", "right"},
                        new String[] {t("Ninguno (cruceta)", "None (d-pad)"), t("Izquierdo", "Left"), t("Derecho", "Right")},
                        "dpad".equals(getString("gamepad", "wario_left_stick", "aim")) ? "left" : "right"));
        info(t("Si el mismo stick apunta y mueve, hace las dos cosas a la vez.",
               "If the same stick aims and moves, it does both at once."), MUTED);
        section(t("Botones táctiles", "Touch buttons"));
        String[] labels = {
            t("Mostrar siempre", "Always show"),
            t("Ocultar siempre", "Always hide"),
            t("Automático: ocultar con mando", "Automatic: hide with a gamepad"),
        };
        // Same order as TouchMode.SHOW, HIDE, AUTO.
        row(t("Botones en pantalla", "On-screen buttons"),
                picker(labels, TouchMode.get(this), i -> TouchMode.set(this, i)));
        statusTouch();
        info(t("Con «Automático», los botones se ocultan al conectar un mando y vuelven al desconectarlo. "
                + "En el juego, el ⚙ muestra siempre los botones para ajustarlos y oculta/muestra desde el menú.",
               "With «Automatic», the buttons hide when a gamepad connects and come back when it is removed. "
                + "In game, the ⚙ always shows the buttons so you can adjust them, and the menu can hide or show them."), MUTED);

        section(t("Mando", "Gamepad"));
        flatButton(t("Configurar los botones del mando…", "Configure the gamepad buttons…"),
                () -> startActivity(new Intent(this, GamepadEditorActivity.class)));
        info(t("Elige qué botón del mando hace cada botón del Wii Remote. Se aplica al jugar.",
               "Choose which gamepad button does each Wii Remote button. It applies when you play."), MUTED);

        section(t("Mando táctil", "Touch remote"));
        info(t("Dentro del juego, pulsa el botón ⚙ (arriba a la izquierda) para mover, cambiar de tamaño y de opacidad\n"
                + "cada botón. Los cambios se guardan solos.",
                "In game, tap the ⚙ button (top left) to move, resize and change the opacity of each button.\n"
                + "Changes are saved automatically."), TEXT);
        flatButton(t("Restaurar la disposición de los botones", "Restore the button layout"), () -> {
            getSharedPreferences("touch_layout", MODE_PRIVATE).edit().clear().apply();
            show(selected);
        });
        section(t("Botones", "Buttons"));
        info("1, 2, A, +, −  →  Wii Remote 1, 2, A, +, −", MUTED);
        info(t("Stick  →  dirección del personaje", "Stick  →  direction of the character"), MUTED);
        info(t("Arrastrar la punta amarilla  →  apuntar (se queda donde la sueltas)",
               "Drag the yellow tip  →  aim (it stays where you let go)"), MUTED);
        info(t("Sacudir o girar rápido el celular  →  sacude a Wario",
               "Shake or quickly twist the phone  →  shakes Wario"), MUTED);
        info(t("Shake  →  sacude a Wario (botón alternativo)", "Shake  →  shakes Wario (alternative button)"), MUTED);
        info(t("Skip  →  salta la intro y avanza los textos", "Skip  →  skips the intro and advances the text"), MUTED);
    }

    private void buildAdvanced() {
        header(t("Avanzado", "Advanced"), t("Opciones del lanzador, archivos y diagnóstico.",
                "Launcher options, files and diagnostics."));
        section(t("Lanzador", "Launcher"));
        final String[] codes = {"es", "en"};
        String[] labels = {"Español", "English"};
        row(t("Idioma del lanzador", "Launcher language"),
                picker(labels, english ? 1 : 0, i -> {
                    prefs().edit().putString("lang", codes[i]).apply();
                    english = "en".equals(codes[i]);
                    buildSide();
                    show(selected);
                }));

        File crash = WarioApplication.crashFile(this);
        if (crash.isFile()) {
            section(t("Último error", "Last error"));
            info(t("El juego se cerró con un error la última vez:", "The game closed with an error last time:"), BAD);
            page.addView(selectable(readText(crash)), wrap(0, dp(4), 0, dp(6)));
            flatButton(t("Borrar el error", "Clear the error"), () -> {
                crash.delete();
                show(selected);
            });
        }

        section(t("Diagnóstico", "Diagnostics"));
        info(t("Si el juego solo muestra una pantalla negra después de actualizar, reinstalar la app suele ayudar.",
               "If the game only shows a black screen after an update, reinstalling the app usually helps."), MUTED);

        section(t("Créditos", "Credits"));
        info("WarioRecomp — " + t("port nativo de Wario Land: Shake It! (RWLE01), por ", "native port of Wario Land: Shake It! (RWLE01), by ")
                + "Santiago109", GOLD);
        info(t("Versión en pruebas: puede haber fallos, cuelgues o partes que no funcionen.",
               "Testing build: expect bugs, crashes or parts that don't work yet."), MUTED);
        info(t("Proyecto de fans no oficial; no incluye el juego.", "Unofficial fan project; the game is not included."), MUTED);
        info("Dreaminerryday — " + t("fan art de Wario del encabezado y el icono", "Wario fan art for the header and the icon"), MUTED);
    }

    private void buildInstall() {
        header(t("Instalación", "Install"), t("Importa los datos del juego desde un zip con la carpeta DATA.",
                "Import the game data from a zip with the DATA folder."));
        section(t("Datos del juego", "Game data"));
        statusLine();
        flatButton(t("Elegir zip de datos", "Choose data zip"), () -> {
            Intent setup = new Intent(this, SetupActivity.class);
            setup.putExtra(SetupActivity.EXTRA_CHOOSE, true);
            startActivity(setup);
        });
        section(t("Qué necesitas", "What you need"));
        info(t("Un zip con sys/main.dol, sys/boot.bin, sys/fst.bin y files/ de tu disco (US, RWLE01, revisión 0).",
               "A zip with sys/main.dol, sys/boot.bin, sys/fst.bin and files/ from your disc (US, RWLE01, revision 0)."), MUTED);
    }

    private void buildLog() {
        header(t("Registro", "Log"), t("Último registro del juego, para pegarlo en un issue.",
                "The latest game log, ready to paste into an issue."));
        File newest = newestLog();
        if (newest == null) {
            info(t("Todavía no hay registros: se crean al jugar.", "No logs yet: they are made when you play."), MUTED);
        } else {
            info(newest.getName(), MUTED);
            String log = readText(newest);
            if (log.length() > 60000) {
                log = "…\n" + log.substring(log.length() - 60000);
            }
            TextView box = selectable(log);
            box.setTypeface(Typeface.MONOSPACE);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            box.setBackgroundColor(0xFF0C0812);
            box.setPadding(dp(12), dp(12), dp(12), dp(12));
            page.addView(box, wrap(0, dp(8), 0, 0));
        }
        flatButton(t("Actualizar", "Refresh"), () -> show(selected));
    }

    // ---- Shared controls (Page.cs) -----------------------------------------------------

    private void header(String title, String subtitle) {
        TextView t = text(title.toUpperCase(java.util.Locale.ROOT), 22, TEXT);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        page.addView(t, wrap(0, dp(28), 0, dp(2)));
        page.addView(text(subtitle, 13, MUTED), wrap(0, 0, 0, dp(8)));
    }

    private void section(String name) {
        TextView label = text(name.toUpperCase(java.util.Locale.ROOT), 13, GOLD);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(label, wrap(0, dp(22), 0, dp(6)));
        View line = new View(this);
        line.setBackgroundColor(FIELD_BORDER);
        page.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void row(String label, View control) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, dp(6));
        line.addView(text(label, 14, TEXT), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(control, new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void check(String label, final String section, final String key, boolean def, String desc) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(TEXT);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.setButtonTintList(ColorStateList.valueOf(GOLD));
        box.setChecked(getBool(section, key, def));
        box.setOnCheckedChangeListener((b, checked) -> put(section, key, String.valueOf(checked)));
        page.addView(box, wrap(0, dp(8), 0, 0));
        if (desc != null) {
            page.addView(text(desc, 12, MUTED), wrap(dp(34), 0, 0, dp(4)));
        }
    }

    private void info(String value, int color) {
        page.addView(text(value, 13, color), wrap(0, dp(6), 0, dp(4)));
    }

    private void statusLine() {
        boolean ok = SetupActivity.dataReady(this);
        info((ok ? "✔ " + t("Juego encontrado", "Game found") : "✖ " + t("No hay datos del juego: elige el zip en Instalación.",
                "No game data: choose the zip on the Install page.")), ok ? GOOD : BAD);
    }

    private void statusTouch() {
        boolean pad = TouchMode.gamepadConnected(this);
        info(t("Mando conectado ahora: ", "Gamepad connected now: ") + (pad ? t("sí", "yes") : t("no", "no")),
                pad ? GOOD : MUTED);
    }

    // Girar el celular (gamepad.wario_motion): on/off, como el sensor de un mando.
    private void motionCheck() {
        CheckBox box = new CheckBox(this);
        box.setText(t("Movimiento del celular (inclinar como el Wii Remote)", "Phone motion (tilt like a Wii Remote)"));
        box.setTextColor(TEXT);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.setButtonTintList(ColorStateList.valueOf(GOLD));
        box.setChecked(!"off".equals(getString("gamepad", "wario_motion", "on")));
        box.setOnCheckedChangeListener((b, checked) -> put("gamepad", "wario_motion", checked ? "\"on\"" : "\"off\""));
        page.addView(box, wrap(0, dp(8), 0, 0));
        page.addView(text(t("Girar el teléfono mueve la flecha del cañón, igual que la punta de apuntar.",
                "Turning the phone moves the cannon arrow, like the aim tip."), 12, MUTED), wrap(dp(34), 0, 0, dp(4)));
        row(t("Alcance del giro", "Turn range"), rangePicker());
    }

    // A gamepad option: one of the values in Config.toml's [gamepad], with its label shown.
    private View choicePicker(final String key, final String[] values, String[] labels, String def) {
        String current = getString("gamepad", key, def);
        int index = 0;
        for (int i = 0; i < values.length; ++i) {
            if (values[i].equals(current)) index = i;
        }
        return picker(labels, index, i -> put("gamepad", key, "\"" + values[i] + "\""));
    }

    // Degrees of phone turn that take the arrow to its full 85 degrees: more is slower. [gamepad] wario_motion_range.
    private View rangePicker() {
        final int[] degs = {90, 120, 160, 200};
        String[] labels = {t("90° (rápido)", "90° (fast)"), "120°", "160°", "200°"};
        int current = 0;
        try {
            int saved = Integer.parseInt(getString("gamepad", "wario_motion_range", "90"));
            for (int i = 0; i < degs.length; ++i) {
                if (degs[i] == saved) current = i;
            }
        } catch (NumberFormatException e) {
            current = 0;
        }
        return picker(labels, current, i -> put("gamepad", "wario_motion_range", "\"" + degs[i] + "\""));
    }

    private void widescreenCheck() {
        check("True 16:9 in levels (experimental)",
                // Same default as the runtime on Android (WarioFullWide): on unless the player turns it off.
                "video", "full_widescreen", true,
                "Removes the side panels so the level fills the whole screen. Experimental: parts of the sides may be empty. Applies when the game starts.");
    }

    private View aspectPicker() {
        // 0 = 4:3, 1 = 16:9 (Android default, centered with black bars), 2 = Fill, 3 = Cinematic. Same as PC aspect_mode.
        String[] labels = {
            t("4:3 (original)", "4:3 (original)"), "16:9",
            t("Llenar la ventana", "Fill the window"), t("Cinemático 2.39:1", "Cinematic 2.39:1"),
        };
        return picker(labels, clampIndex(getInt("video", "aspect_mode", 1), labels.length), i -> {
            put("video", "aspect_mode", String.valueOf(i));
            put("video", "widescreen", String.valueOf(i != 0));
            updateStatus();
        });
    }

    private View frameRatePicker() {
        // 0 = 60 FPS (the game's own rate), 120 = frame interpolation. Written to
        // [video] frame_interpolation_fps; the runtime reads it when the game starts.
        final int[] values = {0, 120};
        String[] labels = {t("60 FPS (original)", "60 FPS (original)"), "120 FPS"};
        int current = getInt("video", "frame_interpolation_fps", 0);
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                index = i;
            }
        }
        return picker(labels, index, i -> {
            put("video", "frame_interpolation_fps", String.valueOf(values[i]));
            updateStatus();
        });
    }

    private View scalePicker() {
        // 0 = automatic (follows the screen, Android default), others: 480 lines per 1x like the PC.
        final double[] values = {0.0, 1.0, 1.5, 2.25, 3.0};
        String[] labels = {
            t("Automática (según la ventana)", "Automatic (follows the window)"),
            t("480p (original)", "480p (original)"), "720p", "1080p (Full HD)", "1440p (2K)",
        };
        double current = getDouble("video", "resolution_multiplier", 1.5);
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - current) < 0.001) {
                index = i;
            }
        }
        return picker(labels, index, i -> {
            put("video", "resolution_multiplier", String.valueOf(values[i]));
            updateStatus();
        });
    }

    private TextView picker(final String[] labels, int current, final Pick pick) {
        final TextView box = flat(labels[clampIndex(current, labels.length)]);
        box.setBackgroundColor(FIELD);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        box.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(labels, (dialog, which) -> {
                    box.setText(labels[which]);
                    pick.on(which);
                })
                .show());
        return box;
    }

    private void flatButton(String label, Runnable action) {
        TextView button = flat(label);
        button.setOnClickListener(v -> action.run());
        page.addView(button, wrap(0, dp(10), 0, 0));
    }

    // ---- Config.toml (same file and keys as the PC launcher, edited through DataImporter) --

    private TextView texturePackLabel;

    private void refreshTexturePackLabel() {
        if (texturePackLabel != null) {
            texturePackLabel.setText(texturePackSummary());
        }
    }

    private String texturePackSummary() {
        int count = TexturePackActivity.installedCount(this);
        return count == 0 ? t("Ningún pack instalado.", "No pack installed.")
                : count + " " + t("texturas de reemplazo instaladas.", "replacement textures installed.");
    }

    private File config() {
        return SetupActivity.configFile(this);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String raw(String section, String key) {
        try {
            return DataImporter.readSetting(config(), section, key);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean getBool(String section, String key, boolean def) {
        String v = raw(section, key);
        return v == null ? def : v.equals("true");
    }

    private int getInt(String section, String key, int def) {
        try {
            return Integer.parseInt(raw(section, key));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private double getDouble(String section, String key, double def) {
        try {
            return Double.parseDouble(raw(section, key));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private String getString(String section, String key, String def) {
        String v = raw(section, key);
        if (v == null) {
            return def;
        }
        v = v.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private void put(String section, String key, String rawValue) {
        try {
            SetupActivity.dataDir(this).mkdirs();
            DataImporter.writeSetting(config(), section, key, rawValue);
        } catch (IOException e) {
            android.widget.Toast.makeText(this,
                    t("No se pudo guardar Config.toml", "Could not save Config.toml"),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void putString(String section, String key, String value) {
        put(section, key, "\"" + value + "\"");
    }

    // ---- Small helpers -----------------------------------------------------------------

    private File newestLog() {
        File[] runs = new File(SetupActivity.dataDir(this), "Logs").listFiles();
        File newest = null;
        if (runs != null) {
            for (File f : runs) {
                if (f.isFile() && (newest == null || f.lastModified() > newest.lastModified())) {
                    newest = f;
                }
            }
        }
        return newest;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private static int clampIndex(int index, int count) {
        return Math.max(0, Math.min(count - 1, index));
    }

    private static String readText(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return t("(no se pudo leer el archivo)", "(could not read the file)");
        }
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private TextView selectable(String value) {
        TextView view = text(value, 11, 0xFFFFAAAA);
        view.setTextIsSelectable(true);
        return view;
    }

    private TextView flat(String value) {
        TextView view = text(value, 14, TEXT);
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setBackgroundColor(PURPLE);
        return view;
    }

    private LinearLayout.LayoutParams wrap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(left, top, right, bottom);
        return lp;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
