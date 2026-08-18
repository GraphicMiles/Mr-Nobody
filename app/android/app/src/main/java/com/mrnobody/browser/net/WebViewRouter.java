package com.mrnobody.browser.net;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.mrnobody.debug.ErrorLog;

import java.util.concurrent.Executor;

/**
 * Applies a {@link NetworkRoute} to the WebView engine.
 *
 * <p>Separate from {@link NetworkGate} because the two mechanisms genuinely
 * are separate, and collapsing them would hide that. The gate proxies sockets
 * this app opens; this proxies the ones the engine opens. A route is only
 * fully in force when both have been told.
 *
 * <p><b>Process-wide.</b> {@code setProxyOverride} applies to every WebView in
 * the process — there is no per-WebView proxy, and Chromium has never offered
 * one. So this class has no per-tab entry point, deliberately: an API that
 * accepted a WebView would imply a guarantee the platform cannot make.
 *
 * <p><b>No addDirect().</b> The builder has a fallback rule that connects
 * directly when the proxy is unreachable. For a privacy route that is exactly
 * the wrong behaviour, so it is never added here. If Tor is down the page must
 * fail, not quietly load over the user's own address.
 */
public final class WebViewRouter {

    private static final Executor NOW = Runnable::run;

    private WebViewRouter() {
    }

    /** True when this device's WebView can have its proxy overridden. */
    public static boolean isSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Point the WebView at {@code route}.
     *
     * @return true when the engine now reflects the route. False means the
     *         caller must not tell the user they are protected — on an
     *         unsupported WebView a privacy route cannot be honoured at all,
     *         and the mode should be refused rather than half-applied.
     */
    public static boolean apply(NetworkRoute route) {
        String rule = route == null ? null : route.webViewProxyRule();

        if (rule == null || rule.isEmpty()) {
            return clear();
        }

        if (!isSupported()) {
            ErrorLog.record("proxy override unsupported by this WebView; "
                    + "route " + route.id() + " NOT applied to the browser");
            return false;
        }

        try {
            // No addDirect(): see the class comment. Rules only.
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule(rule)
                    .build();
            ProxyController.getInstance().setProxyOverride(config, NOW, () -> { });
            return true;
        } catch (Throwable t) {
            ErrorLog.record("failed to apply proxy " + rule + ": " + t);
            return false;
        }
    }

    /** Remove any override, returning the WebView to a direct connection. */
    public static boolean clear() {
        if (!isSupported()) return true; // nothing was ever applied
        try {
            ProxyController.getInstance().clearProxyOverride(NOW, () -> { });
            return true;
        } catch (Throwable t) {
            ErrorLog.record("failed to clear proxy override: " + t);
            return false;
        }
    }
}
