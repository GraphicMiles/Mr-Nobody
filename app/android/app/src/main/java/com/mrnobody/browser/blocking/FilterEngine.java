package com.mrnobody.browser.blocking;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Local filter engine. Decides, for a given request URL, whether it should be
 * blocked and, if so, which category (ad vs. tracker). All counting is local;
 * nothing is uploaded.
 *
 * Failure model: if the filter list is missing or corrupt, {@link #isBlocking}
 * stays false and the browser keeps working normally — the privacy layer must
 * never become a single point of browser failure.
 */
public final class FilterEngine {

    public enum Category { NONE, AD, TRACKER }

    /** Bundled filter-list version. Bump when the blocklist changes. */
    private static final String FILTER_VERSION = "1";

    private final Blocklist blocklist = new Blocklist();
    private volatile boolean loaded = false;
    private volatile boolean enabled = true;

    // Per-page counters (reset on navigation) and session totals.
    private int pageAds = 0;
    private int pageTrackers = 0;
    private long totalAds = 0;
    private long totalTrackers = 0;

    private static final String PREFS = "mrnobody_filters";

    /** Optional callback fired on each blocked request (used by the daily report). */
    public interface BlockListener {
        void onBlocked(Category category);
    }

    private volatile BlockListener blockListener;

    public void loadBundled(Context context) {
        try (InputStream in = context.getAssets().open("blocklist.txt")) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            Blocklist.Category current = blocklist.ads;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.equalsIgnoreCase("[ADS]")) {
                    current = blocklist.ads;
                } else if (trimmed.equalsIgnoreCase("[TRACKERS]")) {
                    current = blocklist.trackers;
                } else {
                    blocklist.addLine(trimmed, current);
                }
            }
            loaded = true;
            loadTotals(context);
        } catch (IOException e) {
            // List missing/corrupt — browser still works, blocking disabled.
            loaded = false;
        }
    }

    private void loadTotals(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        totalAds = p.getLong("total_ads", 0);
        totalTrackers = p.getLong("total_trackers", 0);
    }

    private void persistTotals(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("total_ads", totalAds)
                .putLong("total_trackers", totalTrackers)
                .apply();
    }

    public boolean isBlocking() {
        return loaded && enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Decide whether a URL should be blocked.
     *
     * @return the category the URL was blocked under, or {@link Category#NONE}.
     */
    public Category shouldBlock(String url) {
        if (!isBlocking() || url == null) return Category.NONE;
        if (url.startsWith("data:") || url.startsWith("about:")) return Category.NONE;

        String host = null;
        String path = "";
        try {
            URI uri = new URI(url);
            host = uri.getHost();
            if (host == null) return Category.NONE;
            host = host.toLowerCase(Locale.ROOT);
            path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.isEmpty()) path = "/";
        } catch (URISyntaxException e) {
            return Category.NONE;
        }

        String target = host + path;

        if (matchesCategory(blocklist.ads, host, path, target)) {
            synchronized (this) {
                pageAds++;
                totalAds++;
            }
            notifyBlocked(Category.AD);
            return Category.AD;
        }
        if (matchesCategory(blocklist.trackers, host, path, target)) {
            synchronized (this) {
                pageTrackers++;
                totalTrackers++;
            }
            notifyBlocked(Category.TRACKER);
            return Category.TRACKER;
        }
        return Category.NONE;
    }

    private void notifyBlocked(Category category) {
        BlockListener l = blockListener;
        if (l != null) l.onBlocked(category);
    }

    public void setBlockListener(BlockListener listener) {
        this.blockListener = listener;
    }

    private boolean matchesCategory(Blocklist.Category cat, String host, String path, String target) {
        if (cat.matchesHost(host)) return true;
        if (cat.matchesPath(host, path)) return true;
        return cat.matchesWildcard(target);
    }

    /** Reset the per-page counters (call on new navigation). */
    public void resetPageCounters() {
        pageAds = 0;
        pageTrackers = 0;
    }

    public int getPageAdsBlocked() {
        return pageAds;
    }

    public int getPageTrackersBlocked() {
        return pageTrackers;
    }

    public long getTotalAdsBlocked() {
        return totalAds;
    }

    public long getTotalTrackersBlocked() {
        return totalTrackers;
    }

    /** Bundled filter-list version (integrity-lite; signed updates are V2.x). */
    public String getFilterVersion() {
        return FILTER_VERSION;
    }

    /**
     * Local per-page privacy score (0–100, higher = cleaner page). Computed
     * entirely on-device from blocked-request counts. A page that attempts no
     * known tracking scores 100; each blocked tracker/ad lowers the score.
     */
    public int privacyScore() {
        int penalty = pageTrackers * 5 + pageAds * 2;
        return Math.max(0, 100 - penalty);
    }

    public void persist(Context context) {
        persistTotals(context);
    }

    /** For tests: reset in-memory state. */
    public void resetForTest() {
        pageAds = 0;
        pageTrackers = 0;
        totalAds = 0;
        totalTrackers = 0;
    }
}
