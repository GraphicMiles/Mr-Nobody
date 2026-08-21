package com.mrnobody.browser.webview;

import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mrnobody.browser.net.ProfileManager;
import com.mrnobody.debug.ErrorLog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps a tab's WebView alive while its platform view comes and goes.
 *
 * <p>Flutter destroys a platform view as soon as the widget leaves the tree,
 * and rebuilds it on the way back. For a browser that is the wrong lifetime:
 * leaving the page and returning to it was destroying the WebView, so the tab
 * came back as an empty black surface with no document, no history and no
 * scroll position — and Reload did nothing, because there was nothing loaded
 * to reload.
 *
 * <p>A tab, not a view, owns a page. So the WebView is registered here against
 * the tab's stable id and merely <em>detached</em> when the platform view goes
 * away. The next platform view for that tab adopts the same WebView, still
 * showing the same document. The WebView is destroyed only when the user
 * actually closes the tab ({@link #release}), which Dart reports explicitly.
 *
 * <p>Main thread only: WebView construction, re-parenting and destruction all
 * have to happen there, and every caller (platform view creation, dispose, the
 * method channel) is already on it.
 */
public final class TabWebViews {

    /** Insertion-ordered so the oldest tab is the first eviction candidate. */
    private static final Map<Integer, Page> LIVE = new LinkedHashMap<>();

    /** A retained page, plus the one thing teardown needs to know about it. */
    private static final class Page {
        final WebView webView;
        final boolean isPrivate;

        Page(WebView webView, boolean isPrivate) {
            this.webView = webView;
            this.isPrivate = isPrivate;
        }
    }

    /**
     * How many detached pages may stay in memory. A WebView is expensive
     * (tens of MB with its renderer), so retention has to have a ceiling or a
     * long session becomes an out-of-memory kill. Tabs beyond this still work
     * — they just reload when reopened, which is the old behaviour.
     */
    private static final int MAX_RETAINED = 6;

    private TabWebViews() {
    }

    /** The live WebView for this tab, or null if it has to be created. */
    @Nullable
    static WebView get(int tabId) {
        Page page = LIVE.get(tabId);
        return page == null ? null : page.webView;
    }

    /** Register a freshly created WebView as this tab's page. */
    static void put(int tabId, @NonNull WebView webView, boolean isPrivate) {
        LIVE.put(tabId, new Page(webView, isPrivate));
        evictBeyondLimit(tabId);
    }

    /**
     * Detach a WebView from whatever is currently showing it, so it can be
     * adopted by a new container without Android complaining that the child
     * already has a parent.
     */
    static void detach(@NonNull WebView webView) {
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) parent.removeView(webView);
    }

    /**
     * The tab is gone for good: destroy the page and forget it. Called when
     * the user closes a tab, never when a platform view is merely rebuilt.
     * Also drops the tab's method channel, so no late command can reach the
     * destroyed WebView.
     */
    public static void release(int tabId) {
        MrNobodyWebView.releaseChannel(tabId);
        destroy(LIVE.remove(tabId));
    }

    /** Destroy only private pages before clearing their isolated profile. */
    public static void releasePrivate() {
        java.util.List<Integer> privateIds = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Page> entry : LIVE.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isPrivate) {
                privateIds.add(entry.getKey());
            }
        }
        for (Integer tabId : privateIds) {
            if (tabId != null) release(tabId);
        }
    }

    /** Every tab closed at once (Clear data, "close all tabs"). */
    public static void releaseAll() {
        java.util.List<Page> pages = new java.util.ArrayList<>(LIVE.values());
        boolean hadPrivate = false;
        for (Integer tabId : LIVE.keySet()) {
            if (tabId != null) MrNobodyWebView.releaseChannel(tabId);
        }
        // Clear ownership before teardown so the last-private check cannot see
        // already-destroyed pages as still live.
        LIVE.clear();
        for (Page page : pages) {
            if (page != null && page.isPrivate) hadPrivate = true;
            destroy(page, false);
        }
        if (hadPrivate) ProfileManager.destroyPrivateWhenIdle();
    }

    /** How many pages are being held. Exposed for tests and the debug log. */
    public static int retainedCount() {
        return LIVE.size();
    }

    private static void evictBeyondLimit(int keepTabId) {
        while (LIVE.size() > MAX_RETAINED) {
            Integer oldest = null;
            for (Integer id : LIVE.keySet()) {
                // Never evict the tab that is being shown right now.
                if (id != null && id != keepTabId) {
                    oldest = id;
                    break;
                }
            }
            if (oldest == null) return;
            // Eviction destroys the native target just like closing a tab.
            // Detach the stable channel first so no settings/navigation call
            // can reach the destroyed WebView before Flutter recreates it.
            MrNobodyWebView.releaseChannel(oldest);
            destroy(LIVE.remove(oldest));
        }
    }

    private static void destroy(@Nullable Page page) {
        destroy(page, true);
    }

    private static void destroy(@Nullable Page page, boolean managePrivateProfile) {
        if (page == null) return;
        WebView webView = page.webView;

        // Every preparation step is best-effort, but destroy() is mandatory.
        // The previous single try block skipped destroy when any harmless
        // cleanup call threw; that left a living private WebView and made
        // ProfileStore reject deletion through every retry.
        try {
            detach(webView);
        } catch (Throwable ignored) {
        }
        try {
            webView.stopLoading();
        } catch (Throwable ignored) {
        }
        try {
            webView.setWebChromeClient(null);
        } catch (Throwable ignored) {
        }
        try {
            webView.setWebViewClient(null);
        } catch (Throwable ignored) {
        }
        try {
            webView.setOnScrollChangeListener(null);
        } catch (Throwable ignored) {
        }
        try {
            webView.removeAllViews();
        } catch (Throwable ignored) {
        }
        if (page.isPrivate) {
            // A private tab leaves nothing behind it. Do not navigate to
            // about:blank during teardown: starting another navigation just
            // before destroy can prolong Chromium's ownership of the profile.
            try {
                webView.clearCache(true);
            } catch (Throwable ignored) {
            }
            try {
                webView.clearFormData();
            } catch (Throwable ignored) {
            }
        }
        try {
            webView.clearHistory();
        } catch (Throwable ignored) {
        }
        try {
            webView.destroy();
        } catch (Throwable failure) {
            ErrorLog.record("WebView destroy failed before profile cleanup: "
                    + failure.getClass().getSimpleName());
        }

        // The isolated profile can only be deleted once nothing is using it,
        // so this runs after destroy() and only when the last private tab has
        // gone. Where multi-profile is unsupported it is a no-op and the
        // clear-on-close above remains the only defence.
        if (managePrivateProfile && page.isPrivate && !hasPrivatePages()) {
            ProfileManager.destroyPrivateWhenIdle();
        }
    }

    /** True while any retained page is private. */
    private static boolean hasPrivatePages() {
        for (Page p : LIVE.values()) {
            if (p != null && p.isPrivate) return true;
        }
        return false;
    }
}
