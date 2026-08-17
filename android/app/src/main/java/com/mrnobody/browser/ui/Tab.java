package com.mrnobody.browser.ui;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.DownloadListener;

import com.mrnobody.browser.MrNobodyApp;
import com.mrnobody.browser.core.Settings;

/**
 * One browser tab. Each tab owns its own {@link WebView}. Private tabs never
 * record history and mark the window secure (FLAG_SECURE is applied at the
 * activity level when a private tab is active).
 *
 * Note (honest limitation): Android WebView has no per-tab incognito profile,
 * so a private tab shares the engine's cookie/storage jars. We clear private
 * state on close where practical and never write history for private tabs.
 * Full storage isolation is a V2 item — see docs/ARCHITECTURE.md.
 */
public final class Tab {

    private final int id;
    private final boolean isPrivate;
    private WebView webView;
    private String url = "";
    private String title = "";

    public Tab(int id, boolean isPrivate) {
        this.id = id;
        this.isPrivate = isPrivate;
    }

    public int id() {
        return id;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public String label() {
        return (title != null && !title.isEmpty()) ? title : (url.isEmpty() ? "New tab" : url);
    }

    public WebView getWebView(Context context, WebViewClient client, WebChromeClient chrome,
                              DownloadListener downloads) {
        if (webView == null) {
            webView = createWebView(context);
            webView.setWebViewClient(client);
            webView.setWebChromeClient(chrome);
            webView.setDownloadListener(downloads);
        }
        return webView;
    }

    private WebView createWebView(Context context) {
        WebView wv = new WebView(context);
        Settings settings = MrNobodyApp.settings();
        wv.getSettings().setJavaScriptEnabled(settings.isJsEnabled());
        wv.getSettings().setDomStorageEnabled(true);
        wv.getSettings().setDatabaseEnabled(true);
        wv.getSettings().setLoadWithOverviewMode(true);
        wv.getSettings().setUseWideViewPort(true);
        wv.getSettings().setBuiltInZoomControls(true);
        wv.getSettings().setDisplayZoomControls(false);
        wv.getSettings().setMediaPlaybackRequiresUserGesture(true);
        wv.getSettings().setSupportMultipleWindows(false);
        // Privacy: WebView Safe Browsing phones Google; keep it off.
        wv.getSettings().setSafeBrowsingEnabled(false);
        wv.setBackgroundColor(0xFF0E0E10);

        // Third-party cookies blocked (best-effort, documented).
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false);
        return wv;
    }

    public boolean canGoBack() {
        return webView != null && webView.canGoBack();
    }

    public void goBack() {
        if (webView != null) webView.goBack();
    }

    public void reload() {
        if (webView != null) webView.reload();
    }

    public void loadUrl(String url) {
        if (webView != null && url != null) webView.loadUrl(url);
    }

    public void onResume() {
        if (webView != null) webView.onResume();
    }

    public void onPause() {
        if (webView != null) webView.onPause();
    }

    public void destroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }

    public WebView peekWebView() {
        return webView;
    }
}
