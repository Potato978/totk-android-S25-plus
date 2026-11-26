package com.aure.totk;

import android.app.GameManager;
import android.app.GameState;
import android.app.NativeActivity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.AttachedSurfaceControl;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import com.aure.totk.companion.AssetStore;
import com.aure.totk.companion.CompanionNative;
import com.aure.totk.companion.DisplayClass;
import com.aure.totk.companion.PresentationController;
import com.aure.totk.setup.SetupActivity;   // compile-time constants only: the game process never loads setup classes
import com.aure.totk.setup.SaveMirror;
import java.nio.charset.StandardCharsets;

/** The game remains a NativeActivity; only eligible external displays get a Java UI.
 * SetupActivity (process ":setup") starts it once install.json is playable; the game process never loads the setup library. */
public final class TotkActivity extends NativeActivity {
    private PresentationController companion;
    /** Copies the saves to the player's save folder after each in-game save (null when no folder is set). */
    private SaveMirror.Watcher saves;
    private TouchOverlay touchOverlay;
    private AttachedSurfaceControl presentRoot;
    private AttachedSurfaceControl.OnBufferTransformHintChangedListener presentHintListener;
    private long presentEpoch;
    private boolean displayMoveRequested;
    private static boolean updateChecked;
    private boolean updateOpen;
    private String updateVersion;
    private void showUpdate() {
        if (!resumed || updateVersion == null || updateOpen || isFinishing()) return;
        String version = updateVersion;
        updateVersion = null;
        updateOpen = true;
        // Explicitly pause on both single- and dual-screen devices, independent of window focus.
        nativeGameFocus(false, true);
        com.aure.totk.setup.UpdatePrompt.show(this, version, now -> {
            updateOpen = false;
            if (now) {
                try {
                    startActivity(new Intent().setClassName(this, "com.aure.totk.setup.UpdateEntryActivity")
                            .setAction(Intent.ACTION_VIEW));
                } catch (RuntimeException failed) { Log.w("TotkGame", "Could not open the update menu", failed); }
            }
            boolean single = PresentationController.secondScreen(this, getDisplay()) == DisplayClass.NONE;
            nativeGameFocus(hasWindowFocus(), single);
        });
    }
    private static native void nativePresentTransformHint(long epoch, int hint);
    private static native void nativeTextInputDone(long serial, byte[] utf8);
    private static native void nativeGameFocus(boolean focused, boolean singleDisplay);
    private static native void nativeGameInputFocus(boolean top);
    static native void nativeTouchButton(long mask, boolean down);
    static native void nativeTouchStick(int stick, float x, float y);
    private void mirrorLogToExternal() {
        try {
            java.io.File src = new java.io.File(getFilesDir(), "game/totk.log");
            java.io.File ext = getExternalFilesDir(null);
            if (!src.isFile() || ext == null) return;
            java.io.File dst = new java.io.File(ext, "totk-last.log");
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (Throwable ignored) {}
    }
    private boolean keepRunningUnfocused() {
        try {
            java.io.File env = new java.io.File(getFilesDir(), "game/totk.env");
            if (env.isFile()) {
                for (String raw : java.nio.file.Files.readAllLines(env.toPath(), StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0 && "TOTK_KEEP_RUNNING_UNFOCUSED".equals(line.substring(0, eq).trim())) {
                        return !"0".equals(line.substring(eq + 1).trim());
                    }
                }
            }
        } catch (Throwable ignored) {}
        return !"0".equals(System.getenv("TOTK_KEEP_RUNNING_UNFOCUSED"));
    }
    private void setAndroidGameState(boolean active) {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            GameManager gm = getSystemService(GameManager.class);
            if (gm != null) gm.setGameState(new GameState(false,
                active ? GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE : GameState.MODE_NONE));
        } catch (RuntimeException ignored) {}
    }

    /** runtime/frontend_android.c, on this (the UI) thread: the game asks for text (nn::swkbd). TextEntry answers exactly
     * once: the UTF-8 text, or null when the player cancelled. */
    public void showTextInput(long serial, String prompt, String initial, int maxUnits, int minUnits, boolean secure,
                              boolean digitsOnly, int disabled) {
        TextEntry.show(this, prompt, initial, new TextRules(maxUnits, minUnits, digitsOnly, disabled), secure,
                text -> nativeTextInputDone(serial, text == null ? null : text.getBytes(StandardCharsets.UTF_8)));
    }

