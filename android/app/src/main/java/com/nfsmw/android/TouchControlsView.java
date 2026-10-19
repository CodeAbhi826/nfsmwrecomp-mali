package com.nfsmw.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen gamepad: a full Xbox 360 pad (both sticks, triggers, bumpers, D-pad, A/B/X/Y, Back and Start)
 * that the player can rearrange.
 *
 * Positions are stored as fractions of the view (centre x of the width, centre y of the height) and sizes as a
 * fraction of the height, so a layout keeps its shape on any screen. The state goes to the SDK's touch pad
 * (rex_sdl_set_touch_gamepad_state) only when it changes, and the view redraws only then.
 */
public final class TouchControlsView extends View {
    // SDL gamepad-button bits (1 << SDL_GAMEPAD_BUTTON_*). TouchControllerBridge.setState() converts
    // this mask to XInput bits itself, so sending XInput bits here scrambles the whole pad: START
    // arrived as BACK, A as D-pad down, the D-pad as A/B/X/Y, and "Press START" could never be
    // satisfied.
    static final int A = 0x0001, B = 0x0002, X = 0x0004, Y = 0x0008;      // SOUTH, EAST, WEST, NORTH
    static final int BACK = 0x0010, START = 0x0040;                       // GUIDE (0x20) unused
    static final int LTHUMB = 0x0080, RTHUMB = 0x0100;
    static final int LB = 0x0200, RB = 0x0400;
    static final int DPAD_UP = 0x0800, DPAD_DOWN = 0x1000, DPAD_LEFT = 0x2000, DPAD_RIGHT = 0x4000;

    private static final int BUTTON = 0, STICK = 1, DPAD = 2, PILL = 3, MENU = 4;
    private static final int TRIGGER_NONE = 0, TRIGGER_LEFT = 1, TRIGGER_RIGHT = 2;
    private static final String PREFS = "nfsmw_controls";
    boolean showFps = true;

    /** One control of the pad. */
    static final class Control {
        final String id;
        final int type;
        final String label;
        final String hint;
        final int mask;
        final int trigger;
        final int color;
        float defX, defY, defSize;
        final boolean defVisible;
        float x, y, size;
        boolean visible;

        // Play state.
        boolean pressed;
        int dpad;                  // D-pad bits pressed
        int pointer = -1;          // stick: the finger that holds it
        float originX, originY;    // stick: where that finger went down
        float knobX, knobY;        // stick: -1..1

        Control(String id, int type, String label, String hint, int mask, int trigger, int color,
                float x, float y, float size, boolean visible) {
            this.id = id;
            this.type = type;
            this.label = label;
            this.hint = hint;
            this.mask = mask;
            this.trigger = trigger;
            this.color = color;
            this.defX = x;
            this.defY = y;
            this.defSize = size;
            this.defVisible = visible;
            reset();
        }

        void reset() {
            x = defX;
            y = defY;
            size = defSize;
            visible = defVisible;
        }
    }

    /** What the activity shows around the pad: the editor toolbar and the settings dialog. */
    interface Host {
        void onEditModeChanged(boolean editing);
    }

    private final List<Control> controls = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final SharedPreferences prefs;
    private Host host;

    // Settings.
    float opacity = 0.75f;
    boolean haptics = true;
    boolean tiltSteering = false;
    float tiltSensitivity = 1.0f;   // 1 = full lock at 30 degrees
    boolean hideWithGamepad = true;
    boolean stretch = true;

    // State.
    private boolean editing;
    private boolean hiddenByGamepad;
    private Control selected;
    private float dragDX, dragDY;
    private float pinchStart, sizeAtPinch;
    private float tilt;             // -1..1 from the gravity sensor
    private int lastButtons = -1, lastLX, lastLY, lastRX, lastRY, lastLT = -1, lastRT = -1;

    public TouchControlsView(Context context) {
        super(context);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        setClickable(true);
        setHapticFeedbackEnabled(true);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setPathEffect(new DashPathEffect(new float[] {14f, 10f}, 0f));
        createControls();
        load();
    }

    void setHost(Host host) {
        this.host = host;
    }

