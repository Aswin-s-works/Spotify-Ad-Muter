package com.aswin.admuter;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Simple one-screen UI: grant access, on/off switch, status, ad counter and detection log. */
public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private TextView accessText, statusText, countText, logText;
    private Button accessButton;
    private Switch enabledSwitch, restartSwitch;
    private TextView restartText;
    private Button restartButton;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(AdMuterService.PREFS, Context.MODE_PRIVATE);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Spotify Ad Muter");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView about = new TextView(this);
        about.setText("Runs in the background. When Spotify plays an ad, your media volume is "
                + "muted, and it comes back as soon as the music resumes.");
        about.setPadding(0, dp(6), 0, dp(14));
        root.addView(about);

        accessText = new TextView(this);
        accessText.setTextSize(16);
        root.addView(accessText);

        accessButton = new Button(this);
        accessButton.setText("Grant notification access");
        accessButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(accessButton);

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Mute Spotify ads");
        enabledSwitch.setTextSize(16);
        enabledSwitch.setPadding(0, dp(12), 0, dp(12));
        enabledSwitch.setChecked(prefs.getBoolean(AdMuterService.KEY_ENABLED, true));
        enabledSwitch.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean(AdMuterService.KEY_ENABLED, on).apply());
        root.addView(enabledSwitch);

        restartSwitch = new Switch(this);
        restartSwitch.setText("Restart Spotify when an ad plays (experimental — blocks UPI apps while on)");
        restartSwitch.setTextSize(16);
        restartSwitch.setPadding(0, dp(4), 0, dp(4));
        restartSwitch.setChecked(prefs.getBoolean(AdMuterService.KEY_RESTART, false));
        restartSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(AdMuterService.KEY_RESTART, on).apply();
            if (!on) RestartService.turnOff(); // removes the accessibility permission completely
        });
        root.addView(restartSwitch);

        restartText = new TextView(this);
        restartText.setTextSize(13);
        root.addView(restartText);

        restartButton = new Button(this);
        restartButton.setText("Turn on accessibility for restart mode");
        restartButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(restartButton);

        statusText = new TextView(this);
        statusText.setPadding(0, dp(12), 0, 0);
        statusText.setTextSize(16);
        root.addView(statusText);

        countText = new TextView(this);
        countText.setTextSize(16);
        countText.setPadding(0, dp(4), 0, dp(12));
        root.addView(countText);

        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = new TextView(this);
        logTitle.setText("Detection log (newest first)");
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logHeader.addView(logTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> prefs.edit()
                .putString(AdMuterService.KEY_LOG, "")
                .putInt(AdMuterService.KEY_COUNT, 0).apply());
        logHeader.addView(clear);
        root.addView(logHeader);

        ScrollView scroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(11);
        logText.setTextIsSelectable(true);
        logText.setBackgroundColor(Color.parseColor("#F2F2F2"));
        logText.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(logText);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If Android stopped the background listener, ask it to reconnect.
        if (hasAccess()) {
            try {
                NotificationListenerService.requestRebind(
                        new ComponentName(this, AdMuterService.class));
            } catch (Exception ignored) { }
        }
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void refresh() {
        boolean access = hasAccess();
        accessText.setText(access ? "✔ Notification access granted"
                : "✘ Notification access needed — tap the button and enable \"Spotify Ad Muter\"");
        accessText.setTextColor(access ? Color.parseColor("#1B8A3A") : Color.parseColor("#C62828"));
        accessButton.setText(access ? "Open notification access settings" : "Grant notification access");

        boolean restartOn = restartSwitch.isChecked();
        boolean a11y = RestartService.isRunning();
        restartText.setText(!restartOn
                ? "Off — ads are only muted. Accessibility is off, so UPI/payment apps work normally."
                : a11y ? "✔ Restart mode ready (works when the screen is on and unlocked; otherwise ads are muted)."
                       : "✘ Needs accessibility: Installed apps → Spotify Ad Muter – Restart mode → On");
        restartText.setTextColor(!restartOn ? Color.GRAY
                : a11y ? Color.parseColor("#1B8A3A") : Color.parseColor("#C62828"));
        restartButton.setVisibility(restartOn && !a11y ? android.view.View.VISIBLE : android.view.View.GONE);

        statusText.setText("Status: " + (access
                ? prefs.getString(AdMuterService.KEY_STATUS, "Starting…")
                : "Not running"));
        countText.setText("Ads muted: " + prefs.getInt(AdMuterService.KEY_COUNT, 0));
        String log = prefs.getString(AdMuterService.KEY_LOG, "");
        logText.setText(log.isEmpty() ? "Play something on Spotify — tracks and ads will appear here." : log);
    }

    private boolean hasAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, AdMuterService.class).flattenToString());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
