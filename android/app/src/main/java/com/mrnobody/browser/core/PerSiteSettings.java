package com.mrnobody.browser.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-site privacy overrides (V2). The user can tune blocking, JavaScript,
 * cookies, and location for a specific host without changing global settings.
 *
 * Persistence is a single SharedPreferences file keyed by host; values are a
 * compact "k=v;k=v" string. Local only — never uploaded.
 */
public final class PerSiteSettings {

    private static final String PREFS = "mrnobody_per_site";

    // Per-site keys (absent = inherit global default).
    public static final String KEY_BLOCKING = "blocking";
    public static final String KEY_JS = "js";
    public static final String KEY_COOKIES = "cookies";
    public static final String KEY_LOCATION = "location";

    private final SharedPreferences prefs;

    public PerSiteSettings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Host key: lowercase host with no port/path. */
    private static String hostKey(String host) {
        if (host == null) return "";
        return host.toLowerCase();
    }

    private Map<String, String> decode(String host) {
        String raw = prefs.getString(hostKey(host), null);
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) map.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return map;
    }

    private void encode(String host, Map<String, String> map) {
        if (map.isEmpty()) {
            prefs.edit().remove(hostKey(host)).apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs.edit().putString(hostKey(host), sb.toString()).apply();
    }

    private String get(String host, String key) {
        return decode(host).get(key);
    }

    private void put(String host, String key, String value) {
        Map<String, String> map = new LinkedHashMap<>(decode(host));
        map.put(key, value);
        encode(host, map);
    }

    /** True if this host has any explicit override. */
    public boolean hasOverrides(String host) {
        return !decode(host).isEmpty();
    }

    /** Blocking override: null = inherit global. */
    public Boolean blocking(String host) {
        String v = get(host, KEY_BLOCKING);
        return v == null ? null : Boolean.parseBoolean(v);
    }

    public void setBlocking(String host, boolean value) {
        put(host, KEY_BLOCKING, Boolean.toString(value));
    }

    public Boolean js(String host) {
        String v = get(host, KEY_JS);
        return v == null ? null : Boolean.parseBoolean(v);
    }

    public void setJs(String host, boolean value) {
        put(host, KEY_JS, Boolean.toString(value));
    }

    public Boolean cookies(String host) {
        String v = get(host, KEY_COOKIES);
        return v == null ? null : Boolean.parseBoolean(v);
    }

    public void setCookies(String host, boolean value) {
        put(host, KEY_COOKIES, Boolean.toString(value));
    }

    public Boolean location(String host) {
        String v = get(host, KEY_LOCATION);
        return v == null ? null : Boolean.parseBoolean(v);
    }

    public void setLocation(String host, boolean value) {
        put(host, KEY_LOCATION, Boolean.toString(value));
    }

    /** All hosts that currently have overrides (for the dashboard/list). */
    public Map<String, Map<String, String>> allOverrides() {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Map<String, String> map = decode(e.getKey());
            if (!map.isEmpty()) all.put(e.getKey(), map);
        }
        return all;
    }

    public void clear(String host) {
        prefs.edit().remove(hostKey(host)).apply();
    }
}