    // Victor's authentic NFS Most Wanted touch control palette: transparent interior, crisp white stroke, olive/gold press
    private static final int COLOR_FILL = Color.TRANSPARENT;
    private static final int COLOR_BORDER = Color.argb(215, 255, 255, 255);     // 0xD7FFFFFF crisp bright white
    private static final int COLOR_PRESSED = Color.argb(190, 0x6F, 0x74, 0x32);    // 0xBE6F7432 authentic olive/gold highlight
    private static final int COLOR_DPAD_PRESS = Color.argb(180, 0x6F, 0x74, 0x32); // 0xB46F7432

    private static int applyAlpha(int color, float alpha) {
        int baseAlpha = (color >>> 24);
        int finalAlpha = Math.round(baseAlpha * alpha);
        return (color & 0x00FFFFFF) | (finalAlpha << 24);
    }

    // ---- Layout ------------------------------------------------------------------------------------------

    private void createControls() {
        final int grey = COLOR_FILL;
        // Triggers and Bumpers (Top): Pill shape
        add(new Control("lt", PILL, "LT", "", 0, TRIGGER_LEFT, grey, .07f, .18f, 1.0f, true));
        add(new Control("lb", PILL, "LB", "", LB, TRIGGER_NONE, grey, .20f, .18f, 1.0f, true));
        add(new Control("rb", PILL, "RB", "", RB, TRIGGER_NONE, grey, .80f, .10f, 1.0f, true));
        add(new Control("rt", PILL, "RT", "", 0, TRIGGER_RIGHT, grey, .93f, .10f, 1.0f, true));

        // Thumbstick Clicks (L3 / R3)
        add(new Control("l3", BUTTON, "L3", "", LTHUMB, TRIGGER_NONE, grey, .07f, .48f, 1.0f, true));
        add(new Control("r3", BUTTON, "R3", "", RTHUMB, TRIGGER_NONE, grey, .73f, .48f, 1.0f, true));

        // Left Stick (with "L" on knob) & Cross D-Pad
        add(new Control("ls", STICK, "L", "", 0, TRIGGER_NONE, grey, .16f, .73f, 1.0f, true));
        add(new Control("dpad", DPAD, "", "", 0, TRIGGER_NONE, grey, .32f, .72f, 1.0f, true));

        // Center: Back, Menu (Settings gear), Start
        add(new Control("back", PILL, "BACK", "", BACK, TRIGGER_NONE, grey, .44f, .79f, 1.0f, true));
        add(new Control("menu", MENU, "⚙", "", 0, TRIGGER_NONE, grey, .50f, .08f, 1.0f, true));
        add(new Control("start", PILL, "START", "", START, TRIGGER_NONE, grey, .55f, .79f, 1.0f, true));

        // Right Stick (with "R" on knob)
        add(new Control("rs", STICK, "R", "", 0, TRIGGER_NONE, grey, .67f, .77f, 1.0f, true));

        // Diamond A/B/X/Y Buttons (Right side)
        final float faceX = .85f;
        final float faceY = .68f;
        add(new Control("y", BUTTON, "Y", "", Y, TRIGGER_NONE, grey, faceX, faceY - .0756f, 1.0f, true));
        add(new Control("x", BUTTON, "X", "", X, TRIGGER_NONE, grey, faceX - .0425f, faceY, 1.0f, true));
        add(new Control("b", BUTTON, "B", "", B, TRIGGER_NONE, grey, faceX + .0425f, faceY, 1.0f, true));
        add(new Control("a", BUTTON, "A", "", A, TRIGGER_NONE, grey, faceX, faceY + .0756f, 1.0f, true));
    }

