package com.mrnobody.browser.net;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.mrnobody.debug.ErrorLog;

/** Applies the process-wide WebView proxy override. */
public final class WebViewRouter {

    /** Run callbacks on the thread on which AndroidX reports completion. */
    private static final java.util.concurrent.Executor NOW = Runnable::run;

    /** Completion of an asynchronous WebView proxy operation. */
    public interface Completion {
        void complete(boolean success);
    }

    private WebViewRouter() {
    }

    /** Whether the installed System WebView supports process proxy overrides. */
    public static boolean isSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Start pointing the WebView at {@code route}.
     *
     * <p>The AndroidX operation is asynchronous. Acceptance here only means the
     * request was handed to the WebView; callers must remain fail-closed until
     * {@code completion} reports success.
     *
     * @return false when the request could not even be started
     */
    public static boolean apply(NetworkRoute route, Completion completion) {
        String rule = route == null ? null : route.webViewProxyRule();
        if (rule == null || rule.isEmpty()) return clear(completion);

        if (!isSupported()) {
            ErrorLog.record("proxy override unsupported by this WebView; route "
                    + route.id() + " NOT applied to the browser");
            complete(completion, false);
            return false;
        }

        try {
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule(rule)
                    .build();
            ProxyController.getInstance().setProxyOverride(config, NOW,
                    () -> complete(completion, true));
            return true;
        } catch (Throwable t) {
            ErrorLog.record("failed to apply proxy " + rule + ": " + t);
            complete(completion, false);
            return false;
        }
    }

    /** Backwards-compatible fire-and-forget call for non-protective cleanup. */
    public static boolean apply(NetworkRoute route) {
        return apply(route, null);
    }

    /** Start removing any override. Completion says when direct WebView access is live. */
    public static boolean clear(Completion completion) {
        if (!isSupported()) {
            complete(completion, true); // no override could have been installed
            return true;
        }
        try {
            ProxyController.getInstance().clearProxyOverride(NOW,
                    () -> complete(completion, true));
            return true;
        } catch (Throwable t) {
            ErrorLog.record("failed to clear proxy override: " + t);
            complete(completion, false);
            return false;
        }
    }

    /** Cleanup callers do not make a protection claim, so they need no callback. */
    public static boolean clear() {
        return clear(null);
    }

    private static void complete(Completion completion, boolean success) {
        if (completion == null) return;
        try {
            completion.complete(success);
        } catch (Throwable t) {
            ErrorLog.record("WebView proxy completion failed: " + t);
        }
    }
}
