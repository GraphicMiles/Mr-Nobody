package com.mrnobody.browser.net;

import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Applies a {@link ResourcePolicy} to a WebView.
 *
 * <p>The only place a grade becomes real behaviour. Each lever maps to a
 * single {@code WebSettings} call, so a benchmark can apply a grade and read
 * the settings straight back to prove it took effect — no inference, no
 * "should be off" claims.
 */
public final class ResourceControls {

    private ResourceControls() {
    }

    /**
     * Apply {@code policy} to {@code webView}. Idempotent; call before first
     * navigation so the settings take effect from the first request.
     */
    public static void apply(WebView webView, ResourcePolicy policy) {
        if (webView == null) return;
        WebSettings s = webView.getSettings();
        ResourcePolicy p = policy == null ? ResourcePolicy.BALANCED : policy;

        s.setMediaPlaybackRequiresUserGesture(p.gatesAutoplay());
        s.setLoadsImagesAutomatically(!p.disablesImages());
        s.setCacheMode(p.disablesCache() ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);
    }
}
