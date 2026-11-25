package com.aswin.admuter;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Starts automatically (once notification access is granted) and watches Spotify's
 * media session. When the current item looks like an ad, the music stream is muted;
 * as soon as a real track comes back, the volume is restored.
 *
 * Nothing is blocked inside Spotify itself: the ad still plays, you just don't hear it.
 */
public class AdMuterService extends NotificationListenerService {

    public static final String SPOTIFY = "com.spotify.music";
    public static final String PREFS = "admuter";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_COUNT = "ads_muted";
    public static final String KEY_LOG = "log";
    public static final String KEY_STATUS = "status";

    public static final String KEY_RESTART = "restart_mode";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();

    private MediaSessionManager sessionManager;
    private AudioManager audio;
    private SharedPreferences prefs;

    private boolean weMuted = false;
    private int savedVolume = -1;          // used only by the volume-0 fallback
    private String lastLoggedKey = "";

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            this::attachToSessions;

    // Re-check immediately when the on/off switch in the app is flipped.
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if (KEY_ENABLED.equals(key)) {
            for (MediaController c : callbacks.keySet()) evaluate(c);
            if (callbacks.isEmpty() && !sp.getBoolean(KEY_ENABLED, true)) unmute();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        ComponentName me = new ComponentName(this, AdMuterService.class);
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
            attachToSessions(sessionManager.getActiveSessions(me));
            setStatus("Running — waiting for Spotify");
        } catch (SecurityException e) {
            setStatus("Notification access missing");
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (sessionManager != null) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        }
        detachAll();
        unmute();
        setStatus("Stopped");
        super.onListenerDisconnected();
    }

    // ------------------------------------------------------------------ sessions

    private void attachToSessions(List<MediaController> controllers) {
        detachAll();
        boolean spotifyFound = false;
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (!SPOTIFY.equals(c.getPackageName())) continue;
                spotifyFound = true;
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onMetadataChanged(MediaMetadata metadata) { evaluate(c); }
                    @Override public void onPlaybackStateChanged(PlaybackState state) { evaluate(c); }
                    @Override public void onSessionDestroyed() {
                        unmute();
                        setStatus("Running — Spotify closed");
                    }
                };
                c.registerCallback(cb, handler);
                callbacks.put(c, cb);
                evaluate(c);
            }
        }
        if (!spotifyFound) {
            unmute();   // never leave the phone muted if Spotify disappears mid-ad
            setStatus("Running — waiting for Spotify");
        }
    }

    private void detachAll() {
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try { e.getKey().unregisterCallback(e.getValue()); } catch (Exception ignored) { }
        }
        callbacks.clear();
    }

    // ------------------------------------------------------------------ detection

    private void evaluate(MediaController c) {
        if (prefs == null) return;
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            unmute();
            lastLoggedKey = "";
            setStatus("Paused (turned off in app)");
            return;
        }

        MediaMetadata md = c.getMetadata();
        PlaybackState st = c.getPlaybackState();
        if (md == null) return;

        String title  = text(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST);
        String album  = text(md, MediaMetadata.METADATA_KEY_ALBUM);
        long durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        boolean canSkip = st != null && (st.getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;

        String reason = adReason(title, artist, album, durationMs, canSkip);
        boolean isAd = reason != null;

        if (isAd) {
            mute();
            setStatus("AD detected — muted");
            if (prefs.getBoolean(KEY_RESTART, false)) {
                RestartService.requestRestart(this);
            }
        } else {
            unmute();
            setStatus("Playing music — sound on");
        }

        // Log each distinct item once, so the user can see what Spotify reported.
        String key = title + "|" + artist + "|" + album + "|" + isAd;
        if (!key.equals(lastLoggedKey)) {
            lastLoggedKey = key;
            if (isAd) prefs.edit().putInt(KEY_COUNT, prefs.getInt(KEY_COUNT, 0) + 1).apply();
            log((isAd ? "MUTED  [" + reason + "] " : "MUSIC  ")
                    + "title=\"" + title + "\" artist=\"" + artist + "\" album=\"" + album
                    + "\" len=" + (durationMs / 1000) + "s skip=" + canSkip);
        }
    }

    /**
     * Returns why the item is considered an ad, or null if it looks like normal music.
     * Spotify does not label ads officially, so this uses the patterns ads show in
     * the media session: an "Advertisement"/"Spotify" title or artist, or missing
     * artist + album information.
     */
    static String adReason(String title, String artist, String album, long durationMs, boolean canSkip) {
        String t = title.trim().toLowerCase(Locale.ROOT);
        String a = artist.trim().toLowerCase(Locale.ROOT);

        if (t.equals("advertisement") || t.startsWith("advertisement")) return "title is Advertisement";
        if (a.equals("advertisement") || a.startsWith("advertisement")) return "artist is Advertisement";
        if (t.equals("spotify") || a.equals("spotify")) return "title/artist is Spotify";
        if (t.isEmpty() && a.isEmpty()) return "no title or artist";
        if (a.isEmpty() && album.trim().isEmpty()) return "no artist and no album";
        // Short, unskippable item with no album info: typical ad break slot.
        if (!canSkip && album.trim().isEmpty() && durationMs > 0 && durationMs <= 65_000)
            return "short, unskippable, no album";
        return null;
    }

    private static String text(MediaMetadata md, String key) {
        CharSequence cs = md.getText(key);
        return cs == null ? "" : cs.toString();
    }

    // ------------------------------------------------------------------ audio

    private void mute() {
        if (weMuted || audio == null) return;
        weMuted = true;
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
        } catch (Exception ignored) { }
        // Fallback for phones that ignore ADJUST_MUTE: drop the volume to 0 and remember it.
        if (!audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        }
    }

    private void unmute() {
        if (!weMuted || audio == null) return;
        weMuted = false;
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) { }
        if (savedVolume >= 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
            savedVolume = -1;
        }
    }

    // ------------------------------------------------------------------ status/log

    private void setStatus(String s) {
        if (prefs != null) prefs.edit().putString(KEY_STATUS, s).apply();
    }

    private void log(String line) {
        Logbook.add(this, line);
    }
}
