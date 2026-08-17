package com.mrnobody.browser.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the browser's ephemeral UI state — open tabs, their order, and the
 * active tab — so a session survives process death. This is "state memory", NOT
 * browsing history: the history store remains OFF by default and is entirely
 * separate. Restoring "you had 3 tabs open" never writes a history row.
 *
 * Stored as a compact JSON blob in SharedPreferences (a browser has a handful
 * of tabs, so a SQLite table is overkill; TaskStore already uses SQLite for the
 * heavier task model).
 */
public final class TabStateStore {

    private static final String PREFS = "mrnobody_tab_state";
    private static final String KEY_TABS = "tabs";
    private static final String KEY_ACTIVE = "active_id";
    private static final String KEY_NEXT_ID = "next_id";
    private static final String KEY_LAUNCHED = "launched";

    private final SharedPreferences prefs;

    public TabStateStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A plain snapshot of a tab, independent of its (non-serializable) WebView. */
    public static final class Snapshot {
        public final int id;
        public final boolean isPrivate;
        public final String url;
        public final String title;

        Snapshot(int id, boolean isPrivate, String url, String title) {
            this.id = id;
            this.isPrivate = isPrivate;
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
        }
    }

    /** Save the tab list (in order), active tab, and next-id counter. */
    public void save(List<Tab> tabs, Tab active, int nextId) {
        JSONArray arr = new JSONArray();
        for (Tab t : tabs) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", t.id());
                o.put("private", t.isPrivate());
                o.put("url", t.getUrl());
                o.put("title", t.getTitle());
                arr.put(o);
            } catch (Exception ignored) { }
        }
        prefs.edit()
                .putString(KEY_TABS, arr.toString())
                .putInt(KEY_ACTIVE, active != null ? active.id() : -1)
                .putInt(KEY_NEXT_ID, nextId)
                .putBoolean(KEY_LAUNCHED, true)
                .apply();
    }

    /** Load tab snapshots in their saved order, or an empty list. */
    public List<Snapshot> loadTabs() {
        List<Snapshot> out = new ArrayList<>();
        String raw = prefs.getString(KEY_TABS, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Snapshot(
                        o.optInt("id", -1),
                        o.optBoolean("private", false),
                        o.optString("url", ""),
                        o.optString("title", "")));
            }
        } catch (Exception e) {
            // Corrupt state → treat as no tabs (browser still works).
            out.clear();
        }
        return out;
    }

    public int loadActiveId() {
        return prefs.getInt(KEY_ACTIVE, -1);
    }

    public int loadNextId() {
        return prefs.getInt(KEY_NEXT_ID, 0);
    }

    public boolean hasSavedState() {
        return prefs.contains(KEY_TABS);
    }

    public boolean wasLaunched() {
        return prefs.getBoolean(KEY_LAUNCHED, false);
    }

    /** Clear all persisted tab state (used by "clear browsing data"). */
    public void clear() {
        prefs.edit().clear().apply();
    }
}
