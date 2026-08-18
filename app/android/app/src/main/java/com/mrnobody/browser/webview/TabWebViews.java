package com.mrnobody.browser.webview;

import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mrnobody.browser.net.ProfileManager;

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
     */
    public static void release(int tabId) {
        destroy(LIVE.remove(tabId));
    }

    /** Every tab closed at once (Clear data, "close all tabs"). */
    public static void releaseAll() {
        for (Page page : LIVE.values()) {
            destroy(page);
        }
        LIVE.clear();
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
            destroy(LIVE.remove(oldest));
        }
    }

    private static void destroy(@Nullable Page page) {
        if (page == null) return;
        WebView webView = page.webView;
        try {
            detach(webView);
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setOnScrollChangeListener(null);
            webView.loadUrl("about:blank");
            if (page.isPrivate) {
                // A private tab leaves nothing behind it. This runs when the
                // tab is closed rather than when its view is rebuilt, so
                // switching away from a private tab no longer wipes the page
                // the user is still using.
                webView.clearCache(true);
                webView.clearFormData();
            }
            webView.clearHistory();
            webView.destroy();
        } catch (Throwable ignored) {
            // Tearing down a page must never take the app with it.
        }

        // The isolated profile can only be deleted once nothing is using it,
        // so this runs after destroy() and only when the last private tab has
        // gone. Where multi-profile is unsupported it is a no-op and the
        // clear-on-close above remains the only defence.
        if (page.isPrivate && !hasPrivatePages()) {
            ProfileManager.destroyPrivate();
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
