package com.mrnobody.browser.net;

import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.mrnobody.debug.ErrorLog;

import java.util.HashSet;
import java.util.Set;

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

    /** WebView releases profile ownership asynchronously after destroy(). */
    private static final long[] DELETE_RETRY_MS = {100L, 250L, 500L, 1_000L, 2_000L, 4_000L};
    private static final Set<String> DELETE_PENDING = new HashSet<>();

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
        return applyProfile(webView, PRIVATE_PROFILE);
    }

    /**
     * Bind {@code webView} to a named profile.
     *
     * <p>Generalises {@link #applyPrivate} for callers that own their own
     * session naming — agent tasks in particular, where each task wants its
     * own jar so one task cannot inherit or leak another's logins.
     *
     * <p>Must be called before the WebView navigates.
     *
     * @return true when the WebView is genuinely isolated. False means it is
     *         sharing the default jar and no caller may claim otherwise.
     */
    public static boolean applyProfile(WebView webView, String profileName) {
        if (webView == null) return false;
        if (profileName == null || profileName.trim().isEmpty()) return false;
        if (!isSupported()) return false;
        try {
            WebViewCompat.setProfile(webView, profileName);
            return true;
        } catch (IllegalStateException e) {
            // Already navigated — a programming error, and one that would
            // otherwise silently downgrade privacy.
            ErrorLog.record("setProfile after navigation; NOT isolated: " + e);
            return false;
        } catch (Throwable t) {
            ErrorLog.record("setProfile failed; NOT isolated: " + t);
            return false;
        }
    }

    /**
     * Delete a profile after WebView has actually released it.
     *
     * <p>{@code WebView.destroy()} returns before Chromium drops profile
     * ownership. Deleting in the same stack therefore reports a false
     * "profile still in use" failure. Retry quietly on the main queue and log
     * only if the profile remains live after the bounded retry window.
     */
    public static void destroyProfileWhenIdle(String profileName) {
        if (profileName == null || profileName.trim().isEmpty() || !isSupported()) return;
        String name = profileName.trim();
        synchronized (DELETE_PENDING) {
            if (!DELETE_PENDING.add(name)) return;
        }
        scheduleDelete(name, 0);
    }

    public static void destroyPrivateWhenIdle() {
        destroyProfileWhenIdle(PRIVATE_PROFILE);
    }

    private static void scheduleDelete(String profileName, int attempt) {
        long delay = DELETE_RETRY_MS[Math.min(attempt, DELETE_RETRY_MS.length - 1)];
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> tryDeleteWhenIdle(profileName, attempt), delay);
    }

    private static void tryDeleteWhenIdle(String profileName, int attempt) {
        try {
            // false also means "already absent", which is the desired state.
            ProfileStore.getInstance().deleteProfile(profileName);
            finishDelete(profileName);
        } catch (IllegalStateException inUse) {
            int next = attempt + 1;
            if (next < DELETE_RETRY_MS.length) {
                scheduleDelete(profileName, next);
            } else {
                finishDelete(profileName);
                ErrorLog.record("profile remained in use after cleanup retries: " + profileName);
            }
        } catch (Throwable t) {
            finishDelete(profileName);
            ErrorLog.record("profile cleanup failed for " + profileName + ": "
                    + t.getClass().getSimpleName());
        }
    }

    private static void finishDelete(String profileName) {
        synchronized (DELETE_PENDING) {
            DELETE_PENDING.remove(profileName);
        }
    }

    /** Visible for diagnostics and tests without exposing profile contents. */
    public static boolean isDeletionPending(String profileName) {
        synchronized (DELETE_PENDING) {
            return DELETE_PENDING.contains(profileName);
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