    /** runtime/game_error.h, on the failing native thread just before the process aborts (Vulkan device lost): open the
     * menu (process ":setup", which survives this one) with the reason instead of leaving the player on the home screen. */
    public void showGameError(String reason) {
        mirrorLogToExternal();
        try {
            startActivity(new Intent().setClassName(this, "com.aure.totk.setup.SetupActivity")
                    .putExtra(SetupActivity.EXTRA_GAME_ERROR, reason));
        } catch (RuntimeException failed) {
            Log.w("TotkGame", "could not open the menu for a game error", failed);
        }
    }

    /** The game's rumble and six-axis sensor (runtime/feedback_android.c); null when the game was given neither. */
    private volatile GameFeedback feedback;
    private boolean resumed;
    static native void nativeMotion(float ax, float ay, float az, float gx, float gy, float gz);
    static native void nativeMotionLive(boolean on, String source);
    private static native int nativeFeedbackEnabled();
    /** start_game (UI thread, before the game starts): GameFeedback.RUMBLE | MOTION this device can drive now. */
    public int feedbackHardware() { return GameFeedback.hardware(this); }
    /** The native rumble thread: amplitude 0..255 per side. */
    public void rumble(int left, int right) {
        GameFeedback f = feedback;
        if (f != null) f.rumble(left, right);
    }

    /** Native UI thread, on each window/display lifecycle update. This is an app-local content-rate request,
     * not a preferred mode or Android setting. -1 selects the highest advertised rate, including fractional Hz.
     * Integer requests retain the 0.01 Hz matching tolerance; 0 means no validated override. */
    public float selectFrameRateHint(int hz) {
        Display display = getWindow().getDecorView().getDisplay();
        float highest = 0;
        if ((hz > 0 || hz == -1) && display != null) {
            for (Display.Mode mode : display.getSupportedModes()) {
                float rate = mode.getRefreshRate();
                if (!Float.isFinite(rate) || rate <= 0) continue;
                if (hz == -1) highest = Math.max(highest, rate);
                else if (Math.abs((double)rate - hz) <= 0.01) return hz;
            }
        }
        if (highest > 0) return highest;
        Log.w("TotkPerf", "TOTK_FRAME_RATE_HINT_HZ=" + (hz == -1 ? "max" : hz) + " refused: "
                + (display == null ? "window has no display" : "not advertised by window display " + display.getDisplayId())
                + "; retaining existing policy");
        return 0;
    }

