package com.aure.totk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Toggleable, freely movable, freely scalable, shape-changing on-screen Switch controls for TotK Native.
 * Uses TYPE_APPLICATION_PANEL sub-windows (no extra permissions) so controls composite ABOVE NativeActivity's
 * Vulkan / AHB child SurfaceControl (totk-ahb-ui). Persists per-button layout in SharedPreferences. */
final class TouchOverlay {
    static final int SHAPE_CIRCLE = 0;
    static final int SHAPE_SQUARE = 1;
    static final int SHAPE_PILL = 2;
    static final int SHAPE_OCTAGON = 3;
    private static final String[] SHAPE_NAMES = {"Circle", "Square", "Pill", "Octagon"};

    private static final String TAG = "TotkTouchUI";
    private static final String PREFS = "totk_touch_ui";
    private static final float MIN_SCALE = 0.50f;
    private static final float MAX_SCALE = 2.20f;

    static final class Control {
        final String id, label;
        final int bit;       // >= 0 for Switch button bit, -1 for analog stick
        final int stick;     // 0 = left stick, 1 = right stick, -1 = button
        final float defX, defY, defScale;
        final int defShape;
        float x, y, scale;   // x, y normalized 0..1 of overlay width/height
        int shape;
        boolean visible = true;
        boolean pressed;
        float knobX, knobY;  // -1..1 for analog sticks

        Control(String id, String label, int bit, int stick, float defX, float defY, float defScale, int defShape) {
            this.id = id; this.label = label; this.bit = bit; this.stick = stick;
            this.defX = defX; this.defY = defY; this.defScale = defScale; this.defShape = defShape;
            reset();
        }
        void reset() {
            x = defX; y = defY; scale = defScale; shape = defShape; visible = true;
            pressed = false; knobX = 0f; knobY = 0f;
        }
    }

    private final Activity activity;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final float dp;

    private final FrameLayout overlayRoot;
    private final OverlayCanvas canvasView;
    private final LinearLayout editBar;
    private final TextView togglePill;
    private final WindowManager.LayoutParams overlayParams;
    private final WindowManager.LayoutParams pillParams;

    private TextView selectedLabel;
    private TextView shapeBtn;
    private TextView hideBtn;
    private SeekBar scaleSeek;
    private SeekBar alphaSeek;

    private final List<Control> controls = new ArrayList<>();
    private boolean enabled;
    private boolean editMode;
    private int opacityPct = 65;
    private Control selected;

    private boolean destroyed;
    private boolean pillAttached;
    private boolean overlayAttached;

    // Draggable toggle pill state
    private float pillDownX, pillDownY;
    private int pillStartX, pillStartY;
    private boolean pillMoved;

