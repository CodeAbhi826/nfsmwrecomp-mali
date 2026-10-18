package com.nfsmw.android;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 1:1 Fullscreen Landscape Touch Controls Layout Editor.
 * Matches the reference layout with Size/Opacity steppers, Preset modes, Reset, Cancel, Done,
 * and live drag-and-drop repositioning and scaling of Xbox 360 virtual gamepad buttons.
 */
public final class TouchControlsEditorActivity extends Activity {
    private static final int ACCENT = 0xFFE8A13C;
    private static final String PREFS = "nfsmw_controls";

    private TouchControlsView controlsView;
    private TextView lblOpacity;
    private TextView lblSize;
    private Button btnMode;
    private int presetIndex = 0;
    private static final String[] PRESET_NAMES = {"CONTROLS: DEFAULT", "CONTROLS: COMPACT"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0C1014);

        // Screen boundary outline
        View boundary = new View(this);
        GradientDrawable boundBg = new GradientDrawable();
        boundBg.setColor(Color.TRANSPARENT);
        boundBg.setStroke(dp(1), 0x77E8A13C);
        boundBg.setCornerRadius(dp(8));
        boundary.setBackground(boundBg);
        FrameLayout.LayoutParams boundLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        boundLp.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(boundary, boundLp);

        // Main touch controls canvas in edit mode
        controlsView = new TouchControlsView(this);
        controlsView.setEditing(true);
        root.addView(controlsView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Top Toolbar
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setGravity(Gravity.CENTER_HORIZONTAL);
        toolbar.setPadding(0, dp(10), 0, 0);

        // Row 1: Size & Opacity Adjusters
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        row1.addView(createPillButton("-", () -> {
            controlsView.resizeSelected(0.9f);
            updateLabels();
        }));
        lblSize = createPillLabel("SIZE");
        row1.addView(lblSize);
        row1.addView(createPillButton("+", () -> {
            controlsView.resizeSelected(1.1f);
            updateLabels();
        }));

        View spacer = new View(this);
        row1.addView(spacer, new LinearLayout.LayoutParams(dp(16), dp(1)));

        row1.addView(createPillButton("-", () -> {
            controlsView.opacity = Math.max(0.1f, controlsView.opacity - 0.1f);
            controlsView.invalidate();
            updateLabels();
        }));
        lblOpacity = createPillLabel("OPACITY " + Math.round(controlsView.opacity * 100) + " %");
        row1.addView(lblOpacity);
        row1.addView(createPillButton("+", () -> {
            controlsView.opacity = Math.min(1.0f, controlsView.opacity + 0.1f);
            controlsView.invalidate();
            updateLabels();
        }));

        toolbar.addView(row1);

        // Row 2: Mode, Reset, Cancel, Done
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(6);
        row2.setLayoutParams(row2Lp);

        btnMode = createActionButton(PRESET_NAMES[0], () -> {
            presetIndex = (presetIndex + 1) % PRESET_NAMES.length;
            btnMode.setText(PRESET_NAMES[presetIndex]);
            applyPreset(presetIndex);
        });
        row2.addView(btnMode);

        Button btnReset = createActionButton("RESET", () -> {
            controlsView.resetLayout();
            updateLabels();
            Toast.makeText(this, "Controls layout reset", Toast.LENGTH_SHORT).show();
        });
        row2.addView(btnReset);

        Button btnCancel = createActionButton("CANCEL", () -> finish());
        row2.addView(btnCancel);

        Button btnDone = createActionButton("DONE", () -> {
            controlsView.save();
            Toast.makeText(this, "✔ Touch controls saved!", Toast.LENGTH_SHORT).show();
            finish();
        });
        row2.addView(btnDone);

        toolbar.addView(row2);

        // Row 3: Hint pill
        TextView hint = new TextView(this);
        hint.setText("DRAG BUTTONS TO REARRANGE · TAP TO SELECT");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setTextColor(0xFFD0D0D0);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setPadding(dp(14), dp(3), dp(14), dp(3));
        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setColor(0xDD141820);
        hintBg.setCornerRadius(dp(12));
        hintBg.setStroke(dp(1), 0x33FFFFFF);
        hint.setBackground(hintBg);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        toolbar.addView(hint, hintLp);

        root.addView(toolbar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void applyPreset(int index) {
        controlsView.resetLayout();
        if (index == 1) { // COMPACT
            controlsView.resizeAll(0.85f);
        }
        updateLabels();
        Toast.makeText(this, "Preset applied: " + PRESET_NAMES[index], Toast.LENGTH_SHORT).show();
    }

    private void updateLabels() {
        if (lblOpacity != null) {
            lblOpacity.setText("OPACITY " + Math.round(controlsView.opacity * 100) + " %");
        }
        if (lblSize != null) {
            TouchControlsView.Control sel = controlsView.selected();
            lblSize.setText(sel != null && sel.label != null && !sel.label.isEmpty()
                    ? sel.label + " SIZE" : "SIZE");
        }
    }

    private Button createPillButton(String text, Runnable onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x22E8A13C);
        bg.setStroke(dp(1), 0xAAE8A13C);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(24));
        b.setLayoutParams(lp);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private TextView createPillLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.05f);
        t.setPadding(dp(8), 0, dp(8), 0);
        return t;
    }

    private Button createActionButton(String text, Runnable onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x22E8A13C);
        bg.setStroke(dp(1), 0xAAE8A13C);
        bg.setCornerRadius(dp(13));
        b.setBackground(bg);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(26));
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }
}