    /** Native UI thread only, after its explicit parent transaction succeeded.
     * API <=34 can crash in getBufferTransformHint before a root surface exists.
     * The bridge's successful buildReparentTransaction is our readiness gate. */
    public void updatePresentPrerotate(long epoch) {
        if (epoch != 0 && presentRoot != null && presentEpoch == epoch) return;
        if (presentRoot != null && presentHintListener != null) {
            presentRoot.removeOnBufferTransformHintChangedListener(presentHintListener);
        }
        presentRoot = null; presentHintListener = null; presentEpoch = 0;
        if (epoch == 0) return;
        if (Build.VERSION.SDK_INT < 32) {
            Log.w("TotkPresent", "Pre-rotation unavailable: buffer transform hint requires API32");
            nativePresentTransformHint(epoch, -1); return;
        }
        View decor = getWindow().getDecorView();
        AttachedSurfaceControl root = decor.isAttachedToWindow() ? decor.getRootSurfaceControl() : null;
        if (root == null) {
            Log.w("TotkPresent", "Pre-rotation deferred: no attached root");
            nativePresentTransformHint(epoch, -1); return;
        }
        presentRoot = root; presentEpoch = epoch;
        presentHintListener = hint -> {
            if (presentRoot == root && presentEpoch == epoch) nativePresentTransformHint(epoch, hint);
        };
        try {
            root.addOnBufferTransformHintChangedListener(presentHintListener);
            nativePresentTransformHint(epoch, root.getBufferTransformHint());
        } catch (RuntimeException unavailable) {
            Log.w("TotkPresent", "Pre-rotation hint unavailable; retaining identity", unavailable);
            root.removeOnBufferTransformHintChangedListener(presentHintListener);
            presentRoot = null; presentHintListener = null; presentEpoch = 0;
            nativePresentTransformHint(epoch, -1);
        }
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // NativeActivity synchronously loads totk.env in ANativeActivity_onCreate.
        // Query only after super, before constructing a controller or display listener.
        // The companion art is generated on the device by the optional setup stage; without it the companion stays off.
        if (CompanionNative.isEnabled() && AssetStore.available(this)) companion = new PresentationController(this, new CompanionNative());
        saves = SaveMirror.Watcher.start(this);
        int given = nativeFeedbackEnabled();   // what start_game gave the game (TOTK_RUMBLE / TOTK_GYRO and the hardware)
        if (given != 0 && !isFinishing()) feedback = new GameFeedback(this, given);
        if (!isFinishing()) { try { touchOverlay = new TouchOverlay(this); } catch (Throwable t) { Log.e("TotkGame", "TouchOverlay init failed", t); } }
        if (!updateChecked) {
            updateChecked = true;
            Updates.check(this, version -> { updateVersion = version; showUpdate(); });
        }
    }
    private boolean settingsRequested;
    /** Reuse/reparent the singleTask game, never finish it: NativeActivity.onDestroy exits the game process. */
    private void ensureMainDisplay() {
        if (GameLaunch.onMainScreen(this)) {
            displayMoveRequested = false;
            if (settingsRequested) {
                settingsRequested = false;
                com.aure.totk.setup.SetupActivity.openOnGame(this);
            }
            return;
        }
        if (displayMoveRequested) return;
        displayMoveRequested = true;
        try { GameLaunch.start(this); }
        catch (RuntimeException failed) {
            displayMoveRequested = false;
            Log.w("TotkGame", "Could not return the game task to the main display", failed);
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        settingsRequested |= intent.getBooleanExtra(GameLaunch.OPEN_SETTINGS, false);
        ensureMainDisplay();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        // Moving a task with the menu on top must not bring its background game back over the menu.
        if (resumed) ensureMainDisplay();
        if (resumed && !updateOpen && keepRunningUnfocused()) nativeGameFocus(true, false);
        if (companion != null && getDisplay() != null) companion.onDisplayChanged(getDisplay().getDisplayId());
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ensureMainDisplay();
        if (!updateOpen && keepRunningUnfocused()) nativeGameFocus(true, false);
        GameFeedback f = feedback;
        if (f != null) f.resume();
        setAndroidGameState(true);
        showUpdate();
    }
    @Override public void onTopResumedActivityChanged(boolean top) {
        super.onTopResumedActivityChanged(top);
        nativeGameInputFocus(top); // a release routed to another display must not leave a controller button held
        if (companion != null) companion.ownerTopResumed(top);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        // Keep running across transient focus losses (notifications, volume slider, DeX taskbar/windows)
        // when TOTK_KEEP_RUNNING_UNFOCUSED is enabled (default on S25+); full app switches still pause via onPause().
        if (!updateOpen && keepRunningUnfocused()) {
            nativeGameFocus(true, false);
            return;
        }
        boolean single = PresentationController.secondScreen(this, getDisplay()) == DisplayClass.NONE;
        nativeGameFocus(focused && !updateOpen, single || updateOpen);
        GameFeedback f = feedback;
        if (single && f != null) {
            if (focused && resumed) f.resume();
            else f.pause();
        }
    }
    @Override protected void onPause() {
        resumed = false;
        GameFeedback f = feedback;
        setAndroidGameState(false);
        mirrorLogToExternal();
        if (touchOverlay != null) touchOverlay.onPause();
        if (f != null) f.pause();   // no motion in the background, and no motor left running
        super.onPause();
    }
    @Override protected void onStop() {
        // Leaving the foreground may be the last chance before the process is killed: push a save made just now.
        if (saves != null) saves.flush();
        super.onStop();
    }
    @Override protected void onDestroy() {
        updatePresentPrerotate(0);
        if (companion != null) { companion.close(); companion = null; }
        if (saves != null) { saves.close(1500); saves = null; }
        setAndroidGameState(false);
        if (touchOverlay != null) { touchOverlay.destroy(); touchOverlay = null; }
        if (feedback != null) { feedback.close(); feedback = null; }
        // Native onDestroy exits the process, so all Java teardown must precede this call.
        super.onDestroy();
    }
}
