package com.nfsmw.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.content.Intent;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_GAME_FOLDER = 41;
    private static final int REQUEST_STORAGE_ACCESS = 42;
    private static final int REQUEST_LEGACY_STORAGE = 43;
    private static final int REQUEST_SAVE_REPORT = 44;
    private static final int REQUEST_DRIVER_ZIP = 45;
    private static final int REQUEST_DRIVER_PROBE = 46;
    private boolean playAfterProbe;
    private boolean launcherResumed;

    private static final String TREE_URI = "tree_uri";
    static final String GAME_FOLDER_NAME = "nsfmw-androidevolved";

    private TextView importStatus;
    private Button selectFolder;
    private Button launchGame;
    private Button sendReport;
    private File pendingReport;
    private boolean pendingGithub;
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
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        setContentView(buildScreen());
        prepareSharedGameFolder();
    }

    @Override
    protected void onResume() {
        super.onResume();
        launcherResumed = true;
        if (optionsList != null) {
            refreshOptions();
        }
        if (checksList != null && hasStorageAccess()) {
            showChecks(sharedGameRoot());
        }
    }

    @Override protected void onPause() {
        launcherResumed = false;
        super.onPause();
    }

    // ---- Screen --------------------------------------------------------------------------------------------

    private static final int ACCENT = 0xFFFF8A00;
    private LinearLayout checksList;
    private LinearLayout driverList;
    private LinearLayout optionsList;
    private LinearLayout audioList;
    private LinearLayout shaderPanel;
    private ProgressBar shaderProgress;
    private TextView shaderText;
    private ShaderBuilder shaderBuilder;

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF0C1014, 0xFF14140D, 0xFF0C1014}));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // Header: authentic banner & title
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.banner);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(logo, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)));

        TextView subtitle = label("Xbox 360 · ARM64 Mali Edition", 13, 0x99FFFFFF, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        content.addView(subtitle);

        // Card 1: Game Files
        LinearLayout gameCard = card();
        content.addView(gameCard, marginBox(10));
        gameCard.addView(sectionHeader("GAME FILES"));
        importStatus = label("", 13, 0xDDFFFFFF, false);
        importStatus.setPadding(0, dp(2), 0, dp(6));
        gameCard.addView(importStatus);
        checksList = new LinearLayout(this);
        checksList.setOrientation(LinearLayout.VERTICAL);
        gameCard.addView(checksList);

        shaderPanel = new LinearLayout(this);
        shaderPanel.setOrientation(LinearLayout.VERTICAL);
        shaderPanel.setPadding(0, dp(8), 0, 0);
        shaderPanel.setVisibility(View.GONE);
        shaderText = label("", 13, 0xFFFFFFFF, false);
        shaderPanel.addView(shaderText);
        shaderProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        shaderProgress.setMax(1000);
        shaderProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        shaderPanel.addView(shaderProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));
        gameCard.addView(shaderPanel);

        selectFolder = actionButton("Select Game Folder", false);
        selectFolder.setOnClickListener(view -> selectGameFolder());
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        folderParams.topMargin = dp(10);
        gameCard.addView(selectFolder, folderParams);

        // Hero PLAY Button
        launchGame = actionButton("▶   PLAY", true);
        launchGame.setVisibility(View.GONE);
        launchGame.setOnClickListener(view -> play());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        playParams.topMargin = dp(14);
        content.addView(launchGame, playParams);

        // Card 2: GPU Driver (Dedicated Mali setup)
        LinearLayout driverCard = card();
        content.addView(driverCard, marginBox(14));
        driverCard.addView(sectionHeader("GPU & DRIVER"));
        driverList = new LinearLayout(this);
        driverList.setOrientation(LinearLayout.VERTICAL);
        driverCard.addView(driverList);

        // Card 3: Graphics & Performance Settings
        LinearLayout graphicsCard = card();
        content.addView(graphicsCard, marginBox(14));
        graphicsCard.addView(sectionHeader("GRAPHICS & PERFORMANCE"));
        optionsList = new LinearLayout(this);
        optionsList.setOrientation(LinearLayout.VERTICAL);
        graphicsCard.addView(optionsList);

        // Card 4: Audio & System Settings
        LinearLayout audioCard = card();
        content.addView(audioCard, marginBox(14));
        audioCard.addView(sectionHeader("AUDIO & SYSTEM"));
        audioList = new LinearLayout(this);
        audioList.setOrientation(LinearLayout.VERTICAL);
        audioCard.addView(audioList);

        // Card 5: Diagnostics & Logs
        LinearLayout diagCard = card();
        content.addView(diagCard, marginBox(14));
        diagCard.addView(sectionHeader("DIAGNOSTICS & LOGS"));
        TextView diagDesc = label("Export system state, device GPU probe, and full live logs for debugging.", 12, 0x88FFFFFF, false);
        diagDesc.setPadding(0, dp(2), 0, dp(8));
        diagCard.addView(diagDesc);
        sendReport = actionButton("Share Crash Report / Logs", false);
        sendReport.setOnClickListener(view -> chooseReportDestination());
        diagCard.addView(sendReport, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        refreshOptions();
        return scroll;
    }

    private LinearLayout.LayoutParams marginBox(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private TextView sectionHeader(String title) {
        TextView t = label(title, 12, ACCENT, true);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    // ---- Shader library ------------------------------------------------------------------------------------

    private void play() {
        if (GpuDrivers.selected(this) != null) {
            probeDriver(true);
            return;
        }
        playChecked();
    }

    private void playChecked() {
        launchGame.setEnabled(false);
        Diagnostics.recordLaunch(this);
        importer.execute(() -> {
            String problem = "nativo".equals(GameOptions.get(this, GameOptions.RENDERER))
                    ? Diagnostics.incompatibility(this) : null;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                launchGame.setEnabled(true);
                continuePlay(problem);
            });
        });
    }

    private void continuePlay(String problem) {
        if (problem == null) {
            playCompatibleGame();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Select Alternative Renderer")
                .setMessage(problem + "\n\nYou can test experimental compatibility mode. It may have graphical anomalies or lower performance.")
                .setPositiveButton("Try Compatibility", (dialog, which) -> {
                    GameOptions.set(this, GameOptions.RENDERER, "xenos");
                    refreshOptions();
                    playCompatibleGame();
                })
                .setNeutralButton("Send Report", (dialog, which) -> chooseReportDestination())
                .setNegativeButton("Back", null).show();
    }

    private void playCompatibleGame() {
        if ("xenos".equals(GameOptions.get(this, GameOptions.RENDERER)) || ShaderBuilder.hasLibrary(sharedGameRoot())) {
            startActivity(new Intent(this, GameActivity.class));
        } else {
            buildShaders(true);
        }
    }

    // ---- Tester reports ------------------------------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (pendingReport != null) {
            state.putString("pending_report", pendingReport.getName());
            state.putBoolean("pending_github", pendingGithub);
        }
        state.putBoolean("play_after_probe", playAfterProbe);
        super.onSaveInstanceState(state);
    }

    private void chooseReportDestination() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Share Crash Report / Logs")
                .setMessage("The diagnostic bundle includes device model, Android version, GPU features, settings, and application logs.")
                .setPositiveButton("Email", (dialog, which) -> prepareReport(0))
                .setNeutralButton("GitHub", (dialog, which) -> prepareReport(1))
                .setNegativeButton("Save ZIP", (dialog, which) -> prepareReport(2)).show();
    }

    private void reportError(String message) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Diagnostic").setMessage(message).setPositiveButton("OK", null).show();
    }

    private void prepareReport(int destination) {
        sendReport.setEnabled(false);
        sendReport.setText("Preparing diagnostic report…");
        importer.execute(() -> {
            try {
                File report = Diagnostics.createReport(this);
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    sendReport.setEnabled(true);
                    sendReport.setText("Share Crash Report / Logs");
                    if (destination == 0) shareReportByEmail(report);
                    else saveReport(report, destination == 1);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    sendReport.setEnabled(true);
                    sendReport.setText("Share Crash Report / Logs");
                    reportError("Could not generate report: " + error.getMessage());
                });
            }
        });
    }

    private void shareReportByEmail(File report) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".reports", report);
        Intent email = new Intent(Intent.ACTION_SEND);
        email.setType("application/zip");
        email.putExtra(Intent.EXTRA_EMAIL, new String[]{Diagnostics.EMAIL});
        email.putExtra(Intent.EXTRA_SUBJECT, "NFSMW Recomp Android: " + Build.MODEL + " — Diagnostic");
        email.putExtra(Intent.EXTRA_TEXT, Diagnostics.summary(this) + "\nWhat happened:\n\nSteps to reproduce:\n\nGame edition:\n\nDiagnostic file attached.");
        email.putExtra(Intent.EXTRA_STREAM, uri);
        email.setClipData(ClipData.newUri(getContentResolver(), "NFSMW Diagnostic", uri));
        email.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(email, "Send diagnostic report")); }
        catch (ActivityNotFoundException error) { saveReport(report, false); }
    }

    private void saveReport(File report, boolean github) {
        pendingReport = report;
        pendingGithub = github;
        if (github) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Report on GitHub")
                    .setMessage("First, save the ZIP archive. Afterwards GitHub will open: describe the problem and attach the saved ZIP.")
                    .setPositiveButton("Save and Open GitHub", (dialog, which) -> chooseReportFile())
                    .setNegativeButton("Cancel", (dialog, which) -> pendingReport = null).show();
        } else chooseReportFile();
    }

    private void chooseReportFile() {
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/zip");
        save.putExtra(Intent.EXTRA_TITLE, pendingReport.getName());
        try { startActivityForResult(save, REQUEST_SAVE_REPORT); }
        catch (ActivityNotFoundException error) {
            pendingReport = null;
            reportError("No application available to save files. Please try the Email option.");
        }
    }

    private void finishReportSave(Uri destination) {
        File report = pendingReport;
        boolean github = pendingGithub;
        pendingReport = null;
        if (report == null || !report.isFile()) {
            reportError("The report is no longer available. Please generate a new one.");
            return;
        }
        importer.execute(() -> {
            try (InputStream input = new FileInputStream(report);
                 java.io.OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("Could not open destination file.");
                byte[] buffer = new byte[16384];
                for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) reportError("Could not save ZIP archive: " + error.getMessage());
                });
                return;
            }
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (github) {
                    String body = "### What happened\n\n### Steps to reproduce\n\n### Game edition\n\n### Device\n"
                            + Diagnostics.summary(this) + "\n### Diagnostic\nAttach the saved file **" + report.getName() + "** here.\n";
                    Uri issue = Uri.parse(Diagnostics.ISSUES).buildUpon()
                            .appendQueryParameter("title", "[Android] " + Build.MODEL + ": ")
                            .appendQueryParameter("body", body).build();
                    try { startActivity(new Intent(Intent.ACTION_VIEW, issue)); }
                    catch (ActivityNotFoundException error) { reportError("ZIP saved. Please visit " + Diagnostics.ISSUES + " and attach the file."); }
                } else reportError("ZIP saved successfully. You can attach it to a GitHub issue or email it.");
            });
        });
    }

    /** Builds nfsmw_shaders.nfsp from the game files (a minute or two, only once). */
    private void buildShaders(boolean thenPlay) {
        if (shaderBuilder != null) {
            return;
        }
        launchGame.setEnabled(false);
        launchGame.setAlpha(.5f);
        selectFolder.setEnabled(false);
        shaderPanel.setVisibility(View.VISIBLE);
        shaderProgress.setProgress(0);
        shaderText.setText("Generating native renderer shaders (first launch only)…");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        shaderBuilder = new ShaderBuilder(this, sharedGameRoot(), new ShaderBuilder.Listener() {
            @Override
            public void onProgress(float fraction, String text) {
                shaderProgress.setProgress(Math.round(fraction * 1000));
                shaderText.setText(text);
            }

            @Override
            public void onDone(File library) {
                shadersFinished();
                shaderPanel.setVisibility(View.GONE);
                showChecks(sharedGameRoot());
                if (thenPlay) {
                    startActivity(new Intent(MainActivity.this, GameActivity.class));
                }
            }

            @Override
            public void onError(String message) {
                shadersFinished();
                shaderText.setText("Failed to compile shaders: " + message);
                shaderProgress.setProgress(0);
            }
        });
        shaderBuilder.start();
    }

    private void shadersFinished() {
        shaderBuilder = null;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        launchGame.setEnabled(true);
        launchGame.setAlpha(1f);
        selectFolder.setEnabled(true);
    }

    private void refreshOptions() {
        if (driverList != null) {
            driverList.removeAllViews();
            GpuDrivers.Driver driver = GpuDrivers.selected(this);
            String driverName = driver == null ? "System Driver (Mali G71 HAL)" : driver.name;
            driverList.addView(optionRow("Vulkan Driver", "Hardware driver or compatibility shim", driverName, this::chooseDriver));
            driverList.addView(optionRow("Probe Driver", "Verify GPU extensions and descriptor limits", "Run Probe", () -> probeDriver(false)));
        }
        if (optionsList != null) {
            optionsList.removeAllViews();
            for (GameOptions.Option option : GameOptions.GRAPHICS) {
                String value = GameOptions.get(this, option.key);
                optionsList.addView(optionRow(option.title, optionSummary(option.key), option.label(value), () -> chooseOption(option)));
            }
            SharedPreferences controls = getSharedPreferences("nfsmw_controls", MODE_PRIVATE);
            boolean stretch = controls.getBoolean("stretch", true);
            optionsList.addView(optionRow("Aspect Ratio", "Display scaling mode",
                    stretch ? "Stretch to Fullscreen" : "Original 16:9", () -> {
                        controls.edit().putBoolean("stretch", !stretch).apply();
                        refreshOptions();
                    }));
        }
        if (audioList != null) {
            audioList.removeAllViews();
            for (GameOptions.Option option : GameOptions.AUDIO_SYSTEM) {
                String value = GameOptions.get(this, option.key);
                audioList.addView(optionRow(option.title, optionSummary(option.key), option.label(value), () -> chooseOption(option)));
            }
        }
    }

    private static String optionSummary(String key) {
        switch (key) {
            case GameOptions.RENDERER: return "Native Vulkan pipeline vs Compatibility mode";
            case "gpu_stability": return "Mali occlusions & sun flare stability";
            case GameOptions.RESOLUTION: return "Internal rendering resolution";
            case GameOptions.FPS: return "Target framerate cap";
            case "aa": return "Edge antialiasing filter";
            case "shadows": return "Shadow calculation frequency";
            case "car_reflections": return "Vehicle cubemap reflection detail";
            case "road_reflection": return "Wet road surface reflections";
            case "sky": return "Atmospheric sky shader glow";
            case "filter": return "Post-processing color grade";
            case "volume": return "Game audio master volume";
            case "audio_buffer": return "Audio queue buffer latency";
            case "language": return "In-game text and subtitles language";
            default: return "";
        }
    }

    private void chooseDriver() {
        GpuDrivers.ensureDefaultDrivers(this);
        java.util.List<GpuDrivers.Driver> drivers = GpuDrivers.list(this);
        String[] names = new String[drivers.size() + 1];
        names[0] = "System Driver (Mali G71 HAL)";
        int selected = 0;
        for (int i = 0; i < drivers.size(); ++i) {
            names[i + 1] = drivers.get(i).name;
            if (drivers.get(i).id.equals(GpuDrivers.selectedId(this))) selected = i + 1;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Select Vulkan Driver")
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    GpuDrivers.select(this, which == 0 ? "" : drivers.get(which - 1).id);
                    refreshOptions();
                    dialog.dismiss();
                })
                .setPositiveButton("Import Driver (.zip)", (dialog, which) -> {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                            .addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(picker, REQUEST_DRIVER_ZIP);
                })
                .setNegativeButton("Cancel", null);
        if (selected > 0) {
            builder.setNeutralButton("Delete Selected", (dialog, which) -> {
                GpuDrivers.Driver driver = GpuDrivers.selected(this);
                if (driver == null) return;
                try {
                    GpuDrivers.remove(this, driver);
                    refreshOptions();
                } catch (IOException error) {
                    reportError(error.getMessage());
                }
            });
        }
        builder.show();
    }

    private void probeDriver(boolean playAfter) {
        playAfterProbe = playAfter;
        launchGame.setEnabled(false);
        startActivityForResult(new Intent(this, GpuProbeActivity.class), REQUEST_DRIVER_PROBE);
    }

    private void showDriverReport(String report) {
        try {
            Diagnostics.acceptGpu(this, report);
            org.json.JSONObject gpu = new org.json.JSONObject(report);
            boolean failed = gpu.optBoolean("driverLoadFailed") || gpu.has("probeError");
            String text = "GPU: " + gpu.optString("gpu", "Unavailable")
                    + "\nVulkan: " + gpu.optString("vulkan", "Unavailable")
                    + "\nDescriptor Sets: " + gpu.optInt("maxBoundDescriptorSets")
                    + "\n" + (gpu.optBoolean("compatible") ? "Native renderer features available."
                            : "Missing features: " + gpu.optJSONArray("missing"));
            if (failed) text = "Could not probe driver. " + gpu.optString("probeError", "Failed to load Vulkan.");
            if (playAfterProbe && !failed) { playAfterProbe = false; playChecked(); return; }
            playAfterProbe = false;
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Vulkan Driver Report").setMessage(text)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Use System Driver", (dialog, which) -> {
                        GpuDrivers.select(this, ""); refreshOptions();
                    }).show();
        } catch (Exception error) { playAfterProbe = false; reportError("Invalid diagnostic report: " + error.getMessage()); }
    }

    private void chooseOption(GameOptions.Option option) {
        String current = GameOptions.get(this, option.key);
        int checked = 0;
        for (int i = 0; i < option.values.length; i++) {
            if (option.values[i].equals(current)) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(option.title)
                .setSingleChoiceItems(option.labels, checked, (dialog, which) -> {
                    GameOptions.set(this, option.key, option.values[which]);
                    dialog.dismiss();
                    refreshOptions();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View optionRow(String title, String summary, String value, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onClick.run());

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.addView(label(title, 14, 0xFFFFFFFF, true));
        if (summary != null && !summary.isEmpty()) {
            TextView sub = label(summary, 12, 0x88FFFFFF, false);
            sub.setPadding(0, dp(2), 0, 0);
            textCol.addView(sub);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView current = label(value + "  ›", 13, ACCENT, true);
        current.setGravity(Gravity.END);
        current.setPadding(dp(8), 0, 0, 0);
        row.addView(current);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void showChecks(File folder) {
        checksList.removeAllViews();
        addCheck("default.xex", new File(folder, "default.xex").isFile(), true);
        addCheck("NFS Directory", new File(folder, "NFS").isDirectory(), true);
        addCheck("Movies Directory", new File(folder, "Movies").isDirectory(), true);
        addCheck("Native Renderer Shaders", ShaderBuilder.hasLibrary(folder), false);
    }

    private void addCheck(String name, boolean ok, boolean required) {
        String mark = ok ? "✓  " : (required ? "✗  " : "!  ");
        String warning = ok || required ? "" : " · will be generated on PLAY";
        TextView row = label(mark + name + warning, 13,
                ok ? 0xFF7CD992 : (required ? 0xFFFF6B6B : 0xFFFFC857), false);
        row.setPadding(0, dp(2), 0, dp(2));
        checksList.addView(row);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC121A22);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);
        return card;
    }

    private Button actionButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(primary ? 0xFF1A1000 : Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 22 : 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(primary ? .12f : 0f);
        GradientDrawable bg = primary
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFFFFB300, ACCENT})
                : new GradientDrawable();
        if (!primary) {
            bg.setColor(0x22FFFFFF);
            bg.setStroke(dp(1), 0x55FFFFFF);
        }
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setLetterSpacing(.06f);
        }
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void selectGameFolder() {
        if (!hasStorageAccess()) {
            prepareSharedGameFolder();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        String saved = getPreferences(MODE_PRIVATE).getString(TREE_URI, null);
        if (saved != null && Build.VERSION.SDK_INT >= 26) {
            intent.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(saved));
        }
        startActivityForResult(intent, REQUEST_GAME_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DRIVER_PROBE) {
            launchGame.setEnabled(true);
            showDriverReport(resultCode == RESULT_OK && data != null ? data.getStringExtra("gpu_report")
                    : "{\"probeError\":\"Driver probe was cancelled or closed. Reverting to system driver.\",\"driverLoadFailed\":true}");
            return;
        }
        if (requestCode == REQUEST_DRIVER_ZIP) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri zip = data.getData();
            setImportStatus("Importing Vulkan driver…");
            importer.execute(() -> {
                try {
                    GpuDrivers.Driver driver = GpuDrivers.importZip(this, zip);
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        GpuDrivers.select(this, driver.id); refreshOptions();
                        setImportStatus("Driver imported: " + driver.name + ". Use Probe Driver to test.");
                    });
                } catch (Exception error) {
                    mainHandler.post(() -> reportError("Failed to import driver: " + error.getMessage()));
                }
            });
            return;
        }
        if (requestCode == REQUEST_SAVE_REPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) finishReportSave(data.getData());
            else pendingReport = null;
            return;
        }
        if (requestCode == REQUEST_STORAGE_ACCESS || requestCode == REQUEST_LEGACY_STORAGE) {
            if (hasStorageAccess()) prepareSharedGameFolder();
            else {
                selectFolder.setEnabled(true);
                setImportStatus("Grant all-files storage permission to access Internal Storage/" + GAME_FOLDER_NAME + ".");
            }
            return;
        }
        if (requestCode != REQUEST_GAME_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri tree = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(tree, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException error) {
            setImportStatus("Could not persist folder access permission: " + error.getMessage());
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString(TREE_URI, tree.toString()).apply();
        importGameFolder(tree);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LEGACY_STORAGE) {
            if (hasStorageAccess()) prepareSharedGameFolder();
            else {
                selectFolder.setEnabled(true);
                setImportStatus("Grant storage permission to access Internal Storage/" + GAME_FOLDER_NAME + ".");
            }
        }
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void prepareSharedGameFolder() {
        if (!hasStorageAccess()) {
            selectFolder.setEnabled(false);
            launchGame.setVisibility(View.GONE);
            setImportStatus("To use Internal Storage/" + GAME_FOLDER_NAME + ", please grant all-files access permission.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent settings = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(settings, REQUEST_STORAGE_ACCESS);
            } else {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_LEGACY_STORAGE);
            }
            return;
        }

        selectFolder.setEnabled(false);
        launchGame.setVisibility(View.GONE);
        setImportStatus("Scanning game files in internal storage…");
        importer.execute(() -> {
            File gameRoot = sharedGameRoot();
            File oldPrivateRoot = privateGameRoot();
            File parent = gameRoot.getParentFile();
            File staging = new File(parent, "." + GAME_FOLDER_NAME + ".importing");
            File previous = new File(parent, "." + GAME_FOLDER_NAME + ".previous");
            try {
                if (isValidGameFolder(gameRoot)) {
                    if (oldPrivateRoot.exists()) deleteRecursively(oldPrivateRoot);
                    mainHandler.post(() -> showSharedGameFolder(null));
                    return;
                }

                if (isValidGameFolder(oldPrivateRoot)) {
                    mainHandler.post(() -> setImportStatus("Moving game files to Internal Storage/" + GAME_FOLDER_NAME + "…"));
                    deleteRecursively(staging);
                    deleteRecursively(previous);
                    copyDirectory(oldPrivateRoot, staging, staging.getCanonicalFile(), new long[]{0});
                    if (!isValidGameFolder(staging)) throw new IOException("Copied game files failed integrity verification.");
                    installStagedGame(staging, gameRoot, previous);
                    deleteRecursively(oldPrivateRoot);
                    deleteRecursively(previous);
                    mainHandler.post(() -> showSharedGameFolder("Game files moved from app-private storage."));
                    return;
                }

                if (!gameRoot.mkdirs() && !gameRoot.isDirectory()) {
                    throw new IOException("Could not create " + gameRoot.getAbsolutePath());
                }
                mainHandler.post(() -> showSharedGameFolder(null));
            } catch (Exception error) {
                try { deleteRecursively(staging); } catch (IOException ignored) {}
                mainHandler.post(() -> {
                    setImportStatus("Could not prepare game directory: " + error.getMessage());
                    selectFolder.setEnabled(true);
                });
            }
        });
    }

    private File sharedGameRoot() {
        return new File(Environment.getExternalStorageDirectory(), GAME_FOLDER_NAME);
    }

    private File privateGameRoot() {
        return new File(new File(getFilesDir(), "nfsmw"), "game_root");
    }

    private static boolean isValidGameFolder(File folder) {
        return new File(folder, "default.xex").isFile() &&
                new File(folder, "NFS").isDirectory() && new File(folder, "Movies").isDirectory();
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

    private void importGameFolder(Uri tree) {
        selectFolder.setEnabled(false);
        launchGame.setVisibility(View.GONE);
        setImportStatus("Copying game files to Internal Storage/" + GAME_FOLDER_NAME + "…");
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
                    throw new IOException("The selected folder must contain default.xex, NFS, and Movies.");
                }
                installStagedGame(staging, gameRoot, previous);
                File oldPrivateRoot = privateGameRoot();
                if (oldPrivateRoot.exists()) deleteRecursively(oldPrivateRoot);
                deleteRecursively(previous);
                mainHandler.post(() -> {
                    showSharedGameFolder(null);
                    if ("nativo".equals(GameOptions.get(this, GameOptions.RENDERER)) &&
                            !ShaderBuilder.hasLibrary(sharedGameRoot())) buildShaders(false);
                });
            } catch (Exception error) {
                try { deleteRecursively(staging); } catch (IOException ignored) {}
                mainHandler.post(() -> setImportStatus("Import failed: " + error.getMessage()));
            } finally {
                mainHandler.post(() -> selectFolder.setEnabled(shaderBuilder == null));
            }
        });
    }

    private void copyDirectory(DocumentFile source, File destination, File safeRoot, long[] copiedBytes) throws IOException {
        if (!destination.getCanonicalFile().toPath().startsWith(safeRoot.toPath())) {
            throw new IOException("Game folder contains an invalid path.");
        }
        if (!destination.mkdirs() && !destination.isDirectory()) throw new IOException("Could not create " + destination);
        for (DocumentFile child : source.listFiles()) {
            String name = child.getName();
            if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
                    name.contains("/") || name.contains("\\")) {
                throw new IOException("Game folder contains an invalid file name.");
            }
            File target = new File(destination, name);
            if (child.isDirectory()) {
                copyDirectory(child, target, safeRoot, copiedBytes);
            } else if (child.isFile()) {
                File parent = target.getParentFile();
                if (parent == null || !parent.getCanonicalFile().toPath().startsWith(safeRoot.toPath())) {
                    throw new IOException("File path traversal attempt detected outside import directory.");
                }
                if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Could not create game subdirectory.");
                try (InputStream input = getContentResolver().openInputStream(child.getUri());
                     FileOutputStream output = new FileOutputStream(target)) {
                    if (input == null) throw new IOException("Could not read " + name);
                    byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        copiedBytes[0] += count;
                        if ((copiedBytes[0] & ((32L * 1024 * 1024) - 1)) < count) {
                            long total = copiedBytes[0];
                            mainHandler.post(() -> setImportStatus("Copied " + (total / (1024 * 1024)) + " MiB…"));
                        }
                    }
                    output.getFD().sync();
                }
            }
        }
    }

    private void copyDirectory(File source, File destination, File safeRoot, long[] copiedBytes) throws IOException {
        if (!destination.getCanonicalFile().toPath().startsWith(safeRoot.toPath())) {
            throw new IOException("Game folder contains an invalid path.");
        }
        if (!destination.mkdirs() && !destination.isDirectory()) throw new IOException("Could not create " + destination);
        File[] children = source.listFiles();
        if (children == null) throw new IOException("Could not read " + source.getAbsolutePath());
        byte[] buffer = new byte[1024 * 1024];
        for (File child : children) {
            File target = new File(destination, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, target, safeRoot, copiedBytes);
            } else if (child.isFile()) {
                try (InputStream input = new FileInputStream(child);
                     FileOutputStream output = new FileOutputStream(target)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        copiedBytes[0] += count;
                        if ((copiedBytes[0] & ((128L * 1024 * 1024) - 1)) < count) {
                            long total = copiedBytes[0];
                            mainHandler.post(() -> setImportStatus("Moved " + (total / (1024 * 1024)) + " MiB…"));
                        }
                    }
                    output.getFD().sync();
                }
            }
        }
    }

    private void showSharedGameFolder(String notice) {
        File gameRoot = sharedGameRoot();
        selectFolder.setEnabled(true);
        showChecks(gameRoot);
        if (isValidGameFolder(gameRoot)) {
            setImportStatus((notice == null ? "" : notice + "\n") + "Internal Storage/" + GAME_FOLDER_NAME);
            launchGame.setVisibility(View.VISIBLE);
            selectFolder.setText("Change Game Folder");
        } else {
            setImportStatus("Select your extracted game folder (containing default.xex, NFS, and Movies).\n" +
                    "Files will be placed in Internal Storage/" + GAME_FOLDER_NAME + ".");
            launchGame.setVisibility(View.GONE);
            selectFolder.setText("Select Game Folder");
        }
    }

    private void setImportStatus(String message) {
        if (importStatus != null) importStatus.setText(message);
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete()) throw new IOException("Could not delete " + file.getName());
    }

    @Override
    protected void onDestroy() {
        if (shaderBuilder != null) {
            shaderBuilder.cancel();
        }
        importer.shutdown();
        super.onDestroy();
    }
}
