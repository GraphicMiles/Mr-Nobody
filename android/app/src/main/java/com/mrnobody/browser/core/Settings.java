package com.mrnobody.browser.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Single source of truth for user settings. Every default favors privacy:
 * history OFF, search suggestions OFF, JavaScript ON (the web depends on it),
 * search engine = DuckDuckGo (privacy-respecting), blocking ON.
 */
public final class Settings {

    private static final String PREFS = "mrnobody_prefs";

    // Keys
    public static final String KEY_FIRST_LAUNCH_DONE = "first_launch_done";
    public static final String KEY_HISTORY_ENABLED = "history_enabled";
    public static final String KEY_JS_ENABLED = "js_enabled";
    public static final String KEY_SUGGESTIONS_ENABLED = "suggestions_enabled";
    public static final String KEY_BLOCKING_ENABLED = "blocking_enabled";
    public static final String KEY_SEARCH_ENGINE = "search_engine";
    public static final String KEY_THEME = "theme";

    public static final String SEARCH_DDG = "https://duckduckgo.com/?q=";
    public static final String SEARCH_BING = "https://www.bing.com/search?q=";
    public static final String SEARCH_STARTPAGE = "https://www.startpage.com/sp/search?query=";

    // Theme constants (see MainActivity.applyTheme)
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isFirstLaunchDone() {
        return prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false);
    }

    public void setFirstLaunchDone() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply();
    }

    public boolean isHistoryEnabled() {
        return prefs.getBoolean(KEY_HISTORY_ENABLED, false);
    }

    public void setHistoryEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HISTORY_ENABLED, enabled).apply();
    }

    public boolean isJsEnabled() {
        return prefs.getBoolean(KEY_JS_ENABLED, true);
    }

    public void setJsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_JS_ENABLED, enabled).apply();
        // Applied to existing sessions on next page load; see MainActivity.
    }

    public boolean areSuggestionsEnabled() {
        return prefs.getBoolean(KEY_SUGGESTIONS_ENABLED, false);
    }

    public void setSuggestionsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SUGGESTIONS_ENABLED, enabled).apply();
    }

    public boolean isBlockingEnabled() {
        return prefs.getBoolean(KEY_BLOCKING_ENABLED, true);
    }

    public void setBlockingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, enabled).apply();
    }

    public String getSearchEngine() {
        return prefs.getString(KEY_SEARCH_ENGINE, SEARCH_DDG);
    }

    public void setSearchEngine(String engine) {
        prefs.edit().putString(KEY_SEARCH_ENGINE, engine).apply();
    }

    public String getTheme() {
        return prefs.getString(KEY_THEME, THEME_SYSTEM);
    }

    public void setTheme(String theme) {
        prefs.edit().putString(KEY_THEME, theme).apply();
    }
}
