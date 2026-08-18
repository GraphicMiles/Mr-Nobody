package com.mrnobody.browser.net;

import android.webkit.CookieManager;
import android.webkit.WebView;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.mrnobody.debug.ErrorLog;

/**
 * Real storage isolation for private tabs, where the device supports it.
 *
 * <p>The old claim — "isolated storage, cleared on close" — was not true.
 * Android WebView had no per-tab incognito profile, so private tabs shared the
 * process-wide cookie and storage jars, and all that was really enforced was
 * "no history written". {@code androidx.webkit} 1.9.0's multi-profile API
 * changes that: a {@link Profile} owns its own {@code CookieManager},
 * {@code WebStorage} and {@code ServiceWorkerController}, and deleting it
 * destroys the data.
 *
 * <p><b>Feature-detected, never assumed.</b> Multi-profile depends on the
 * installed System WebView, not on the Android API level, so a current OS with
 * an old WebView will not have it. {@link #isSupported()} is the only honest
 * basis for the UI claim, and callers must degrade the wording rather than the
 * check.
 *
 * <p><b>Ordering matters.</b> {@code setProfile} throws once a WebView has
 * navigated, so it has to happen at construction, before any {@code loadUrl}.
 */
public final class ProfileManager {

    /** Profile name for the ephemeral private session. */
    private static final String PRIVATE_PROFILE = "mrnobody-private";

    private ProfileManager() {
    }

    /** True when this device's WebView supports genuinely separate profiles. */
    public static boolean isSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Bind {@code webView} to the isolated private profile.
     *
     * <p>Must be called before the WebView navigates.
     *
     * @return true when the WebView is genuinely isolated. False means it is
     *         sharing the default jar and the UI must not claim otherwise.
     */
    public static boolean applyPrivate(WebView webView) {
        if (webView == null) return false;
        if (!isSupported()) return false;
        try {
            WebViewCompat.setProfile(webView, PRIVATE_PROFILE);
            return true;
        } catch (IllegalStateException e) {
            // Already navigated — a programming error, and one that would
            // otherwise silently downgrade privacy.
            ErrorLog.record("setProfile after navigation; tab NOT isolated: " + e);
            return false;
        } catch (Throwable t) {
            ErrorLog.record("setProfile failed; tab NOT isolated: " + t);
            return false;
        }
    }

    /**
     * Destroy the private profile and everything in it.
     *
     * <p>Called when the last private tab closes. No-op when unsupported,
     * where the existing clear-on-close path remains the only defence.
     */
    public static boolean destroyPrivate() {
        if (!isSupported()) return false;
        try {
            return ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE);
        } catch (IllegalStateException e) {
            // Thrown when the profile is still in use by a live WebView.
            ErrorLog.record("private profile still in use, not deleted: " + e);
            return false;
        } catch (Throwable t) {
            ErrorLog.record("deleteProfile failed: " + t);
            return false;
        }
    }

    /**
     * The cookie manager governing {@code webView}.
     *
     * <p>Exists because {@code CookieManager.getInstance()} always returns the
     * <em>default</em> profile's manager. Applying our third-party-cookie
     * policy through it would configure the wrong jar for an isolated tab —
     * the policy would appear to be set and would not be.
     */
    public static CookieManager cookiesFor(WebView webView) {
        if (webView != null && isSupported()) {
            try {
                return WebViewCompat.getProfile(webView).getCookieManager();
            } catch (Throwable t) {
                ErrorLog.record("profile cookie manager unavailable: " + t);
            }
        }
        return CookieManager.getInstance();
    }
}