    private void add(Control c) {
        controls.add(c);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            float u = Math.min(w / 16f, h / 9f);
            float faceX = w * 0.85f;
            float faceY = h * 0.68f;
            float offset = u * 0.68f;
            for (Control c : controls) {
                if (c.id.equals("a")) { c.defX = faceX / w; c.defY = (faceY + offset) / h; }
                else if (c.id.equals("b")) { c.defX = (faceX + offset) / w; c.defY = faceY / h; }
                else if (c.id.equals("x")) { c.defX = (faceX - offset) / w; c.defY = faceY / h; }
                else if (c.id.equals("y")) { c.defX = faceX / w; c.defY = (faceY - offset) / h; }
            }
            if (!prefs.contains("layout")) {
                resetLayout();
            }
        }
    }

    private void load() {
        opacity = prefs.getFloat("opacity", opacity);
        haptics = prefs.getBoolean("haptics", haptics);
        tiltSteering = prefs.getBoolean("tilt", tiltSteering);
        tiltSensitivity = prefs.getFloat("tilt_sensitivity", tiltSensitivity);
        hideWithGamepad = prefs.getBoolean("hide_with_gamepad", hideWithGamepad);
        stretch = prefs.getBoolean("stretch", stretch);
        showFps = prefs.getBoolean("show_fps", true);
        int layoutVer = prefs.getInt("layout_version", 0);
        if (layoutVer < 3) {
            resetLayout();
            save();
            return;
        }
        String json = prefs.getString("layout", null);
        if (json == null) {
            return;
        }
        try {
            JSONObject all = new JSONObject(json);
            for (Control c : controls) {
                JSONObject o = all.optJSONObject(c.id);
                if (o != null) {
                    c.x = clamp((float) o.optDouble("x", c.defX), 0f, 1f);
                    c.y = clamp((float) o.optDouble("y", c.defY), 0f, 1f);
                    c.size = clamp((float) o.optDouble("s", c.defSize), 0.5f, 2.5f);
                    c.visible = o.optBoolean("v", c.defVisible);
                }
            }
        } catch (JSONException ignored) {
            // A damaged layout: keep the defaults.
        }
    }

    void save() {
        JSONObject all = new JSONObject();
        try {
            for (Control c : controls) {
                JSONObject o = new JSONObject();
                o.put("x", c.x).put("y", c.y).put("s", c.size).put("v", c.visible);
                all.put(c.id, o);
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit()
                .putInt("layout_version", 3)
                .putString("layout", all.toString())
                .putFloat("opacity", opacity)
                .putBoolean("haptics", haptics)
                .putBoolean("tilt", tiltSteering)
                .putFloat("tilt_sensitivity", tiltSensitivity)
                .putBoolean("hide_with_gamepad", hideWithGamepad)
                .putBoolean("stretch", stretch)
                .putBoolean("show_fps", showFps)
                .apply();
    }

    void resetLayout() {
        int w = getWidth(), h = getHeight();
        float u = unit();
        float faceX = (w > 0 ? w * 0.85f : 0.85f * 1280f);
        float faceY = (h > 0 ? h * 0.68f : 0.68f * 720f);
        float offset = u * 0.68f;
        for (Control c : controls) {
            c.reset();
            if (w > 0 && h > 0) {
                if (c.id.equals("a")) { c.x = faceX / w; c.y = (faceY + offset) / h; }
                else if (c.id.equals("b")) { c.x = (faceX + offset) / w; c.y = faceY / h; }
                else if (c.id.equals("x")) { c.x = (faceX - offset) / w; c.y = faceY / h; }
                else if (c.id.equals("y")) { c.x = faceX / w; c.y = (faceY - offset) / h; }
            }
        }
        selected = null;
        invalidate();
    }

    // ---- Editor ------------------------------------------------------------------------------------------

    boolean isEditing() {
        return editing;
    }

    void setEditing(boolean on) {
        if (editing == on) {
            return;
        }
        editing = on;
        selected = null;
        releaseAll();
        if (!on) {
            save();
        }
        if (host != null) {
            host.onEditModeChanged(on);
        }
        invalidate();
    }

    /** The control the editor toolbar acts on, or null. */
    Control selected() {
        return selected;
    }

    void resizeSelected(float factor) {
        if (selected != null) {
            selected.size = clamp(selected.size * factor, 0.5f, 2.5f);
            invalidate();
        }
    }

    List<Control> controls() {
        return controls;
    }

    void resizeAll(float scale) {
        for (Control c : controls) {
            c.size = clamp(scale, 0.5f, 2.5f);
        }
        invalidate();
    }

    void toggleSelectedVisible() {
        if (selected != null && selected.type != MENU) {
            selected.visible = !selected.visible;
            invalidate();
        }
    }

    // ---- Gamepad and sensors -----------------------------------------------------------------------------

    /** A physical gamepad was used or connected: the touch pad gets out of the way. */
    void onGamepadActive() {
        if (hideWithGamepad && !hiddenByGamepad && !editing) {
            hiddenByGamepad = true;
            releaseAll();
            invalidate();
        }
    }

    /** The last physical gamepad went away. */
    void onGamepadGone() {
        if (hiddenByGamepad) {
            hiddenByGamepad = false;
            invalidate();
        }
    }

    /** Tilt from the gravity sensor, -1 (left) to 1 (right), already corrected for the screen rotation. */
    void setTilt(float value) {
        tilt = value;
        if (tiltSteering && !editing && !hiddenByGamepad) {
            send();
        }
    }

    // ---- Input -------------------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editing) {
            return editTouch(event);
        }
        if (hiddenByGamepad) {
            // A touch brings the pad back.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                hiddenByGamepad = false;
                invalidate();
            }
            return true;
        }
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll();
            return true;
        }
        final int liftedIndex = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                ? event.getActionIndex() : -1;
        final int buttonsBefore = buttonState();

        // Sticks hold on to the finger that grabbed them.
        for (Control c : controls) {
            if (c.type != STICK || c.pointer < 0) {
                continue;
            }
            int index = event.findPointerIndex(c.pointer);
            if (index < 0 || index == liftedIndex) {
                c.pointer = -1;
                c.knobX = c.knobY = 0f;
                continue;
            }
            float radius = radius(c);
            c.knobX = clamp((event.getX(index) - c.originX) / radius, -1f, 1f);
            c.knobY = clamp((event.getY(index) - c.originY) / radius, -1f, 1f);
            float length = (float) Math.hypot(c.knobX, c.knobY);
            if (length > 1f) {
                c.knobX /= length;
                c.knobY /= length;
            }
        }
        // A new finger on a stick grabs it; the stick centres itself under the finger.
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            float px = event.getX(index), py = event.getY(index);
            Control stick = hitAny(px, py);
            if (stick != null && stick.type == STICK && stick.pointer < 0) {
                stick.pointer = event.getPointerId(index);
                stick.originX = px;
                stick.originY = py;
                stick.knobX = stick.knobY = 0f;
            }
        }
        // Buttons, pedals and the D-pad follow the fingers, so a thumb can slide from gas to brake.
        for (Control c : controls) {
            c.pressed = false;
            c.dpad = 0;
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == liftedIndex || holdsStick(event.getPointerId(i))) {
                continue;
            }
            float px = event.getX(i), py = event.getY(i);
            Control c = hitAny(px, py);
            if (c == null || c.type == STICK) {
                continue;
            }
            if (c.type == DPAD) {
                c.dpad |= dpadBits(c, px, py);
            } else {
                c.pressed = true;
            }
        }
        // The gear opens the editor when the finger lifts on it.
        if (liftedIndex >= 0 && hitAny(event.getX(liftedIndex), event.getY(liftedIndex)) == menuControl()) {
            releaseAll();
            setEditing(true);
            return true;
        }
        final int buttonsAfter = buttonState();
        if (haptics && (buttonsAfter & ~buttonsBefore) != 0) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        send();
        return true;
    }

    private boolean editTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            selected = hitAny(event.getX(), event.getY(), true);
            if (selected != null) {
                dragDX = selected.x * getWidth() - event.getX();
                dragDY = selected.y * getHeight() - event.getY();
            }
            invalidate();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN && selected != null && event.getPointerCount() == 2) {
            pinchStart = spacing(event);
            sizeAtPinch = selected.size;
        } else if (action == MotionEvent.ACTION_MOVE && selected != null) {
            if (event.getPointerCount() >= 2 && pinchStart > 0f) {
                selected.size = clamp(sizeAtPinch * spacing(event) / pinchStart, .06f, .8f);
            } else if (event.getPointerCount() == 1) {
                selected.x = clamp((event.getX() + dragDX) / getWidth(), 0f, 1f);
                selected.y = clamp((event.getY() + dragDY) / getHeight(), 0f, 1f);
            }
            invalidate();
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            pinchStart = 0f;
            if (selected != null) {
                // The finger left on screen keeps dragging from where it is now.
                int keep = event.getActionIndex() == 0 ? 1 : 0;
                dragDX = selected.x * getWidth() - event.getX(keep);
                dragDY = selected.y * getHeight() - event.getY(keep);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pinchStart = 0f;
        }
        return true;
    }

    private static float spacing(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private boolean holdsStick(int pointerId) {
        for (Control c : controls) {
            if (c.type == STICK && c.pointer == pointerId) {
                return true;
            }
        }
        return false;
    }

    private Control menuControl() {
        for (Control c : controls) {
            if (c.type == MENU) {
                return c;
            }
        }
        return null;
    }

    private int dpadBits(Control c, float px, float py) {
        float dx = px - c.x * getWidth(), dy = py - c.y * getHeight();
        if (Math.hypot(dx, dy) < radius(c) * .2f) {
            return 0;
        }
        double angle = Math.toDegrees(Math.atan2(-dy, dx));  // 0 = right, 90 = up
        if (angle < 0) {
            angle += 360;
        }
        int bits = 0;
        // 67.5-degree sectors per direction: diagonals press two.
        if (angle < 67.5 || angle > 292.5) bits |= DPAD_RIGHT;
        if (angle > 22.5 && angle < 157.5) bits |= DPAD_UP;
        if (angle > 112.5 && angle < 247.5) bits |= DPAD_LEFT;
        if (angle > 202.5 && angle < 337.5) bits |= DPAD_DOWN;
        return bits;
    }

    private Control hitAny(float px, float py) {
        return hitAny(px, py, false);
    }

    /** The control under a point; the nearest one when several overlap. In the editor, hidden ones too. */
    private Control hitAny(float px, float py, boolean includeHidden) {
        Control best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Control c : controls) {
            if ((!c.visible && !includeHidden) || !contains(c, px, py, c.type == STICK ? 1.15f : 1.08f)) {
                continue;
            }
            double d = Math.hypot(px - c.x * getWidth(), py - c.y * getHeight()) / radius(c);
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        return best;
    }

    private boolean contains(Control c, float px, float py, float slack) {
        float cx = c.x * getWidth(), cy = c.y * getHeight();
        if (c.type == PILL) {
            float halfW = pillWidth(c) * .5f * slack, halfH = pillHeight(c) * .5f * slack;
            return Math.abs(px - cx) <= halfW && Math.abs(py - cy) <= halfH;
        }
        if (c.type == DPAD) {
            float r = radius(c) * slack;
            return Math.abs(px - cx) <= r && Math.abs(py - cy) <= r;
        }
        return Math.hypot(px - cx, py - cy) <= radius(c) * slack;
    }

    private float unit() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return 80f;
        return Math.min(w / 16f, h / 9f);
    }

    private float radius(Control c) {
        float u = unit() * c.size;
        switch (c.type) {
            case STICK:
                return u * 0.78f;
            case DPAD:
                return u * 0.98f;
            case BUTTON:
                if (c.id.equals("l3") || c.id.equals("r3")) {
                    return u * 0.38f * 0.82f;
                }
                return u * 0.38f;
            case MENU:
                return u * 0.38f;
            default:
                return u * 0.38f;
        }
    }

    private float pillWidth(Control c) {
        float u = unit() * c.size;
        if (c.id.equals("back")) return u * 0.82f;
        if (c.id.equals("start")) return u * 0.92f;
        if (c.id.equals("menu")) return u * 0.80f;
        return u * 1.25f;
    }

    private float pillHeight(Control c) {
        float u = unit() * c.size;
        if (c.id.equals("back") || c.id.equals("start") || c.id.equals("menu")) return u * 0.45f;
        return u * 0.55f;
    }

    private void releaseAll() {
        for (Control c : controls) {
            c.pressed = false;
            c.dpad = 0;
            c.pointer = -1;
            c.knobX = c.knobY = 0f;
        }
        send();
        invalidate();
    }

    private int buttonState() {
        int buttons = 0;
        for (Control c : controls) {
            if ((c.type == BUTTON || c.type == PILL) && c.pressed && c.mask != 0) {
                buttons |= c.mask;
            } else if (c.type == DPAD) {
                buttons |= c.dpad;
            }
        }
        return buttons;
    }

    /** Sends the pad to the game if it changed, and redraws. */
    private void send() {
        boolean active = !editing && !hiddenByGamepad;
        int buttons = active ? buttonState() : 0;
        int lx = 0, ly = 0, rx = 0, ry = 0, lt = 0, rt = 0;
        boolean steeringHeld = false;
        if (active) {
            for (Control c : controls) {
                if (c.type == STICK && c.id.equals("ls")) {
                    steeringHeld = c.pointer >= 0;
                    lx = axis(c.knobX);
                    ly = axis(-c.knobY);  // XInput: up is positive
                } else if (c.type == STICK) {
                    rx = axis(c.knobX);
                    ry = axis(-c.knobY);
                } else if (c.pressed) {
                    if (c.trigger == TRIGGER_LEFT) lt = 255;
                    else if (c.trigger == TRIGGER_RIGHT) rt = 255;
                }
            }
            // Touch steering wins while held; releasing it resumes tilt steering.
            if (tiltSteering && !steeringHeld) {
                lx = axis(clamp(tilt * tiltSensitivity, -1f, 1f));
            }
        }
        if (buttons == lastButtons && lx == lastLX && ly == lastLY && rx == lastRX && ry == lastRY &&
                lt == lastLT && rt == lastRT) {
            return;
        }
        lastButtons = buttons;
        lastLX = lx;
        lastLY = ly;
        lastRX = rx;
        lastRY = ry;
        lastLT = lt;
        lastRT = rt;
        io.github.nfsmwrecomp.TouchControllerBridge.trySetState(
                buttons,
                clamp(lx / 32767f, -1f, 1f),
                clamp(ly / 32767f, -1f, 1f),
                clamp(rx / 32767f, -1f, 1f),
                clamp(ry / 32767f, -1f, 1f),
                clamp(lt / 255f, 0f, 1f),
                clamp(rt / 255f, 0f, 1f)
        );
        invalidate();
    }

    private static int axis(float v) {
        return Math.round(clamp(v, -1f, 1f) * 32767f);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---- Drawing -----------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (hiddenByGamepad && !editing) {
            return;
        }
        if (editing) {
            canvas.drawColor(0x66000000);
        }
        for (Control c : controls) {
            if (!c.visible && !editing) {
                continue;
            }
            float alpha = c.visible ? (editing ? 1f : opacity) : .35f;
            drawControl(canvas, c, alpha);
            if (editing) {
                float cx = c.x * getWidth(), cy = c.y * getHeight();
                dashed.setStrokeWidth(3f);
                dashed.setColor(c == selected ? 0xFFFFC107 : 0x88FFFFFF);
                if (c.type == PILL) {
                    float hw = pillWidth(c) * .5f + 8f, hh = pillHeight(c) * .5f + 8f;
                    canvas.drawRoundRect(new RectF(cx - hw, cy - hh, cx + hw, cy + hh), hh, hh, dashed);
                } else {
                    canvas.drawCircle(cx, cy, radius(c) + 8f, dashed);
                }
                if (!c.visible) {
                    text.setColor(0xFFFFFFFF);
                    text.setTextSize(Math.max(22f, radius(c) * .28f));
                    canvas.drawText("HIDDEN", cx, cy + radius(c) + text.getTextSize() + 6f, text);
                }
            }
        }
    }

    private void drawControl(Canvas canvas, Control c, float alpha) {
        final float cx = c.x * getWidth(), cy = c.y * getHeight(), r = radius(c);
        final int a = Math.round(alpha * 255f);
        switch (c.type) {
            case STICK: {
                boolean held = c.pointer >= 0;
                float bx = held ? c.originX : cx, by = held ? c.originY : cy;
                boolean tiltMode = c.id.equals("ls") && tiltSteering && !editing && !held;
                stroke.setStrokeWidth(2.0f);
                stroke.setColor(applyAlpha(COLOR_BORDER, alpha));
                canvas.drawCircle(bx, by, r, stroke);

                float knobR = r * .43f;
                float kx = bx + (tiltMode ? clamp(tilt * tiltSensitivity, -1f, 1f) : c.knobX) * (r - knobR);
                float ky = by + (tiltMode ? 0f : c.knobY) * (r - knobR);
                if (held) {
                    fill.setColor(applyAlpha(COLOR_PRESSED, alpha));
                    canvas.drawCircle(kx, ky, knobR, fill);
                }
                stroke.setColor(applyAlpha(held ? COLOR_PRESSED : COLOR_BORDER, alpha));
                canvas.drawCircle(kx, ky, knobR, stroke);
                label(canvas, tiltMode ? "TILT" : c.label, kx, ky + knobR * .35f, knobR * .72f, a);
                break;
            }
            case DPAD: {
                float arm = r * .36f;
                float cr = 6.0f;
                stroke.setStrokeWidth(2.0f);
                stroke.setColor(applyAlpha(COLOR_BORDER, alpha));

                rect.set(cx - arm, cy - r, cx + arm, cy + r);
                canvas.drawRoundRect(rect, cr, cr, stroke);

                rect.set(cx - r, cy - arm, cx + r, cy + arm);
                canvas.drawRoundRect(rect, cr, cr, stroke);

                if ((c.dpad & DPAD_UP) != 0) {
                    fill.setColor(applyAlpha(COLOR_DPAD_PRESS, alpha));
                    rect.set(cx - arm, cy - r, cx + arm, cy - arm);
                    canvas.drawRoundRect(rect, cr, cr, fill);
                }
                if ((c.dpad & DPAD_DOWN) != 0) {
                    fill.setColor(applyAlpha(COLOR_DPAD_PRESS, alpha));
                    rect.set(cx - arm, cy + arm, cx + arm, cy + r);
                    canvas.drawRoundRect(rect, cr, cr, fill);
                }
                if ((c.dpad & DPAD_LEFT) != 0) {
                    fill.setColor(applyAlpha(COLOR_DPAD_PRESS, alpha));
                    rect.set(cx - r, cy - arm, cx - arm, cy + arm);
                    canvas.drawRoundRect(rect, cr, cr, fill);
                }
                if ((c.dpad & DPAD_RIGHT) != 0) {
                    fill.setColor(applyAlpha(COLOR_DPAD_PRESS, alpha));
                    rect.set(cx + arm, cy - arm, cx + r, cy + arm);
                    canvas.drawRoundRect(rect, cr, cr, fill);
                }
                break;
            }
            case PILL: {
                float hw = pillWidth(c) * .5f, hh = pillHeight(c) * .5f;
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
                float corner = hh;
                if (c.pressed) {
                    fill.setColor(applyAlpha(COLOR_PRESSED, alpha));
                    canvas.drawRoundRect(rect, corner, corner, fill);
                }
                stroke.setStrokeWidth(2.0f);
                stroke.setColor(applyAlpha(c.pressed ? COLOR_PRESSED : COLOR_BORDER, alpha));
                canvas.drawRoundRect(rect, corner, corner, stroke);
                label(canvas, c.label, cx, cy + hh * .35f, hh * .55f, a);
                break;
            }
            default: {
                if (c.pressed) {
                    fill.setColor(applyAlpha(COLOR_PRESSED, alpha));
                    canvas.drawCircle(cx, cy, r, fill);
                }
                stroke.setStrokeWidth(2.0f);
                stroke.setColor(applyAlpha(c.pressed ? COLOR_PRESSED : COLOR_BORDER, alpha));
                canvas.drawCircle(cx, cy, r, stroke);
                float textSize = (c.id.equals("l3") || c.id.equals("r3")) ? r * .65f : r * .75f;
                label(canvas, c.label, cx, cy + r * .35f, textSize, a);
                break;
            }
        }
    }

    private void drawArrow(Canvas canvas, float x, float y, float size, float rotation, boolean on, int a) {
        canvas.save();
        canvas.rotate(rotation, x, y);
        fill.setColor(withAlpha(on ? 0xFFFF9A32 : 0xFFEAF2FA, a * (on ? 240 : 170) / 255));
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x, y - size * .5f);
        p.lineTo(x + size * .5f, y + size * .35f);
        p.lineTo(x - size * .5f, y + size * .35f);
        p.close();
        canvas.drawPath(p, fill);
        canvas.restore();
    }

    private void label(Canvas canvas, String s, float x, float baseline, float size, int a) {
        if (s == null || s.isEmpty()) {
            return;
        }
        text.setTextSize(size);
        text.setColor(withAlpha(0xFFFFFFFF, a));
        text.setShadowLayer(size * .15f, 0f, size * .05f, withAlpha(0xFF000000, a));
        canvas.drawText(s, x, baseline, text);
        text.clearShadowLayer();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
