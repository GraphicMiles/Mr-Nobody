package com.mrnobody.browser.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks which sites the user has granted camera/microphone/location/notification
 * access to (V2 permission dashboard). This mirrors WebView's own grant state so
 * the user can review and revoke from one place.
 *
 * Format: per host, a comma-separated list of granted permission names.
 */
public final class PermissionStore {

    private static final String PREFS = "mrnobody_perm_store";

    public static final String CAMERA = "camera";
    public static final String MICROPHONE = "microphone";
    public static final String LOCATION = "location";
    public static final String NOTIFICATION = "notification";

    private final SharedPreferences prefs;

    public PermissionStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void grant(String host, String permission) {
        String h = normalize(host);
        List<String> current = granted(h);
        if (!current.contains(permission)) current.add(permission);
        prefs.edit().putString(h, join(current)).apply();
    }

    public void revoke(String host, String permission) {
        String h = normalize(host);
        List<String> current = granted(h);
        current.remove(permission);
        if (current.isEmpty()) prefs.edit().remove(h).apply();
        else prefs.edit().putString(h, join(current)).apply();
    }

    public void revokeAll(String host) {
        prefs.edit().remove(normalize(host)).apply();
    }

    public List<String> granted(String host) {
        String raw = prefs.getString(normalize(host), "");
        List<String> list = new ArrayList<>();
        if (raw.isEmpty()) return list;
        for (String s : raw.split(",")) {
            if (!s.isEmpty()) list.add(s);
        }
        return list;
    }

    /** Host -> granted permissions, for the dashboard. */
    public Map<String, List<String>> allGrants() {
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            List<String> perms = new ArrayList<>();
            for (String s : String.valueOf(e.getValue()).split(",")) {
                if (!s.isEmpty()) perms.add(s);
            }
            if (!perms.isEmpty()) all.put(e.getKey(), perms);
        }
        return all;
    }

    private static String normalize(String host) {
        return host == null ? "" : host.toLowerCase();
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }
}
