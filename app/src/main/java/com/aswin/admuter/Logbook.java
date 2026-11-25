package com.aswin.admuter;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small shared log shown on the app screen (newest first). */
final class Logbook {
    private static final int MAX_LINES = 50;

    private Logbook() { }

    static synchronized void add(Context ctx, String line) {
        SharedPreferences p = ctx.getSharedPreferences(AdMuterService.PREFS, Context.MODE_PRIVATE);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String all = time + "  " + line + "\n" + p.getString(AdMuterService.KEY_LOG, "");
        String[] lines = all.split("\n");
        if (lines.length > MAX_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_LINES; i++) sb.append(lines[i]).append('\n');
            all = sb.toString();
        }
        p.edit().putString(AdMuterService.KEY_LOG, all).apply();
    }
}
