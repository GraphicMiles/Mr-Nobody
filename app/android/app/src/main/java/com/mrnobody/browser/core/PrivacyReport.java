package com.mrnobody.browser.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * Local privacy report (V2). Tracks today's aggregate counters — ads blocked,
 * trackers blocked, and pages loaded — for the daily report. Deliberately does
 * NOT store visited URLs or titles, so it reveals nothing about the user's
 * browsing even when enabled.
 *
 * Counters live on-device only.
 */
public final class PrivacyReport {

    private static final String PREFS = "mrnobody_report";

    private final SharedPreferences prefs;

    public PrivacyReport(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String todayKey() {
        Calendar c = Calendar.getInstance();
        return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private String key(String metric) {
        return metric + "_" + todayKey();
    }

    public void increment(String metric) {
        String k = key(metric);
        prefs.edit().putLong(k, prefs.getLong(k, 0) + 1).apply();
    }

    public long today(String metric) {
        return prefs.getLong(key(metric), 0);
    }

    public long adsBlocked() {
        return today("ads");
    }

    public long trackersBlocked() {
        return today("trackers");
    }

    public long pagesLoaded() {
        return today("pages");
    }

    /** Total across all metrics for today (the "privacy score" denominator is separate). */
    public void resetToday() {
        String suffix = "_" + todayKey();
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : prefs.getAll().keySet()) {
            if (k.endsWith(suffix)) ed.remove(k);
        }
        ed.apply();
    }
}
