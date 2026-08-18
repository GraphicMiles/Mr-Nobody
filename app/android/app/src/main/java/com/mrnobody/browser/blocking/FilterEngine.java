package com.mrnobody.browser.blocking;

import android.content.Context;
import android.content.SharedPreferences;

import com.mrnobody.debug.ErrorLog;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
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

    /**
     * SHA-256 of the bundled {@code blocklist.txt}, pinned at build time.
     *
     * <p>Must be regenerated whenever the asset changes:
     * {@code sha256sum app/src/main/assets/blocklist.txt}. A stale value fails
     * closed -- blocking switches off and the reason is logged -- which is the
     * intended direction: a list we cannot vouch for should not silently
     * decide what gets blocked.
     */
    private static final String BUNDLED_DIGEST =
            "3ed6a3b8b92e87799efb13532fa0e827add9f16687b4b2e0cb4d92b6747990ae";

    /** Generous ceiling for the bundled list; guards a corrupt/hostile asset. */
    private static final int MAX_LIST_BYTES = 4 * 1024 * 1024;

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
            // Verify before parsing, not after: a list that fails the check
            // must never have reached the matcher, even briefly.
            byte[] raw = FilterIntegrity.readBounded(in, MAX_LIST_BYTES);
            FilterIntegrity.Result check = FilterIntegrity.verify(raw, BUNDLED_DIGEST);
            if (!check.ok) {
                // Fail closed. Blocking off is visible and recoverable; running
                // an unverified blocklist is neither.
                loaded = false;
                ErrorLog.record("blocklist integrity check failed: " + check.reason);
                return;
            }

            parseInto(new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(raw), "UTF-8")));
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

    /**
     * The one place blocklist text becomes rules.
     *
     * <p>Shared by the real asset load and the test seam so there is exactly
     * one parser to be wrong.
     */
    private void parseInto(BufferedReader reader) throws IOException {
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
    }

    /**
     * For tests: load rules from a stream instead of the APK's assets.
     *
     * <p>{@link #loadBundled} reads through {@code Context.getAssets()}, which
     * does not exist on a JVM, so without this the only way to test the
     * shipping request path off-device is to re-implement the parsing loop in
     * the test -- which then tests the copy rather than the original, and
     * passes happily while the real one is broken.
     *
     * <p>Deliberately shares {@link #parseInto} with {@code loadBundled} so a
     * section header or rule the real parser mishandles is mishandled here
     * too. Package-private: this is a seam for the tests beside it, not API.
     */
    void loadForTest(InputStream in) throws IOException {
        parseInto(new BufferedReader(new InputStreamReader(in, "UTF-8")));
        loaded = true;
    }

    /** For tests: reset in-memory state. */
    public void resetForTest() {
        pageAds = 0;
        pageTrackers = 0;
        totalAds = 0;
        totalTrackers = 0;
    }
}
