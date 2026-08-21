package com.mrnobody.browser.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.mrnobody.browser.net.ResourcePolicy;
import com.mrnobody.security.EncryptedPreferences;

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
    public static final String KEY_FINGERPRINT_BEFORE_NOBODY = "fingerprint_before_nobody";
    public static final String KEY_FINGERPRINT_FORCED_BY_NOBODY = "fingerprint_forced_by_nobody";
    public static final String KEY_TERMINAL_ENABLED = "terminal_enabled";
    // Privacy mode + network route (V2). See browser/net/.
    public static final String KEY_PRIVACY_MODE = "privacy_mode";
    public static final String KEY_ROUTE = "network_route";
    public static final String KEY_PROXY_KIND = "proxy_kind";
    public static final String KEY_PROXY_HOST = "proxy_host";
    public static final String KEY_PROXY_PORT = "proxy_port";
    /** How often the agent stops to ask before acting. */
    public static final String KEY_APPROVAL_MODE = "approval_mode";
    public static final String KEY_REMOTE_SERVER = "remote_server";
    public static final String KEY_RESOURCE_POLICY = "resource_policy";

    public static final String SEARCH_DDG = "https://duckduckgo.com/?q=";
    public static final String SEARCH_BING = "https://www.bing.com/search?q=";
    public static final String SEARCH_STARTPAGE = "https://www.startpage.com/sp/search?query=";
    public static final String SEARCH_GOOGLE = "https://www.google.com/search?q=";

    // Theme constants (see MainActivity.applyTheme)
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    private final SharedPreferences prefs;
    private final EncryptedPreferences secrets;

    public Settings(Context context) {
        this(context, PREFS);
    }

    /**
     * A Settings view over a named preferences file. Exists so Diagnostics
     * can probe the <em>defaults</em> against an empty file: the benchmark
     * used to read the live file and fail on any device where the user had
     * toggled history on — reporting user choice as a broken default.
     */
    public Settings(Context context, String prefsFile) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(prefsFile, Context.MODE_PRIVATE);
        // Same preference file for an in-place migration: legacy plaintext
        // api_key_* values become AES-GCM envelopes on their first read.
        secrets = new EncryptedPreferences(context, prefsFile,
                "mrnobody_provider_credentials_v1");
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

    /**
     * The fingerprint setting as it was before Nobody forced it on. Meaningless
     * unless {@link #isFingerprintForcedByNobody()} is true.
     */
    public boolean fingerprintBeforeNobody() {
        return prefs.getBoolean(KEY_FINGERPRINT_BEFORE_NOBODY, false);
    }

    public void setFingerprintBeforeNobody(boolean enabled) {
        prefs.edit().putBoolean(KEY_FINGERPRINT_BEFORE_NOBODY, enabled).apply();
    }

    /** True when Nobody turned fingerprinting on and we must restore on exit. */
    public boolean isFingerprintForcedByNobody() {
        return prefs.getBoolean(KEY_FINGERPRINT_FORCED_BY_NOBODY, false);
    }

    public void setFingerprintForcedByNobody(boolean forced) {
        prefs.edit().putBoolean(KEY_FINGERPRINT_FORCED_BY_NOBODY, forced).apply();
    }

    // ------------------------------------------------------- AI provider keys
    // Encrypted with an Android Keystore AES key and never used unless the user
    // enables a remote provider. Legacy plaintext values migrate on first read.

    public String apiKey(String provider) {
        return secrets.getString("api_key_" + provider, "");
    }

    public void setApiKey(String provider, String key) {
        secrets.putString("api_key_" + provider, key == null ? "" : key);
    }

    public void removeApiKey(String provider) {
        secrets.remove("api_key_" + provider);
    }

    public String apiBase(String provider) {
        String v = prefs.getString("api_base_" + provider, "");
        return v == null || v.trim().isEmpty() ? defaultBase(provider) : v;
    }

    public void setApiBase(String provider, String base) {
        prefs.edit().putString("api_base_" + provider, base).apply();
    }

    /**
     * The model the user picked, or empty. There is deliberately no default:
     * providers retire model ids (Groq's llama-3.3-70b-versatile disappeared
     * and every install carrying it started 404-ing), so the app asks the
     * provider what the key can use rather than shipping a guess.
     */
    public String apiModel(String provider) {
        String v = prefs.getString("api_model_" + provider, "");
        return v == null ? "" : v.trim();
    }

    public void setApiModel(String provider, String model) {
        prefs.edit().putString("api_model_" + provider, model).apply();
    }

    /**
     * The endpoint that <em>defines</em> a named provider — choosing "Groq"
     * means talking to Groq. Still editable, and empty for the generic
     * OpenAI-compatible option, where only the user knows their gateway.
     *
     * <p>Endpoints are stable in a way model ids are not; this is a starting
     * value, not a guess about someone's account.
     */
    private static String defaultBase(String provider) {
        switch (provider) {
            case "gemini":
                return "https://generativelanguage.googleapis.com/v1beta";
            case "groq":
                return "https://api.groq.com/openai/v1";
            case "openai":
            default:
                return ""; // the user's own gateway — we cannot know it
        }
    }

    /**
     * Sandboxed terminal tool. OFF by default: the agent may not run shell
     * commands unless the user explicitly turns this on (see the terminal policy gate).
     */
    public boolean isTerminalEnabled() {
        return prefs.getBoolean(KEY_TERMINAL_ENABLED, false);
    }

    public void setTerminalEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TERMINAL_ENABLED, enabled).apply();
    }

    // ------------------------------------------------- privacy mode / route

    /**
     * The privacy mode. NORMAL by default: a browser that silently starts in a
     * restricted mode is a browser people think is broken.
     */
    public String privacyMode() {
        return prefs.getString(KEY_PRIVACY_MODE, "NORMAL");
    }

    public void setPrivacyMode(String mode) {
        prefs.edit().putString(KEY_PRIVACY_MODE, mode).apply();
    }

    /** Which route NOBODY mode should use: "proxy" or "tor-orbot". */
    public String routeId() {
        return prefs.getString(KEY_ROUTE, "tor-orbot");
    }

    public void setRouteId(String id) {
        prefs.edit().putString(KEY_ROUTE, id).apply();
    }

    /** "http" or "socks". */
    public String proxyKind() {
        return prefs.getString(KEY_PROXY_KIND, "http");
    }

    public String proxyHost() {
        return prefs.getString(KEY_PROXY_HOST, "");
    }

    public int proxyPort() {
        return prefs.getInt(KEY_PROXY_PORT, 0);
    }

    /**
     * Approval mode. CAUTIOUS by default: search/read/sandbox-download go
     * ahead; click/submit and irreversible commands still ask.
     */
    public String approvalMode() {
        return prefs.getString(KEY_APPROVAL_MODE, "CAUTIOUS");
    }

    public void setApprovalMode(String mode) {
        prefs.edit().putString(KEY_APPROVAL_MODE, mode).apply();
    }

    /** Data Saver grade. Defaults to OFF: the privacy product never degrades a page by surprise. */
    public ResourcePolicy resourcePolicy() {
        return ResourcePolicy.fromName(prefs.getString(KEY_RESOURCE_POLICY, ResourcePolicy.OFF.name()));
    }

    public void setResourcePolicy(ResourcePolicy policy) {
        prefs.edit().putString(KEY_RESOURCE_POLICY,
                policy == null ? ResourcePolicy.OFF.name() : policy.name()).apply();
    }

    /** The remote worker's base URL. Empty until configured: remote is opt-in. */
    public String remoteServer() {
        return prefs.getString(KEY_REMOTE_SERVER, "");
    }

    public void setRemoteServer(String url) {
        prefs.edit().putString(KEY_REMOTE_SERVER, url == null ? "" : url.trim()).apply();
    }

    public void setProxy(String kind, String host, int port) {
        prefs.edit()
                .putString(KEY_PROXY_KIND, kind == null ? "http" : kind)
                .putString(KEY_PROXY_HOST, host == null ? "" : host.trim())
                .putInt(KEY_PROXY_PORT, port)
                .apply();
    }

    public String activeAiProvider() {
        return prefs.getString("active_ai_provider", "local");
    }

    public void setActiveAiProvider(String id) {
        prefs.edit().putString("active_ai_provider", id).apply();
    }
}
