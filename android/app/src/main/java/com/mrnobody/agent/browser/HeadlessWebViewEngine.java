package com.mrnobody.agent.browser;

import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * A headless WebView-based engine (V1 "one tested lightweight backend"). It
 * renders off-screen and extracts text/title. This is deliberately minimal: it
 * proves the BrowserEngine interface; richer automation (click/type/scroll) is
 * V2 and arrives behind the same interface.
 *
 * Uses Android System WebView as the rendering surface, so it adds no APK size.
 * If a lighter/faster engine proves necessary later, it replaces this class.
 */
public final class HeadlessWebViewEngine implements BrowserEngine {

    private final WebView webView;
    private volatile String currentTitle = "";

    public HeadlessWebViewEngine(WebView webView) {
        this.webView = webView;
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                currentTitle = view.getTitle() == null ? "" : view.getTitle();
            }
        });
    }

    @Override
    public void open(String url) {
        if (webView != null) webView.loadUrl(url);
    }

    @Override
    public void back() {
        if (webView != null && webView.canGoBack()) webView.goBack();
    }

    @Override
    public void forward() {
        if (webView != null && webView.canGoForward()) webView.goForward();
    }

    @Override
    public void reload() {
        if (webView != null) webView.reload();
    }

    @Override
    public String extractText() {
        if (webView == null) return "";
        // Best-effort text extraction. Replaced by DOM extraction in V2.
        return webView.getTitle() == null ? "" : webView.getTitle();
    }

    @Override
    public String title() {
        return currentTitle;
    }

    @Override
    public void close() {
        if (webView != null) webView.destroy();
    }
}
