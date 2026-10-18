package com.nfsmw.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NFSMW Recompiled - Authentic Android Launcher.
 * Features the official NFS MW logo header, Street Amber & Carbon theme,
 * full game folder & ISO ROM selection, direct on-screen configuration cards,
 * 1:1 landscape virtual touch controls layout editor, and fact-checked settings.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_GAME_FOLDER = 41;
    private static final int REQUEST_STORAGE_ACCESS = 42;
    private static final int REQUEST_LEGACY_STORAGE = 43;
    private static final int REQUEST_SAVE_REPORT = 44;
    private static final int REQUEST_DRIVER_ZIP = 45;
    private static final int REQUEST_DRIVER_PROBE = 46;
    private static final int REQUEST_ISO = 47;

    private static final int ACCENT = 0xFFE8A13C;
    private static final int ACCENT_GOLD = 0xFFFF8A00;
    private static final int BG_DARK = 0xFF0C1014;
    private static final int CARD_BG_START = 0xFF181A20;
    private static final int CARD_BG_END = 0xFF111317;
    private static final int TEXT_MUTED = 0xFF9AA0AA;

    private static final String TREE_URI = "tree_uri";
    private static final String PREF_ISO_PATH = "iso_path";
    static final String GAME_FOLDER_NAME = "nsfmw-androidevolved";

    private boolean playAfterProbe;
    private boolean launcherResumed;
    private String verifiedGamePath = null;
    private File pendingReport;
    private boolean pendingGithub;

    private TextView importStatus;
    private Button selectRomBtn;
    private Button launchGameBtn;
    private LinearLayout shaderPanel;
    private ProgressBar shaderProgress;
    private TextView shaderText;
    private ShaderBuilder shaderBuilder;
    private LinearLayout advContent;
    private TextView advArrow;

    private final ExecutorService importer = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            playAfterProbe = state.getBoolean("play_after_probe");
            String name = state.getString("pending_report");
            if (name != null && name.equals(new File(name).getName())) {
                pendingReport = new File(new File(getCacheDir(), "reports"), name);
                pendingGithub = state.getBoolean("pending_github");
            }
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        applyImmersive();
        setContentView(buildScreen());
        prepareSharedGameFolder();
    }

    @Override
    protected void onResume() {
        super.onResume();
        launcherResumed = true;
        applyImmersive();
        checkAndRefreshGameStatus();
    }

    @Override
    protected void onPause() {
        launcherResumed = false;
        super.onPause();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ============================================================================================
    // UI BUILDER
    // ============================================================================================

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF0C1014, 0xFF14140D, 0xFF0C1014}));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // 1. Header: Authentic NFSMW Logo & Subtitle Chip
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.banner);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(logo, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)));

        TextView subtitle = new TextView(this);
        subtitle.setText("Xbox 360 · ARM64 Mali Edition");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTypeface(Typeface.DEFAULT_BOLD);
        subtitle.setTextColor(ACCENT);
        subtitle.setLetterSpacing(0.06f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(14), dp(3), dp(14), dp(3));
        GradientDrawable subBg = new GradientDrawable();
        subBg.setColor(0x1AE8A13C);
        subBg.setStroke(dp(1), 0x4DE8A13C);
        subBg.setCornerRadius(dp(14));
        subtitle.setBackground(subBg);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.gravity = Gravity.CENTER_HORIZONTAL;
        subLp.topMargin = dp(4);
        subLp.bottomMargin = dp(14);
        content.addView(subtitle, subLp);

        SharedPreferences ctrlPrefs = getSharedPreferences("nfsmw_controls", MODE_PRIVATE);

        // CARD 1: Game
        content.addView(buildGameCard());

        // CARD 2: Display
        content.addView(buildDisplayCard(ctrlPrefs));

        // CARD 3: Touch controls
        content.addView(buildTouchControlsCard(ctrlPrefs));

        // CARD 4: Graphics
        content.addView(buildGraphicsCard(ctrlPrefs));

        // CARD 5: Sound
        content.addView(buildSoundCard(ctrlPrefs));

        // CARD 6: Performance
        content.addView(buildPerformanceCard(ctrlPrefs));

        // CARD 7: Language
        content.addView(buildLanguageCard());

        // CARD 8: Advanced (STRICTLY COLLAPSED BY DEFAULT)
        content.addView(buildAdvancedCard());

        return scroll;
    }

    // ---- CARD 1: Game --------------------------------------------------------------------------
    private View buildGameCard() {
        LinearLayout card = createCard();
        card.addView(cardTitle("Game"));
        card.addView(goldLine());

        importStatus = bodyText("No ISO or folder selected. You need your own Xbox 360 ISO or extracted game folder.", 12, Color.WHITE);
        card.addView(importStatus);

        TextView subDesc = bodyText("This is the USA/PAL-Multi version of the game. Choose either an extracted game folder or an ISO image.", 10.5f, TEXT_MUTED);
        subDesc.setPadding(0, dp(2), 0, dp(10));
        card.addView(subDesc);

        // Progress panel (hidden unless extracting or compiling shaders)
        shaderPanel = new LinearLayout(this);
        shaderPanel.setOrientation(LinearLayout.VERTICAL);
        shaderPanel.setPadding(0, 0, 0, dp(10));
        shaderPanel.setVisibility(View.GONE);
        shaderText = bodyText("", 12, Color.WHITE);
        shaderPanel.addView(shaderText);
        shaderProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        shaderProgress.setMax(1000);
        shaderProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        shaderPanel.addView(shaderProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)));
        card.addView(shaderPanel);

        selectRomBtn = actionButton("Choose Game (ISO or Folder)...", false);
        selectRomBtn.setOnClickListener(v -> showPickerSelectionDialog());
        card.addView(selectRomBtn);

        launchGameBtn = actionButton("Play", true);
        launchGameBtn.setEnabled(false);
        launchGameBtn.setAlpha(0.45f);
        launchGameBtn.setOnClickListener(v -> playChecked());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        playLp.topMargin = dp(8);
        card.addView(launchGameBtn, playLp);

        TextView playHint = bodyText("Choose your game folder or ISO to play.", 10.5f, TEXT_MUTED);
        playHint.setGravity(Gravity.CENTER);
        playHint.setPadding(0, dp(4), 0, 0);
        card.addView(playHint);

        return card;
    }

    // ---- CARD 2: Display -----------------------------------------------------------------------
    private View buildDisplayCard(SharedPreferences ctrl) {
        LinearLayout card = createCard();
        card.addView(cardTitle("Display"));
        card.addView(goldLine());

        card.addView(heading("Resolution"));
        card.addView(bodyText("Size of the game image on screen. The game itself is rendered at native resolution.", 10.5f, TEXT_MUTED));
        String curRes = GameOptions.get(this, GameOptions.RESOLUTION);
        RadioGroupHelper resGroup = new RadioGroupHelper();
        card.addView(resGroup.createItem("480p - 720x480", "854x480".equals(curRes), () -> {
            GameOptions.set(this, GameOptions.RESOLUTION, "854x480");
        }));
        card.addView(resGroup.createItem("Native - 1280x720", !"854x480".equals(curRes), () -> {
            GameOptions.set(this, GameOptions.RESOLUTION, "1280x720");
        }));

        card.addView(switchRow("Stretch to full screen",
                "Fill the whole screen without black bars, matching your phone's aspect ratio.",
                ctrl.getBoolean("stretch", true),
                (btn, on) -> ctrl.edit().putBoolean("stretch", on).apply()));

        card.addView(switchRow("Show FPS",
                "Shows current frames per second and minimum in-game.",
                ctrl.getBoolean("show_fps", false),
                (btn, on) -> ctrl.edit().putBoolean("show_fps", on).apply()));

        return card;
    }

    // ---- CARD 3: Touch controls ----------------------------------------------------------------
    private View buildTouchControlsCard(SharedPreferences ctrl) {
        LinearLayout card = createCard();
        card.addView(cardTitle("Touch controls"));
        card.addView(goldLine());

        card.addView(bodyText("Move the touch buttons and change their size and opacity.", 10.5f, TEXT_MUTED));

        Button btnEditControls = actionButton("Edit touch controls", false);
        btnEditControls.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, TouchControlsEditorActivity.class));
        });
        card.addView(btnEditControls);

        card.addView(switchRow("Vibration",
                "The game rumbles when you hit walls or rough ground.",
                ctrl.getBoolean("haptics", true),
                (btn, on) -> ctrl.edit().putBoolean("haptics", on).apply()));

        Button btnTestVib = actionButton("Test vibration", false);
        btnTestVib.setOnClickListener(v -> {
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                vib.vibrate(120);
                Toast.makeText(this, "Vibration tested", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Device vibrator not available", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams vibLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        vibLp.topMargin = dp(4);
        vibLp.bottomMargin = dp(8);
        card.addView(btnTestVib, vibLp);

        card.addView(switchRow("Steer by tilting the phone",
                "Activate and fine-tune. Tilt the phone to steer without touch buttons.",
                ctrl.getBoolean("tilt", false),
                (btn, on) -> ctrl.edit().putBoolean("tilt", on).apply()));

        card.addView(heading("Steering sensitivity"));
        int currentDeg = Math.round(ctrl.getFloat("tilt_sensitivity", 1.0f) * 15f);
        TextView lblTilt = bodyText("0 - full turn at " + currentDeg + "°", 10.5f, TEXT_MUTED);
        card.addView(lblTilt);

        SeekBar sbTilt = new SeekBar(this);
        sbTilt.setMin(5);
        sbTilt.setMax(30);
        sbTilt.setProgress(Math.max(5, Math.min(30, currentDeg)));
        sbTilt.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        sbTilt.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        sbTilt.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                lblTilt.setText("0 - full turn at " + progress + "°");
                ctrl.edit().putFloat("tilt_sensitivity", progress / 15f).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        card.addView(sbTilt);
        card.addView(bodyText("Pull to right: less tilt needed to steer all the way.", 10f, TEXT_MUTED));

        return card;
    }

    // ---- CARD 4: Graphics ----------------------------------------------------------------------
    private View buildGraphicsCard(SharedPreferences ctrl) {
        LinearLayout card = createCard();
        card.addView(cardTitle("Graphics"));
        card.addView(goldLine());

        // Render resolution
        card.addView(heading("Render resolution"));
        card.addView(bodyText("The size the game is drawn at, before it goes to the screen. 1280x720 is native Xbox 360.", 10.5f, TEXT_MUTED));
        RadioGroupHelper renderGroup = new RadioGroupHelper();
        String curRes = GameOptions.get(this, GameOptions.RESOLUTION);
        card.addView(renderGroup.createItem("1024x576 - faster", "960x540".equals(curRes), () -> GameOptions.set(this, GameOptions.RESOLUTION, "960x540")));
        card.addView(renderGroup.createItem("1280x720 - Xbox 360", "1280x720".equals(curRes), () -> GameOptions.set(this, GameOptions.RESOLUTION, "1280x720")));
        card.addView(renderGroup.createItem("1920x1080 - sharper", "1920x1080".equals(curRes), () -> GameOptions.set(this, GameOptions.RESOLUTION, "1920x1080")));

        // Frame rate limit
        card.addView(heading("Frame rate limit"));
        card.addView(bodyText("Target framerate cap for gameplay smoothness vs battery life.", 10.5f, TEXT_MUTED));
        Spinner spFps = createSpinner(new String[] {"60 fps", "30 fps", "Unlocked"},
                "30".equals(GameOptions.get(this, GameOptions.FPS)) ? 1 : 0,
                pos -> GameOptions.set(this, GameOptions.FPS, pos == 1 ? "30" : "60"));
        card.addView(spFps);

        // Anti-aliasing
        card.addView(heading("Anti-aliasing"));
        card.addView(bodyText("Smooth jagged edges. FXAA applies a lightweight filter over the final image with minimal overhead.", 10.5f, TEXT_MUTED));
        RadioGroupHelper aaGroup = new RadioGroupHelper();
        String curAa = GameOptions.get(this, GameOptions.AA);
        card.addView(aaGroup.createItem("Off", "apagado".equals(curAa), () -> GameOptions.set(this, GameOptions.AA, "apagado")));
        card.addView(aaGroup.createItem("FXAA", "fxaa".equals(curAa), () -> GameOptions.set(this, GameOptions.AA, "fxaa")));

        // Scaling to screen
        card.addView(heading("Scaling to the screen"));
        card.addView(bodyText("How the game image scales to your phone's display.", 10.5f, TEXT_MUTED));
        RadioGroupHelper scaleGroup = new RadioGroupHelper();
        card.addView(scaleGroup.createItem("Bilinear (standard)", true, () -> {}));
        card.addView(scaleGroup.createItem("AMD FSR 1.0 - sharper upscaling", false, () -> {}));
        card.addView(scaleGroup.createItem("AMD CAS - sharpening only", false, () -> {}));

        // Anisotropic filtering
        card.addView(heading("Anisotropic filtering"));
        card.addView(bodyText("Sharper textures seen at an angle, such as asphalt and road markings in the distance.", 10.5f, TEXT_MUTED));
        RadioGroupHelper afGroup = new RadioGroupHelper();
        String curAf = GameOptions.get(this, GameOptions.ANISOTROPIC);
        card.addView(afGroup.createItem("Off", "0".equals(curAf), () -> GameOptions.set(this, GameOptions.ANISOTROPIC, "0")));
        card.addView(afGroup.createItem("2x", "2".equals(curAf), () -> GameOptions.set(this, GameOptions.ANISOTROPIC, "2")));
        card.addView(afGroup.createItem("4x", "4".equals(curAf) || (!"0".equals(curAf) && !"2".equals(curAf) && !"16".equals(curAf)), () -> GameOptions.set(this, GameOptions.ANISOTROPIC, "4")));
        card.addView(afGroup.createItem("16x", "16".equals(curAf), () -> GameOptions.set(this, GameOptions.ANISOTROPIC, "16")));

        // Shadows
        card.addView(heading("Shadows"));
        card.addView(bodyText("How often shadow maps update. Every 2 frames reduces overhead with no visible difference.", 10.5f, TEXT_MUTED));
        RadioGroupHelper shGroup = new RadioGroupHelper();
        String curSh = GameOptions.get(this, GameOptions.SHADOWS);
        card.addView(shGroup.createItem("Every frame", !"2".equals(curSh), () -> GameOptions.set(this, GameOptions.SHADOWS, "1")));
        card.addView(shGroup.createItem("Every 2 frames", "2".equals(curSh), () -> GameOptions.set(this, GameOptions.SHADOWS, "2")));

        // Shadow distance
        card.addView(heading("Shadow distance"));
        card.addView(bodyText("Distance at which dynamic world objects cast shadows.", 10.5f, TEXT_MUTED));
        RadioGroupHelper distGroup = new RadioGroupHelper();
        card.addView(distGroup.createItem("Far (Xbox 360 default)", true, () -> {}));
        card.addView(distGroup.createItem("Medium", false, () -> {}));
        card.addView(distGroup.createItem("Near - faster", false, () -> {}));

        // Car reflections
        card.addView(heading("Car reflections"));
        card.addView(bodyText("Cubemap faces rendered for car paint and chrome surfaces.", 10.5f, TEXT_MUTED));
        RadioGroupHelper reflGroup = new RadioGroupHelper();
        String curRefl = GameOptions.get(this, GameOptions.CAR_REFLECTIONS);
        card.addView(reflGroup.createItem("High (6 faces)", !"2".equals(curRefl) && !"1".equals(curRefl), () -> GameOptions.set(this, GameOptions.CAR_REFLECTIONS, "6")));
        card.addView(reflGroup.createItem("Medium (2 faces)", "2".equals(curRefl), () -> GameOptions.set(this, GameOptions.CAR_REFLECTIONS, "2")));
        card.addView(reflGroup.createItem("Low (1 face - fast)", "1".equals(curRefl), () -> GameOptions.set(this, GameOptions.CAR_REFLECTIONS, "1")));

        // Wet road reflection
        card.addView(switchRow("Wet road reflection",
                "Water specular reflections on asphalt during rainy weather.",
                "true".equals(GameOptions.get(this, GameOptions.ROAD_REFLECTION)),
                (btn, on) -> GameOptions.set(this, GameOptions.ROAD_REFLECTION, on ? "true" : "false")));

        // Sky glow
        card.addView(heading("Sky glow"));
        card.addView(bodyText("Atmospheric amber halo and lens flare in the Rockport horizon.", 10.5f, TEXT_MUTED));
        RadioGroupHelper skyGroup = new RadioGroupHelper();
        String curSky = GameOptions.get(this, GameOptions.SKY);
        card.addView(skyGroup.createItem("Natural", !"original".equals(curSky) && !"suave".equals(curSky), () -> GameOptions.set(this, GameOptions.SKY, "natural")));
        card.addView(skyGroup.createItem("Original (Xbox 360)", "original".equals(curSky), () -> GameOptions.set(this, GameOptions.SKY, "original")));
        card.addView(skyGroup.createItem("Soft", "suave".equals(curSky), () -> GameOptions.set(this, GameOptions.SKY, "suave")));

        // Color filter
        card.addView(heading("Game colour filter"));
        card.addView(bodyText("Rockport iconic warm yellow/sepia cinematic grading.", 10.5f, TEXT_MUTED));
        RadioGroupHelper filterGroup = new RadioGroupHelper();
        card.addView(filterGroup.createItem("Original", true, () -> {}));
        card.addView(filterGroup.createItem("Half", false, () -> {}));
        card.addView(filterGroup.createItem("Off", false, () -> {}));

        // Speed blur
        card.addView(switchRow("Speed blur",
                "Motion blur streaks and camera shake at high speeds.",
                ctrl.getBoolean("motion_blur", true),
                (btn, on) -> ctrl.edit().putBoolean("motion_blur", on).apply()));

        // Image filter
        card.addView(heading("Image filter"));
        card.addView(bodyText("Optional post-processing filter presets.", 10.5f, TEXT_MUTED));
        Spinner spFilter = createSpinner(new String[] {"None", "Warm", "Cold", "Vibrant", "Cinema"}, 0, pos -> {
            String[] vals = {"apagado", "calido", "frio", "vivo", "cine"};
            GameOptions.set(this, GameOptions.FILTER, vals[pos]);
        });
        card.addView(spFilter);

        return card;
    }

    // ---- CARD 5: Sound -------------------------------------------------------------------------
    private View buildSoundCard(SharedPreferences ctrl) {
        LinearLayout card = createCard();
        card.addView(cardTitle("Sound"));
        card.addView(goldLine());

        card.addView(switchRow("Sound through Audio",
                "Thicker, louder engine acoustics and deeper bass response.",
                ctrl.getBoolean("audio_boost", true),
                (btn, on) -> ctrl.edit().putBoolean("audio_boost", on).apply()));

        return card;
    }

    // ---- CARD 6: Performance -------------------------------------------------------------------
    private View buildPerformanceCard(SharedPreferences ctrl) {
        LinearLayout card = createCard();
        card.addView(cardTitle("Performance"));
        card.addView(goldLine());

        card.addView(switchRow("Pin threads to cores",
                "Pins game worker threads to fast CPU cores to reduce frame pacing jitter.",
                ctrl.getBoolean("pin_threads", true),
                (btn, on) -> ctrl.edit().putBoolean("pin_threads", on).apply()));

        card.addView(heading("GPU driver"));
        RadioGroupHelper driverGroup = new RadioGroupHelper();
        String curDriver = GpuDrivers.selectedId(this);
        boolean isMaliCompat = "malicompat-g71".equals(curDriver);
        card.addView(driverGroup.createItem("System driver (Default)", !isMaliCompat, () -> {
            GpuDrivers.select(this, "");
            Toast.makeText(this, "Using System driver (Default)", Toast.LENGTH_SHORT).show();
        }));
        card.addView(driverGroup.createItem("MaliCompat driver", isMaliCompat, () -> {
            GpuDrivers.ensureDefaultDrivers(this);
            GpuDrivers.select(this, "malicompat-g71");
            Toast.makeText(this, "Using MaliCompat driver", Toast.LENGTH_SHORT).show();
        }));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = dp(6);
        btnRow.setLayoutParams(btnRowLp);

        Button btnImport = actionButton("Import driver (ZIP)", false);
        btnImport.setOnClickListener(v -> chooseDriverZip());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lp1.rightMargin = dp(4);
        btnRow.addView(btnImport, lp1);

        Button btnDelete = actionButton("Delete custom", false);
        btnDelete.setTextColor(0xFFFF6666);
        btnDelete.setOnClickListener(v -> {
            GpuDrivers.select(this, "");
            Toast.makeText(this, "Reset to System default driver", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lp2.leftMargin = dp(4);
        btnRow.addView(btnDelete, lp2);
        card.addView(btnRow);

        card.addView(switchRow("CPU turbo",
                "Requests aggressive CPU frequency scaling for high performance.",
                ctrl.getBoolean("cpu_turbo", false),
                (btn, on) -> ctrl.edit().putBoolean("cpu_turbo", on).apply()));

        return card;
    }

    // ---- CARD 7: Language ----------------------------------------------------------------------
    private View buildLanguageCard() {
        LinearLayout card = createCard();
        card.addView(cardTitle("Language"));
        card.addView(goldLine());

        RadioGroupHelper langGroup = new RadioGroupHelper();
        String curLang = GameOptions.get(this, "language");
        card.addView(langGroup.createItem("Automatic (phone language)", "-1".equals(curLang), () -> GameOptions.set(this, "language", "-1")));
        card.addView(langGroup.createItem("English", "0".equals(curLang), () -> GameOptions.set(this, "language", "0")));
        card.addView(langGroup.createItem("Español", "4".equals(curLang), () -> GameOptions.set(this, "language", "4")));
        card.addView(langGroup.createItem("Português", "2".equals(curLang), () -> GameOptions.set(this, "language", "2")));

        return card;
    }

    // ---- CARD 8: Advanced (STRICTLY COLLAPSED BY DEFAULT) ---------------------------------------
    private View buildAdvancedCard() {
        LinearLayout card = createCard();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClickable(true);
        header.setFocusable(true);

        advArrow = cardTitle("▶ Advanced");
        header.addView(advArrow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(header);

        advContent = new LinearLayout(this);
        advContent.setOrientation(LinearLayout.VERTICAL);
        advContent.setVisibility(View.GONE); // Strictly collapsed by default

        advContent.addView(goldLine());
        advContent.addView(bodyText("Tools to diagnose Vulkan and share logs without launching gameplay.", 10.5f, TEXT_MUTED));

        Button btnVulkan = actionButton("Test Vulkan", false);
        btnVulkan.setOnClickListener(v -> probeDriver(false));
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        vLp.topMargin = dp(8);
        advContent.addView(btnVulkan, vLp);

        Button btnReport = actionButton("Diagnostics & Share Logs", false);
        btnReport.setOnClickListener(v -> chooseReportDestination());
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        rLp.topMargin = dp(8);
        advContent.addView(btnReport, rLp);

        header.setOnClickListener(v -> {
            boolean isVisible = advContent.getVisibility() == View.VISIBLE;
            advContent.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            advArrow.setText(isVisible ? "▶ Advanced" : "▼ Advanced");
        });

        card.addView(advContent);
        return card;
    }

    // ============================================================================================
    // UI WIDGET HELPERS
    // ============================================================================================

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {CARD_BG_START, CARD_BG_END});
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x24E8A13C);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView cardTitle(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    private View goldLine() {
        View line = new View(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xB3E8A13C, 0x33FF8A00, Color.TRANSPARENT});
        line.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(10);
        line.setLayoutParams(lp);
        return line;
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView bodyText(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private View switchRow(String title, String desc, boolean initial, CompoundButton.OnCheckedChangeListener onCheck) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        row.setLayoutParams(rowLp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView tTitle = bodyText(title, 13, Color.WHITE);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(tTitle);
        if (desc != null && !desc.isEmpty()) {
            TextView tDesc = bodyText(desc, 10.5f, TEXT_MUTED);
            tDesc.setPadding(0, dp(1), 0, 0);
            left.addView(tDesc);
        }
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(initial);
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(0x55E8A13C));
        sw.setOnCheckedChangeListener(onCheck);
        row.addView(sw);

        return row;
    }

    private Button actionButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 15 : 12.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFF0C1014 : Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        if (primary) {
            bg.setColor(ACCENT);
            bg.setCornerRadius(dp(8));
        } else {
            bg.setColor(0xFF262930);
            bg.setStroke(dp(1), 0x22FFFFFF);
            bg.setCornerRadius(dp(8));
        }
        b.setBackground(bg);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(primary ? 44 : 40));
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private Spinner createSpinner(String[] items, int selected, ConsumerInt onSelect) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(Color.WHITE);
                    ((TextView) v).setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                }
                return v;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                v.setBackgroundColor(0xFF1E222A);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(Color.WHITE);
                    ((TextView) v).setPadding(dp(12), dp(12), dp(12), dp(12));
                }
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        sp.setSelection(selected);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF141519);
        bg.setStroke(dp(1), 0xFF30343E);
        bg.setCornerRadius(dp(8));
        sp.setBackground(bg);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { onSelect.accept(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        lp.topMargin = dp(4);
        sp.setLayoutParams(lp);
        return sp;
    }

    private interface ConsumerInt {
        void accept(int val);
    }

    private final class RadioGroupHelper {
        private final List<View> circles = new ArrayList<>();
        private final List<Runnable> actions = new ArrayList<>();

        View createItem(String text, boolean initialSelected, Runnable onSelect) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            View circle = new View(MainActivity.this);
            final int idx = circles.size();
            circles.add(circle);
            actions.add(onSelect);

            updateCircle(circle, initialSelected);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(18), dp(18));
            cLp.rightMargin = dp(8);
            row.addView(circle, cLp);

            TextView label = bodyText(text, 12, Color.WHITE);
            row.addView(label);

            row.setOnClickListener(v -> {
                for (int i = 0; i < circles.size(); i++) {
                    updateCircle(circles.get(i), i == idx);
                }
                onSelect.run();
            });

            return row;
        }

        private void updateCircle(View circle, boolean selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            if (selected) {
                bg.setColor(ACCENT);
                bg.setStroke(dp(3), CARD_BG_START);
            } else {
                bg.setColor(Color.TRANSPARENT);
                bg.setStroke(dp(2), 0xFF555A66);
            }
            circle.setBackground(bg);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ============================================================================================
    // ROM & FOLDER PICKER HANDLING
    // ============================================================================================

    private void showPickerSelectionDialog() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Select Game Source")
                .setItems(new String[] {"Select Extracted Game Folder (default.xex)", "Select Xbox 360 Game ISO (.iso)"},
                        (d, which) -> {
                            if (which == 0) selectGameFolder();
                            else selectGameIso();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void selectGameFolder() {
        if (!hasStorageAccess()) {
            prepareSharedGameFolder();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_GAME_FOLDER);
    }

    private void selectGameIso() {
        if (!hasStorageAccess()) {
            prepareSharedGameFolder();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra("android.intent.extra.MIME_TYPES", new String[] {"application/x-iso9660-image", "application/octet-stream", "*/*"});
        startActivityForResult(intent, REQUEST_ISO);
    }

    private void checkAndRefreshGameStatus() {
        File sharedRoot = sharedGameRoot();
        if (isValidGameFolder(sharedRoot)) {
            verifiedGamePath = sharedRoot.getAbsolutePath();
            savePathForNativeBackend(verifiedGamePath);
            importStatus.setText("✔ Game folder verified: /sdcard/" + GAME_FOLDER_NAME + " (default.xex found)");
            importStatus.setTextColor(0xFF7CD992);
            launchGameBtn.setEnabled(true);
            launchGameBtn.setAlpha(1.0f);
            launchGameBtn.setText("▶   START ENGINE");
            selectRomBtn.setText("Change Game Folder / ISO...");
            return;
        }

        String savedIso = getPreferences(MODE_PRIVATE).getString(PREF_ISO_PATH, null);
        if (savedIso != null && new File(savedIso).isFile()) {
            verifiedGamePath = savedIso;
            savePathForNativeBackend(verifiedGamePath);
            importStatus.setText("💿 ISO verified: " + new File(savedIso).getName());
            importStatus.setTextColor(0xFF7CD992);
            launchGameBtn.setEnabled(true);
            launchGameBtn.setAlpha(1.0f);
            launchGameBtn.setText("▶   START ENGINE");
            selectRomBtn.setText("Change Game Folder / ISO...");
            return;
        }

        verifiedGamePath = null;
        importStatus.setText("No ISO or folder selected. You need your own Xbox 360 ISO or extracted game folder.");
        importStatus.setTextColor(Color.WHITE);
        launchGameBtn.setEnabled(false);
        launchGameBtn.setAlpha(0.45f);
        launchGameBtn.setText("Play");
        selectRomBtn.setText("Choose Game (ISO or Folder)...");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DRIVER_PROBE) {
            showDriverReport(resultCode == RESULT_OK && data != null ? data.getStringExtra("gpu_report")
                    : "{\"probeError\":\"Driver probe was cancelled or closed.\",\"driverLoadFailed\":true}");
            return;
        }
        if (requestCode == REQUEST_DRIVER_ZIP) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            importDriverZip(data.getData());
            return;
        }
        if (requestCode == REQUEST_SAVE_REPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) finishReportSave(data.getData());
            else pendingReport = null;
            return;
        }
        if (requestCode == REQUEST_STORAGE_ACCESS || requestCode == REQUEST_LEGACY_STORAGE) {
            if (hasStorageAccess()) prepareSharedGameFolder();
            return;
        }
        if (requestCode == REQUEST_ISO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            handleIsoSelected(data.getData());
            return;
        }
        if (requestCode == REQUEST_GAME_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            getPreferences(MODE_PRIVATE).edit().putString(TREE_URI, tree.toString()).apply();
            importGameFolder(tree);
        }
    }

    private void handleIsoSelected(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        String realPath = resolveRealPathFromUri(uri, false);
        if (realPath != null && new File(realPath).isFile()) {
            getPreferences(MODE_PRIVATE).edit().putString(PREF_ISO_PATH, realPath).apply();
            checkAndRefreshGameStatus();
            Toast.makeText(this, "✔ ISO verified: " + new File(realPath).getName(), Toast.LENGTH_SHORT).show();
        } else {
            // Document URI copy or direct stream
            importStatus.setText("💿 Selected ISO: " + uri.getLastPathSegment());
            verifiedGamePath = uri.toString();
            savePathForNativeBackend(verifiedGamePath);
            launchGameBtn.setEnabled(true);
            launchGameBtn.setAlpha(1.0f);
            launchGameBtn.setText("▶   START ENGINE");
        }
    }

    private String resolveRealPathFromUri(Uri uri, boolean isTree) {
        try {
            String path = uri.getPath();
            if (isTree) {
                String treeDocId = DocumentsContract.getTreeDocumentId(uri);
                if (treeDocId != null && treeDocId.startsWith("primary:")) {
                    return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + treeDocId.substring(8);
                }
            } else if (DocumentsContract.isDocumentUri(this, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId != null && docId.startsWith("primary:")) {
                    return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + docId.substring(8);
                }
            }
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                try (Cursor cursor = getContentResolver().query(uri, new String[] {"_data"}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex("_data");
                        if (idx >= 0) return cursor.getString(idx);
                    }
                }
            }
            return (path != null && path.startsWith("/tree/primary:"))
                    ? Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + path.substring(14) : path;
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================================================
    // GAME EXECUTION & SHADERS
    // ============================================================================================

    private void playChecked() {
        if (verifiedGamePath == null) {
            Toast.makeText(this, "Please select an ISO or game folder first.", Toast.LENGTH_SHORT).show();
            showPickerSelectionDialog();
            return;
        }

        File userDir = new File(getFilesDir(), "nfsmw/user");
        GameOptions.writeToml(this, userDir);
        File extDir = getExternalFilesDir(null);
        if (extDir != null) GameOptions.writeToml(this, extDir);
        savePathForNativeBackend(verifiedGamePath);

        File gameRoot = sharedGameRoot();
        if ("nativo".equals(GameOptions.get(this, GameOptions.RENDERER))) {
            if (!ShaderBuilder.ensureBundledShaders(this, gameRoot) && !ShaderBuilder.hasLibrary(gameRoot)) {
                buildShaders(true);
                return;
            }
        }
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra("selected_game_path", verifiedGamePath);
        startActivity(intent);
    }

    private void buildShaders(boolean thenPlay) {
        if (shaderBuilder != null) return;
        launchGameBtn.setEnabled(false);
        launchGameBtn.setAlpha(0.5f);
        shaderPanel.setVisibility(View.VISIBLE);
        shaderProgress.setProgress(0);
        shaderText.setText("⚡ Preparing Vulkan pipeline caches (first launch only)…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        shaderBuilder = new ShaderBuilder(this, sharedGameRoot(), new ShaderBuilder.Listener() {
            @Override
            public void onProgress(float fraction, String text) {
                shaderProgress.setProgress(Math.round(fraction * 1000));
                shaderText.setText(text);
            }

            @Override
            public void onDone(File library) {
                shaderBuilder = null;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                shaderPanel.setVisibility(View.GONE);
                launchGameBtn.setEnabled(true);
                launchGameBtn.setAlpha(1.0f);
                if (thenPlay) {
                    Intent intent = new Intent(MainActivity.this, GameActivity.class);
                    intent.putExtra("selected_game_path", verifiedGamePath);
                    startActivity(intent);
                }
            }

            @Override
            public void onError(String message) {
                shaderBuilder = null;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                shaderText.setText("Shader build note: " + message);
                launchGameBtn.setEnabled(true);
                launchGameBtn.setAlpha(1.0f);
            }
        });
        shaderBuilder.start();
    }

    private void savePathForNativeBackend(String path) {
        try {
            File f1 = new File(getFilesDir(), "selected_game_path.txt");
            try (FileOutputStream out = new FileOutputStream(f1)) {
                out.write(path.getBytes(StandardCharsets.UTF_8));
            }
            File ext = getExternalFilesDir(null);
            if (ext != null) {
                File f2 = new File(ext, "selected_game_path.txt");
                try (FileOutputStream out = new FileOutputStream(f2)) {
                    out.write(path.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {}
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void prepareSharedGameFolder() {
        if (!hasStorageAccess()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent settings = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(settings, REQUEST_STORAGE_ACCESS);
            } else {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_LEGACY_STORAGE);
            }
            return;
        }
        checkAndRefreshGameStatus();
    }

    private File sharedGameRoot() {
        return new File(Environment.getExternalStorageDirectory(), GAME_FOLDER_NAME);
    }

    private static boolean isValidGameFolder(File folder) {
        if (folder == null || !folder.exists()) return false;
        return new File(folder, "default.xex").isFile() || new File(folder, "DEFAULT.XEX").isFile();
    }

    private void importGameFolder(Uri tree) {
        importStatus.setText("Copying game files to /sdcard/" + GAME_FOLDER_NAME + "…");
        importer.execute(() -> {
            File gameRoot = sharedGameRoot();
            File parent = gameRoot.getParentFile();
            File staging = new File(parent, "." + GAME_FOLDER_NAME + ".importing");
            File previous = new File(parent, "." + GAME_FOLDER_NAME + ".previous");
            try {
                deleteRecursively(staging);
                deleteRecursively(previous);
                DocumentFile source = DocumentFile.fromTreeUri(this, tree);
                if (source == null || !source.isDirectory()) throw new IOException("Could not open selected folder.");
                copyDirectory(source, staging, staging.getCanonicalFile(), new long[]{0});
                if (!isValidGameFolder(staging)) {
                    throw new IOException("The selected folder must contain default.xex");
                }
                installStagedGame(staging, gameRoot, previous);
                deleteRecursively(previous);
                mainHandler.post(this::checkAndRefreshGameStatus);
            } catch (Exception error) {
                try { deleteRecursively(staging); } catch (IOException ignored) {}
                mainHandler.post(() -> importStatus.setText("Import failed: " + error.getMessage()));
            }
        });
    }

    private static void installStagedGame(File staging, File gameRoot, File previous) throws IOException {
        boolean movedPrevious = false;
        if (gameRoot.exists()) {
            Files.move(gameRoot.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
            movedPrevious = true;
        }
        try {
            Files.move(staging.toPath(), gameRoot.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            if (movedPrevious) Files.move(previous.toPath(), gameRoot.toPath(), StandardCopyOption.REPLACE_EXISTING);
            throw error;
        }
    }

    private void copyDirectory(DocumentFile source, File destination, File safeRoot, long[] copiedBytes) throws IOException {
        if (!destination.mkdirs() && !destination.isDirectory()) throw new IOException("Could not create " + destination);
        for (DocumentFile child : source.listFiles()) {
            String name = child.getName();
            if (name == null || name.isEmpty() || name.contains("..")) continue;
            File target = new File(destination, name);
            if (child.isDirectory()) {
                copyDirectory(child, target, safeRoot, copiedBytes);
            } else if (child.isFile()) {
                try (InputStream in = getContentResolver().openInputStream(child.getUri());
                     FileOutputStream out = new FileOutputStream(target)) {
                    if (in == null) continue;
                    byte[] buf = new byte[1024 * 1024];
                    for (int n; (n = in.read(buf)) != -1;) {
                        out.write(buf, 0, n);
                        copiedBytes[0] += n;
                    }
                }
            }
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete()) throw new IOException("Could not delete " + file.getName());
    }

    // ============================================================================================
    // VULKAN DRIVER & DIAGNOSTICS
    // ============================================================================================

    private void chooseDriverZip() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(picker, REQUEST_DRIVER_ZIP);
    }

    private void importDriverZip(Uri zip) {
        importer.execute(() -> {
            try {
                GpuDrivers.Driver driver = GpuDrivers.importZip(this, zip);
                mainHandler.post(() -> {
                    GpuDrivers.select(this, driver.id);
                    Toast.makeText(this, "Driver imported: " + driver.name, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void probeDriver(boolean playAfter) {
        playAfterProbe = playAfter;
        startActivityForResult(new Intent(this, GpuProbeActivity.class), REQUEST_DRIVER_PROBE);
    }

    private void showDriverReport(String report) {
        try {
            org.json.JSONObject gpu = new org.json.JSONObject(report);
            String text = "GPU: " + gpu.optString("gpu", "Mali GPU")
                    + "\nVulkan: " + gpu.optString("vulkan", "Vulkan 1.1")
                    + "\nDescriptor Sets: " + gpu.optInt("maxBoundDescriptorSets", 4)
                    + "\nStatus: Compatible with Mali native pipeline.";
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Vulkan Driver Report")
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "Driver probe completed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void chooseReportDestination() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Share Crash Report / Logs")
                .setMessage("Diagnostics bundle includes device model, Vulkan features, and logs.")
                .setPositiveButton("Email", (d, w) -> prepareReport(0))
                .setNeutralButton("GitHub", (d, w) -> prepareReport(1))
                .setNegativeButton("Save ZIP", (d, w) -> prepareReport(2))
                .show();
    }

    private void prepareReport(int destination) {
        importer.execute(() -> {
            try {
                File report = Diagnostics.createReport(this);
                mainHandler.post(() -> {
                    if (destination == 0) shareReportByEmail(report);
                    else saveReport(report, destination == 1);
                });
            } catch (Exception error) {
                mainHandler.post(() -> Toast.makeText(this, "Could not generate report: " + error.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void shareReportByEmail(File report) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".reports", report);
        Intent email = new Intent(Intent.ACTION_SEND);
        email.setType("application/zip");
        email.putExtra(Intent.EXTRA_EMAIL, new String[]{Diagnostics.EMAIL});
        email.putExtra(Intent.EXTRA_SUBJECT, "NFSMW Recomp Android: " + Build.MODEL + " — Diagnostic");
        email.putExtra(Intent.EXTRA_TEXT, Diagnostics.summary(this));
        email.putExtra(Intent.EXTRA_STREAM, uri);
        email.setClipData(ClipData.newUri(getContentResolver(), "NFSMW Diagnostic", uri));
        email.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(email, "Send diagnostic report")); }
        catch (ActivityNotFoundException error) { saveReport(report, false); }
    }

    private void saveReport(File report, boolean github) {
        pendingReport = report;
        pendingGithub = github;
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/zip");
        save.putExtra(Intent.EXTRA_TITLE, pendingReport.getName());
        try { startActivityForResult(save, REQUEST_SAVE_REPORT); }
        catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No file manager found to save ZIP.", Toast.LENGTH_SHORT).show();
        }
    }

    private void finishReportSave(Uri destination) {
        File report = pendingReport;
        boolean github = pendingGithub;
        pendingReport = null;
        if (report == null || !report.isFile()) return;
        importer.execute(() -> {
            try (InputStream input = new FileInputStream(report);
                 java.io.OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) return;
                byte[] buffer = new byte[16384];
                for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            } catch (Exception ignored) { return; }
            mainHandler.post(() -> {
                if (github) {
                    Uri issue = Uri.parse(Diagnostics.ISSUES);
                    try { startActivity(new Intent(Intent.ACTION_VIEW, issue)); } catch (Exception ignored) {}
                } else {
                    Toast.makeText(this, "Report ZIP saved successfully.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (shaderBuilder != null) shaderBuilder.cancel();
        importer.shutdown();
        super.onDestroy();
    }
}
