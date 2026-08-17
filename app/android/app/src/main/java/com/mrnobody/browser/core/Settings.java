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
    // V2
    public static final String KEY_PROFILE = "privacy_profile";
    public static final String KEY_PARAM_STRIPPING = "param_stripping";
    public static final String KEY_FINGERPRINT_PROTECTION = "fingerprint_protection";
    public static final String KEY_TERMINAL_ENABLED = "terminal_enabled";

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

    // ---------------------------------------------------------------- V2

    public PrivacyProfile getProfile() {
        return PrivacyProfile.fromName(prefs.getString(KEY_PROFILE, PrivacyProfile.BALANCED.name()));
    }

    public void setProfile(PrivacyProfile profile) {
        prefs.edit().putString(KEY_PROFILE, profile.name()).apply();
    }

    /** Strip known tracking parameters (utm_*, gclid, fbclid, ...) from URLs. */
    public boolean isParamStrippingEnabled() {
        return prefs.getBoolean(KEY_PARAM_STRIPPING, true);
    }

    public void setParamStrippingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PARAM_STRIPPING, enabled).apply();
    }

    public boolean isFingerprintProtection() {
        return prefs.getBoolean(KEY_FINGERPRINT_PROTECTION, false);
    }

    public void setFingerprintProtection(boolean enabled) {
        prefs.edit().putBoolean(KEY_FINGERPRINT_PROTECTION, enabled).apply();
    }

    // ------------------------------------------------------- AI provider keys
    // Stored locally; never used unless the user enables a remote provider.

    public String apiKey(String provider) {
        return prefs.getString("api_key_" + provider, "");
    }

    public void setApiKey(String provider, String key) {
        prefs.edit().putString("api_key_" + provider, key).apply();
    }

    public String apiBase(String provider) {
        String v = prefs.getString("api_base_" + provider, "");
        return v == null || v.trim().isEmpty() ? defaultBase(provider) : v;
    }

    public void setApiBase(String provider, String base) {
        prefs.edit().putString("api_base_" + provider, base).apply();
    }

    public String apiModel(String provider) {
        String v = prefs.getString("api_model_" + provider, "");
        return v == null || v.trim().isEmpty() ? defaultModel(provider) : v;
    }

    public void setApiModel(String provider, String model) {
        prefs.edit().putString("api_model_" + provider, model).apply();
    }

    /**
     * Correct, free-accessible defaults for each remote provider. A user can
     * override these (e.g. point OpenAI-compatible at their own gateway).
     */
    private static String defaultBase(String provider) {
        switch (provider) {
            case "gemini":
                return "https://generativelanguage.googleapis.com/v1beta"; // Google AI Studio free tier
            case "groq":
                return "https://api.groq.com/openai/v1"; // Groq free tier
            case "openai":
                return "https://openrouter.ai/api/v1"; // OpenRouter (has :free models)
            default:
                return "";
        }
    }

    private static String defaultModel(String provider) {
        switch (provider) {
            case "gemini":
                return "gemini-2.0-flash";
            case "groq":
                return "llama-3.3-70b-versatile";
            case "openai":
                return "meta-llama/llama-3.3-70b-instruct:free"; // OpenRouter free model
            default:
                return "";
        }
    }

    /**
     * Sandboxed terminal tool. OFF by default: the agent may not run shell
     * commands unless the user explicitly turns this on (spec §policy gate).
     */
    public boolean isTerminalEnabled() {
        return prefs.getBoolean(KEY_TERMINAL_ENABLED, false);
    }

    public void setTerminalEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TERMINAL_ENABLED, enabled).apply();
    }

    public String activeAiProvider() {
        return prefs.getString("active_ai_provider", "local");
    }

    public void setActiveAiProvider(String id) {
        prefs.edit().putString("active_ai_provider", id).apply();
    }
}
