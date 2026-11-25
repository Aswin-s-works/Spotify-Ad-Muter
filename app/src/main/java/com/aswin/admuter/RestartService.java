package com.aswin.admuter;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * Optional "Restart Spotify on ad" mode (accessibility service).
 *
 * Steps when an ad is detected:
 *   1. open Spotify's App info page in Settings
 *   2. tap "Force stop", then confirm the dialog (Samsung One UI: "Force stop" / AOSP: "OK")
 *   3. reopen Spotify and send Play
 * The phone stays muted the whole time; AdMuterService unmutes when real music is playing.
 * If anything goes wrong it gives up after a timeout and the muting fallback keeps working.
 */
public class RestartService extends AccessibilityService {

    private enum Step { IDLE, OPEN_INFO, CONFIRM, RELAUNCH }

    private static RestartService instance;

    private static final long STEP_TIMEOUT_MS = 12_000;   // give up if Settings doesn't cooperate
    private static final long COOLDOWN_MS = 90_000;       // never restart in a loop
    private static final long POLL_MS = 300;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Step step = Step.IDLE;
    private long stepStarted = 0;
    private long lastRestart = -COOLDOWN_MS;
    private int playAttempts = 0;

    // ------------------------------------------------------------------ entry point

    /** Called by AdMuterService when an ad starts. Safe to call repeatedly. */
    static void requestRestart(Context ctx) {
        RestartService s = instance;
        if (s == null) {
            return; // accessibility not enabled: muting alone handles the ad
        }
        s.start();
    }

    /** Fully switches the accessibility service off (so payment apps stop flagging it). */
    static void turnOff() {
        RestartService s = instance;
        if (s != null) s.disableSelf();
    }

    static boolean isRunning() {
        return instance != null;
    }

    private void start() {
        if (step != Step.IDLE) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRestart < COOLDOWN_MS) {
            Logbook.add(this, "RESTART skipped — restarted less than 90 s ago, muting instead");
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (!pm.isInteractive() || km.isKeyguardLocked()) {
            Logbook.add(this, "RESTART skipped — screen off/locked, muting instead");
            return;
        }

        lastRestart = now;
        Logbook.add(this, "RESTART 1/3 opening Spotify App info");
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + AdMuterService.SPOTIFY));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            startActivity(i);
        } catch (Exception e) {
            Logbook.add(this, "RESTART failed to open Settings: " + e.getMessage());
            return;
        }
        setStep(Step.OPEN_INFO);
        handler.post(poller);
    }

    // ------------------------------------------------------------------ state machine

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (step == Step.IDLE || step == Step.RELAUNCH) return;
            if (SystemClock.elapsedRealtime() - stepStarted > STEP_TIMEOUT_MS) {
                Logbook.add(RestartService.this, "RESTART gave up at step " + step
                        + " (button not found) — muting instead");
                setStep(Step.IDLE);
                performGlobalAction(GLOBAL_ACTION_HOME);
                return;
            }
            advance();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (step == Step.OPEN_INFO || step == Step.CONFIRM) advance();
    }

    private void advance() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !pkg.toString().contains("settings")) return;

        if (step == Step.OPEN_INFO) {
            // Make sure this is Spotify's page before touching anything.
            if (root.findAccessibilityNodeInfosByText("Spotify").isEmpty()) return;
            AccessibilityNodeInfo btn = findButton(root, "force stop");
            if (btn == null) return;
            if (!btn.isEnabled()) {
                // Settings fills in the button state a moment after opening; wait before trusting it.
                if (SystemClock.elapsedRealtime() - stepStarted < 1500) return;
                // Already stopped — go straight to relaunch.
                Logbook.add(this, "RESTART 2/3 Spotify already stopped");
                relaunch();
                return;
            }
            if (click(btn)) {
                Logbook.add(this, "RESTART 2/3 tapped Force stop");
                setStep(Step.CONFIRM);
            }
        } else if (step == Step.CONFIRM) {
            // Dialog's positive button: android:id/button1 on Samsung and AOSP.
            AccessibilityNodeInfo ok = null;
            List<AccessibilityNodeInfo> ids = root.findAccessibilityNodeInfosByViewId("android:id/button1");
            if (!ids.isEmpty()) ok = ids.get(0);
            if (ok == null) ok = findButton(root, "ok");
            if (ok == null) ok = findButton(root, "force stop"); // One UI dialog button text
            if (ok != null && click(ok)) {
                Logbook.add(this, "RESTART 2/3 confirmed Force stop");
                relaunch();
            }
        }
    }

    private void relaunch() {
        setStep(Step.RELAUNCH);
        handler.removeCallbacks(poller);
        handler.postDelayed(() -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(AdMuterService.SPOTIFY);
            if (launch == null) {
                Logbook.add(this, "RESTART failed — Spotify launch intent not found");
                setStep(Step.IDLE);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
            Logbook.add(this, "RESTART 3/3 reopened Spotify, resuming playback…");
            playAttempts = 0;
            handler.postDelayed(resume, 2500);
        }, 700);
    }

    /** Presses Play until Spotify reports it is playing (max ~6 tries). */
    private final Runnable resume = new Runnable() {
        @Override public void run() {
            MediaController spotify = findSpotifySession();
            if (spotify != null && spotify.getPlaybackState() != null
                    && spotify.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) {
                Logbook.add(RestartService.this, "RESTART done — Spotify playing");
                setStep(Step.IDLE);
                return;
            }
            if (++playAttempts > 6) {
                Logbook.add(RestartService.this, "RESTART: Spotify reopened but didn't start; tap play");
                setStep(Step.IDLE);
                return;
            }
            if (spotify != null) {
                spotify.getTransportControls().play();
            } else {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
            }
            handler.postDelayed(this, 1500);
        }
    };

    // ------------------------------------------------------------------ helpers

    private MediaController findSpotifySession() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(this, AdMuterService.class));
            for (MediaController c : list) {
                if (AdMuterService.SPOTIFY.equals(c.getPackageName())) return c;
            }
        } catch (SecurityException ignored) { }
        return null;
    }

    /** Finds a node whose text or description equals the label (ignoring case and "?"). */
    private static AccessibilityNodeInfo findButton(AccessibilityNodeInfo root, String label) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo n : nodes) {
            String t = n.getText() != null ? n.getText().toString()
                    : (n.getContentDescription() != null ? n.getContentDescription().toString() : "");
            if (!t.trim().toLowerCase(Locale.ROOT).equals(label)) continue; // skip "Force stop?" titles etc.
            best = n; // keep the last match (dialog buttons come after titles)
        }
        return best;
    }

    private static boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        while (cur != null) {
            if (cur.isClickable()) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            cur = cur.getParent();
        }
        return false;
    }

    private void setStep(Step s) {
        step = s;
        stepStarted = SystemClock.elapsedRealtime();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() { }
}
