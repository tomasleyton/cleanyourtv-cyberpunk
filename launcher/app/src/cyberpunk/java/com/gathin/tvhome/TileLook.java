package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which app tiles the cyberpunk home shows: text tiles (index and name, like the mock) or the apps'
 * banner artwork. The default comes from {@code cyber_text_tiles} (values/config.xml); the top-bar
 * button ({@link TileLookButton}) flips it at runtime and the choice is kept in preferences.
 */
final class TileLook {

    private static final String PREFS = "cyber_home";
    private static final String KEY = "text_tiles";

    private TileLook() {
    }

    static boolean isText(Context ctx) {
        boolean fallback = ctx.getResources().getBoolean(R.bool.cyber_text_tiles);
        return prefs(ctx).getBoolean(KEY, fallback);
    }

    /** Flips the look and returns the new value; the caller rebuilds the screen. */
    static boolean toggle(Context ctx) {
        boolean next = !isText(ctx);
        prefs(ctx).edit().putBoolean(KEY, next).apply();
        return next;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