    TouchOverlay(Activity activity) {
        this.activity = activity;
        this.wm = activity.getWindowManager();
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.dp = activity.getResources().getDisplayMetrics().density;

        initControls();
        loadPrefs();

        overlayRoot = new FrameLayout(activity);
        canvasView = new OverlayCanvas(activity);
        overlayRoot.addView(canvasView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        editBar = buildEditBar();
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        barLp.topMargin = Math.round(12 * dp);
        overlayRoot.addView(editBar, barLp);

        togglePill = buildTogglePill();

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        pillParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        pillParams.gravity = Gravity.TOP | Gravity.START;
        pillParams.x = Math.round(28 * dp);
        pillParams.y = Math.round(18 * dp);

        // Respect TOTK_TOUCH_UI=0 (from S25+ Tweaks & QoL) to hide even the UI pill for pure controller/Xreal play.
        if ("0".equals(readEnvKey(activity, "TOTK_TOUCH_UI"))) {
            destroyed = true;
            return;
        }
        // Attach sub-windows once the Activity's DecorView has a valid window token.
        View decor = activity.getWindow().getDecorView();
        decor.post(this::attachWhenReady);
    }

    private void attachWhenReady() {
        if (destroyed || activity.isFinishing()) return;
        View decor = activity.getWindow().getDecorView();
        IBinder token = decor != null ? decor.getWindowToken() : null;
        if (token == null) {
            if (decor != null) decor.postDelayed(this::attachWhenReady, 150);
            return;
        }
        overlayParams.token = token;
        pillParams.token = token;
        syncUiState();
    }

    private void initControls() {
        // Switch bit layout matches frontend_android.c:
        // SW_A=0, SW_B=1, SW_X=2, SW_Y=3, SW_LS=4, SW_RS=5, SW_L=6, SW_R=7, SW_ZL=8, SW_ZR=9,
        // SW_PLUS=10, SW_MINUS=11, SW_LEFT=12, SW_UP=13, SW_RIGHT=14, SW_DOWN=15
        controls.add(new Control("ls", "LS", -1, 0, 0.14f, 0.72f, 1.0f, SHAPE_CIRCLE));
        controls.add(new Control("rs", "RS", -1, 1, 0.76f, 0.76f, 1.0f, SHAPE_CIRCLE));

        // Face buttons (Nintendo diamond layout)
        controls.add(new Control("a", "A", 0, -1, 0.93f, 0.58f, 1.0f, SHAPE_CIRCLE));
        controls.add(new Control("b", "B", 1, -1, 0.87f, 0.70f, 1.0f, SHAPE_CIRCLE));
        controls.add(new Control("x", "X", 2, -1, 0.87f, 0.46f, 1.0f, SHAPE_CIRCLE));
        controls.add(new Control("y", "Y", 3, -1, 0.81f, 0.58f, 1.0f, SHAPE_CIRCLE));

        // D-Pad
        controls.add(new Control("du", "▲", 13, -1, 0.25f, 0.45f, 0.90f, SHAPE_SQUARE));
        controls.add(new Control("dd", "▼", 15, -1, 0.25f, 0.65f, 0.90f, SHAPE_SQUARE));
        controls.add(new Control("dl", "◀", 12, -1, 0.19f, 0.55f, 0.90f, SHAPE_SQUARE));
        controls.add(new Control("dr", "▶", 14, -1, 0.31f, 0.55f, 0.90f, SHAPE_SQUARE));

        // Shoulders & Triggers (Pill default)
        controls.add(new Control("zl", "ZL", 8, -1, 0.08f, 0.20f, 1.0f, SHAPE_PILL));
        controls.add(new Control("l",  "L",  6, -1, 0.08f, 0.33f, 1.0f, SHAPE_PILL));
        controls.add(new Control("zr", "ZR", 9, -1, 0.92f, 0.20f, 1.0f, SHAPE_PILL));
        controls.add(new Control("r",  "R",  7, -1, 0.92f, 0.33f, 1.0f, SHAPE_PILL));

        // System & Stick clicks
        controls.add(new Control("minus", "−",  11, -1, 0.42f, 0.88f, 0.85f, SHAPE_PILL));
        controls.add(new Control("plus",  "+",  10, -1, 0.58f, 0.88f, 0.85f, SHAPE_PILL));
        controls.add(new Control("l3",    "L3",  4, -1, 0.33f, 0.88f, 0.80f, SHAPE_CIRCLE));
        controls.add(new Control("r3",    "R3",  5, -1, 0.67f, 0.88f, 0.80f, SHAPE_CIRCLE));
    }

    private void loadPrefs() {
        enabled = prefs.getBoolean("enabled", false);
        opacityPct = clampInt(prefs.getInt("opacity", 65), 20, 100);
        for (Control c : controls) {
            c.x = clamp(prefs.getFloat(c.id + "_x", c.defX), 0.03f, 0.97f);
            c.y = clamp(prefs.getFloat(c.id + "_y", c.defY), 0.05f, 0.95f);
            c.scale = clamp(prefs.getFloat(c.id + "_s", c.defScale), MIN_SCALE, MAX_SCALE);
            c.shape = clampInt(prefs.getInt(c.id + "_sh", c.defShape), SHAPE_CIRCLE, SHAPE_OCTAGON);
            c.visible = prefs.getBoolean(c.id + "_v", true);
        }
    }

    private void savePrefs() {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean("enabled", enabled);
        ed.putInt("opacity", opacityPct);
        for (Control c : controls) {
            ed.putFloat(c.id + "_x", c.x);
            ed.putFloat(c.id + "_y", c.y);
            ed.putFloat(c.id + "_s", c.scale);
            ed.putInt(c.id + "_sh", c.shape);
            ed.putBoolean(c.id + "_v", c.visible);
        }
        ed.apply();
    }

    private TextView buildTogglePill() {
        TextView tv = makeChip("UI", 0xCC18202C);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC18202C);
        bg.setCornerRadius(14 * dp);
        bg.setStroke(Math.round(1.5f * dp), 0xCC58A6FF);
        tv.setBackground(bg);
        tv.setTextSize(12f);
        int ph = Math.round(12 * dp), pv = Math.round(7 * dp);
        tv.setPadding(ph, pv, ph, pv);

        tv.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pillDownX = ev.getRawX(); pillDownY = ev.getRawY();
                    pillStartX = pillParams.x; pillStartY = pillParams.y;
                    pillMoved = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - pillDownX, dy = ev.getRawY() - pillDownY;
                    if (Math.hypot(dx, dy) > 8 * dp) {
                        pillMoved = true;
                        pillParams.x = Math.max(0, Math.round(pillStartX + dx));
                        pillParams.y = Math.max(0, Math.round(pillStartY + dy));
                        if (pillAttached) {
                            try { wm.updateViewLayout(togglePill, pillParams); } catch (Throwable ignored) {}
                        }
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                    return pillMoved;
                default:
                    return false;
            }
        });
        tv.setOnClickListener(v -> {
            if (editMode) {
                setEditMode(false);
            } else {
                enabled = !enabled;
                if (!enabled) releaseAllInputs();
                savePrefs();
                syncUiState();
            }
        });
        tv.setOnLongClickListener(v -> {
            if (!enabled) enabled = true;
            setEditMode(!editMode);
            return true;
        });
        return tv;
    }

    private LinearLayout buildEditBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(6 * dp);
        bar.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE111620);
        bg.setCornerRadius(14 * dp);
        bg.setStroke(Math.round(1.5f * dp), 0xCC58A6FF);
        bar.setBackground(bg);

        selectedLabel = makeChip("Tap btn", 0x00000000);
        selectedLabel.setTextColor(0xFF9CDCFE);
        bar.addView(selectedLabel);

        shapeBtn = makeChip("Shape", 0xFF243044);
        shapeBtn.setOnClickListener(v -> {
            if (selected != null) {
                selected.shape = (selected.shape + 1) % SHAPE_NAMES.length;
                savePrefs();
                updateEditBar();
                canvasView.invalidate();
            }
        });
        bar.addView(shapeBtn);

        TextView allShapeBtn = makeChip("All Shape", 0xFF243044);
        allShapeBtn.setOnClickListener(v -> {
            int next = ((selected != null ? selected.shape : controls.get(2).shape) + 1) % SHAPE_NAMES.length;
            for (Control c : controls) if (c.stick < 0) c.shape = next;
            savePrefs();
            updateEditBar();
            canvasView.invalidate();
        });
        bar.addView(allShapeBtn);

        hideBtn = makeChip("Hide", 0xFF3A2430);
        hideBtn.setOnClickListener(v -> {
            if (selected != null) {
                selected.visible = !selected.visible;
                savePrefs();
                updateEditBar();
                canvasView.invalidate();
            }
        });
        bar.addView(hideBtn);

        TextView showAllBtn = makeChip("Show All", 0xFF1F382E);
        showAllBtn.setOnClickListener(v -> {
            for (Control c : controls) c.visible = true;
            savePrefs();
            updateEditBar();
            canvasView.invalidate();
        });
        bar.addView(showAllBtn);

        scaleSeek = new SeekBar(activity);
        scaleSeek.setMax(170); // 0.50 .. 2.20
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(Math.round(84 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
        skLp.leftMargin = Math.round(4 * dp);
        scaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && selected != null) {
                    selected.scale = MIN_SCALE + progress / 100f;
                    updateEditBar();
                    canvasView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { savePrefs(); }
        });
        bar.addView(scaleSeek, skLp);

        alphaSeek = new SeekBar(activity);
        alphaSeek.setMax(80); // 20..100%
        alphaSeek.setProgress(opacityPct - 20);
        LinearLayout.LayoutParams alLp = new LinearLayout.LayoutParams(Math.round(64 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
        alLp.leftMargin = Math.round(4 * dp);
        alphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    opacityPct = 20 + progress;
                    canvasView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { savePrefs(); }
        });
        bar.addView(alphaSeek, alLp);

        TextView resetBtn = makeChip("Reset", 0xFF42301C);
        resetBtn.setOnClickListener(v -> {
            for (Control c : controls) c.reset();
            opacityPct = 65;
            alphaSeek.setProgress(opacityPct - 20);
            savePrefs();
            updateEditBar();
            canvasView.invalidate();
        });
        bar.addView(resetBtn);

        TextView logBtn = makeChip("Log", 0xFF2B3A4A);
        logBtn.setOnClickListener(v -> shareLog());
        bar.addView(logBtn);

        TextView doneBtn = makeChip("Done", 0xFF1F6FEB);
        doneBtn.setOnClickListener(v -> setEditMode(false));
        bar.addView(doneBtn);

        return bar;
    }

    private void shareLog() {
        try {
            File log = new File(activity.getFilesDir(), "game/totk.log");
            String text;
            if (!log.isFile()) {
                text = "totk.log not found in internal storage.";
            } else {
                long len = log.length();
                int keep = (int) Math.min(len, 16384L);
                byte[] buf = new byte[keep];
                try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
                    raf.seek(len - keep);
                    raf.readFully(buf);
                }
                text = new String(buf, StandardCharsets.UTF_8);
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "TotK Native S25+ totk.log");
            i.putExtra(Intent.EXTRA_TEXT, text);
            activity.startActivity(Intent.createChooser(i, "Share totk.log"));
        } catch (Throwable ignored) {}
    }

    private TextView makeChip(String text, int bgColor) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        int ph = Math.round(8 * dp), pv = Math.round(5 * dp);
        tv.setPadding(ph, pv, ph, pv);
        if (bgColor != 0) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(bgColor);
            d.setCornerRadius(10 * dp);
            tv.setBackground(d);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(3 * dp);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void setEditMode(boolean on) {
        editMode = on;
        if (on) {
            releaseAllInputs();
            if (selected == null) selected = controls.get(2); // A button
        } else {
            savePrefs();
        }
        syncUiState();
    }

    private void syncUiState() {
        if (destroyed || pillParams.token == null) return;
        // Keep overlayRoot below togglePill by attaching overlayRoot first when enabled.
        if (enabled && !overlayAttached) {
            try {
                if (pillAttached) { wm.removeViewImmediate(togglePill); pillAttached = false; }
                wm.addView(overlayRoot, overlayParams);
                overlayAttached = true;
            } catch (Throwable t) {
                Log.w(TAG, "overlay attach failed", t);
            }
        } else if (!enabled && overlayAttached) {
            try { wm.removeViewImmediate(overlayRoot); } catch (Throwable ignored) {}
            overlayAttached = false;
        }

        if (!pillAttached) {
            try {
                wm.addView(togglePill, pillParams);
                pillAttached = true;
            } catch (Throwable t) {
                Log.w(TAG, "pill attach failed", t);
            }
        }

        editBar.setVisibility(editMode ? View.VISIBLE : View.GONE);
        togglePill.setText(editMode ? "Editing" : enabled ? "UI: ON" : "UI");
        togglePill.setAlpha(editMode ? 1.0f : enabled ? 0.85f : 0.65f);
        updateEditBar();
        canvasView.invalidate();
    }

    private void updateEditBar() {
        if (selected == null) return;
        selectedLabel.setText(selected.label + " " + Math.round(selected.scale * 100) + "%");
        shapeBtn.setText(SHAPE_NAMES[selected.shape]);
        hideBtn.setText(selected.visible ? "Hide" : "Show");
        scaleSeek.setProgress(clampInt(Math.round((selected.scale - MIN_SCALE) * 100f), 0, 170));
    }

    void onPause() {
        releaseAllInputs();
    }

    void destroy() {
        destroyed = true;
        releaseAllInputs();
        if (pillAttached) {
            try { wm.removeViewImmediate(togglePill); } catch (Throwable ignored) {}
            pillAttached = false;
        }
        if (overlayAttached) {
            try { wm.removeViewImmediate(overlayRoot); } catch (Throwable ignored) {}
            overlayAttached = false;
        }
    }

    private void releaseAllInputs() {
        for (Control c : controls) {
            if (c.bit >= 0 && c.pressed) {
                c.pressed = false;
                TotkActivity.nativeTouchButton(1L << c.bit, false);
            } else if (c.stick >= 0 && (c.knobX != 0f || c.knobY != 0f || c.pressed)) {
                c.pressed = false;
                c.knobX = 0f; c.knobY = 0f;
                TotkActivity.nativeTouchStick(c.stick, 0f, 0f);
            }
        }
        canvasView.clearPointers();
        canvasView.invalidate();
    }

    private final class OverlayCanvas extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shapePath = new Path();
        private final RectF rect = new RectF();
        private final SparseArray<Control> activePointers = new SparseArray<>();
        private final ScaleGestureDetector pinchDetector;
        private float dragOffX, dragOffY;
        private boolean dragging;

        OverlayCanvas(Context ctx) {
            super(ctx);
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            pinchDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    if (editMode && selected != null) {
                        selected.scale = clamp(selected.scale * d.getScaleFactor(), MIN_SCALE, MAX_SCALE);
                        updateEditBar();
                        invalidate();
                        return true;
                    }
                    return false;
                }
                @Override public void onScaleEnd(ScaleGestureDetector d) {
                    if (editMode) savePrefs();
                }
            });
        }

        void clearPointers() { activePointers.clear(); }

        private float baseRadius(Control c) {
            float base = c.stick >= 0 ? 52f * dp : 27f * dp;
            return base * c.scale;
        }

        private float halfW(Control c) {
            float r = baseRadius(c);
            return c.stick < 0 && c.shape == SHAPE_PILL ? r * 1.48f : r;
        }

        private float halfH(Control c) {
            float r = baseRadius(c);
            return c.stick < 0 && c.shape == SHAPE_PILL ? r * 0.78f : r;
        }

        private Control hitTest(float px, float py, boolean includeHidden) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return null;
            for (int i = controls.size() - 1; i >= 0; i--) {
                Control c = controls.get(i);
                if (!includeHidden && !c.visible) continue;
                float cx = c.x * w, cy = c.y * h;
                if (c.stick >= 0) {
                    float r = baseRadius(c) * 1.30f;
                    float dx = px - cx, dy = py - cy;
                    if (dx * dx + dy * dy <= r * r) return c;
                } else {
                    float hw = halfW(c) * 1.12f, hh = halfH(c) * 1.12f;
                    if (Math.abs(px - cx) <= hw && Math.abs(py - cy) <= hh) return c;
                }
            }
            return null;
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            int baseAlpha = Math.round(255f * opacityPct / 100f);

            for (Control c : controls) {
                if (!c.visible && !editMode) continue;
                float cx = c.x * w, cy = c.y * h;
                float hw = halfW(c), hh = halfH(c);
                boolean isSel = editMode && c == selected;
                int a = !c.visible ? Math.max(35, baseAlpha / 3) : baseAlpha;

                fillPaint.setColor(c.pressed ? Color.argb(Math.min(255, a + 60), 88, 166, 255)
                        : Color.argb(a, 24, 30, 42));
                strokePaint.setStrokeWidth((isSel ? 2.6f : 1.5f) * dp);
                strokePaint.setColor(isSel ? Color.argb(255, 88, 196, 255)
                        : c.pressed ? Color.argb(255, 160, 216, 255)
                        : Color.argb(Math.min(255, a + 45), 220, 232, 248));

                drawControlShape(canvas, c, cx, cy, hw, hh);

                if (c.stick >= 0) {
                    float kr = hw * 0.42f;
                    float kx = cx + c.knobX * (hw - kr * 0.65f);
                    float ky = cy + c.knobY * (hh - kr * 0.65f);
                    fillPaint.setColor(Color.argb(Math.min(255, a + 55), 210, 224, 245));
                    canvas.drawCircle(kx, ky, kr, fillPaint);
                }

                textPaint.setTextSize((c.stick >= 0 ? 12f : 14f) * dp * Math.min(1.4f, c.scale));
                textPaint.setColor(Color.argb(Math.min(255, a + 70), 255, 255, 255));
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float ty = cy - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(c.label, cx, ty, textPaint);
            }
        }

        private void drawControlShape(Canvas canvas, Control c, float cx, float cy, float hw, float hh) {
            int sh = c.stick >= 0 ? SHAPE_CIRCLE : c.shape;
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            switch (sh) {
                case SHAPE_SQUARE:
                    float rad = Math.min(hw, hh) * 0.34f;
                    canvas.drawRoundRect(rect, rad, rad, fillPaint);
                    canvas.drawRoundRect(rect, rad, rad, strokePaint);
                    break;
                case SHAPE_PILL:
                    canvas.drawRoundRect(rect, hh, hh, fillPaint);
                    canvas.drawRoundRect(rect, hh, hh, strokePaint);
                    break;
                case SHAPE_OCTAGON:
                    buildOctagon(shapePath, cx, cy, hw, hh);
                    canvas.drawPath(shapePath, fillPaint);
                    canvas.drawPath(shapePath, strokePaint);
                    break;
                default:
                    canvas.drawOval(rect, fillPaint);
                    canvas.drawOval(rect, strokePaint);
                    break;
            }
        }

        private void buildOctagon(Path p, float cx, float cy, float hw, float hh) {
            float ox = hw * 0.42f, oy = hh * 0.42f;
            p.reset();
            p.moveTo(cx - ox, cy - hh);
            p.lineTo(cx + ox, cy - hh);
            p.lineTo(cx + hw, cy - oy);
            p.lineTo(cx + hw, cy + oy);
            p.lineTo(cx + ox, cy + hh);
            p.lineTo(cx - ox, cy + hh);
            p.lineTo(cx - hw, cy + oy);
            p.lineTo(cx - hw, cy - oy);
            p.close();
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (!enabled) return false;
            if (editMode) return handleEditTouch(ev);
            return handlePlayTouch(ev);
        }

        private boolean handleEditTouch(MotionEvent ev) {
            pinchDetector.onTouchEvent(ev);
            if (pinchDetector.isInProgress()) { dragging = false; return true; }
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return true;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    Control hit = hitTest(ev.getX(), ev.getY(), true);
                    if (hit != null) {
                        selected = hit;
                        dragOffX = ev.getX() - hit.x * w;
                        dragOffY = ev.getY() - hit.y * h;
                        dragging = true;
                        updateEditBar();
                        invalidate();
                    } else {
                        dragging = false;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging && selected != null && ev.getPointerCount() == 1) {
                        selected.x = clamp((ev.getX() - dragOffX) / w, 0.03f, 0.97f);
                        selected.y = clamp((ev.getY() - dragOffY) / h, 0.05f, 0.95f);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) { dragging = false; savePrefs(); }
                    return true;
                default:
                    return true;
            }
        }

        private boolean handlePlayTouch(MotionEvent ev) {
            int action = ev.getActionMasked();
            int idx = ev.getActionIndex();
            int pid = ev.getPointerId(idx);
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float px = ev.getX(idx), py = ev.getY(idx);
                    Control hit = hitTest(px, py, false);
                    if (hit == null) return activePointers.size() > 0;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    activePointers.put(pid, hit);
                    if (hit.bit >= 0) {
                        hit.pressed = true;
                        TotkActivity.nativeTouchButton(1L << hit.bit, true);
                    } else if (hit.stick >= 0) {
                        hit.pressed = true;
                        updateStick(hit, px, py);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    boolean handled = false;
                    for (int i = 0; i < ev.getPointerCount(); i++) {
                        int id = ev.getPointerId(i);
                        Control c = activePointers.get(id);
                        if (c != null && c.stick >= 0) {
                            updateStick(c, ev.getX(i), ev.getY(i));
                            handled = true;
                        }
                    }
                    if (handled) invalidate();
                    return activePointers.size() > 0;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (action == MotionEvent.ACTION_CANCEL) {
                        releaseAllInputs();
                        return true;
                    }
                    Control c = activePointers.get(pid);
                    activePointers.remove(pid);
                    if (c != null) {
                        if (c.bit >= 0) {
                            c.pressed = false;
                            TotkActivity.nativeTouchButton(1L << c.bit, false);
                        } else if (c.stick >= 0) {
                            c.pressed = false;
                            c.knobX = 0f; c.knobY = 0f;
                            TotkActivity.nativeTouchStick(c.stick, 0f, 0f);
                        }
                        invalidate();
                        return true;
                    }
                    return activePointers.size() > 0;
                }
                default:
                    return activePointers.size() > 0;
            }
        }

        private void updateStick(Control c, float px, float py) {
            float cx = c.x * getWidth(), cy = c.y * getHeight();
            float r = Math.max(1f, baseRadius(c));
            float dx = (px - cx) / r, dy = (py - cy) / r;
            float len = (float) Math.hypot(dx, dy);
            if (len > 1f) { dx /= len; dy /= len; }
            c.knobX = dx;
            c.knobY = dy;
            try { TotkActivity.nativeTouchStick(c.stick, dx, dy); } catch (Throwable ignored) {}
        }
    }

    private static String readEnvKey(Activity activity, String key) {
        try {
            File env = new File(activity.getFilesDir(), "game/totk.env");
            if (env.isFile()) {
                for (String raw : java.nio.file.Files.readAllLines(env.toPath(), StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0 && key.equals(line.substring(0, eq).trim())) {
                        return line.substring(eq + 1).trim();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return System.getenv(key);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
