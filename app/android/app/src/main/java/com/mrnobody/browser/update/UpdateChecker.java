package com.mrnobody.browser.update;

import android.content.Context;
import android.content.pm.PackageInfo;

import com.mrnobody.browser.MrNobodyApp;
import com.mrnobody.browser.core.Settings;
import com.mrnobody.browser.net.NetworkGate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The app's side of the update flow.
 *
 * <p>The model: the server publishes <em>metadata</em> about the latest
 * release. The app fetches it once per launch (quietly, off the main
 * thread, behind the normal network gate), caches the last successful
 * answer, and compares versions. That is the whole system — there is no
 * auto-download, no auto-install, and nothing here ever executes code
 * from the server. Installing a release is a signed APK the user starts
 * through Android's package installer, which verifies the signature.
 *
 * <p>Failure is quiet by design. An unreachable server, a slow cold start,
 * or a malformed payload all mean "show the last cached check" — browsing
 * must never wait on, or break because of, the update service.
 */
public final class UpdateChecker {

    /**
     * The single endpoint. Point this at the deployed service after it is
     * live on Render (see server/README.md) — it is the only place the URL
     * appears in the app.
     */
    public static final String UPDATE_URL =
            "https://mrnobody-updates.onrender.com/update.json";

    static final int CONNECT_TIMEOUT_MS = 5000;
    static final int READ_TIMEOUT_MS = 6000;
    static final int MAX_BODY_BYTES = 64 * 1024;

    private UpdateChecker() {
    }

    /** The running app's version, from its own package info. */
    public static String installedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Status straight from the cache — no network, no blocking. This is
     * what the Settings screen shows on open and what an offline launch
     * falls back to.
     */
    public static Map<String, Object> cachedStatus(Context context) {
        Settings s = MrNobodyApp.settings();
        String json = s.getLastUpdateCheck();
        return UpdateStatus.compute(
                installedVersion(context),
                json,
                s.getLastUpdateCheckTs(),
                s.getDismissedUpdateVersion(),
                json == null || json.isEmpty() ? "none" : "cache",
                false);
    }

    /**
     * One network round-trip against the update endpoint.
     *
     * <p>Runs through {@link NetworkGate} so the request honours the active
     * privacy route (a NOBODY-mode check goes through Tor like everything
     * else) and fails closed when that route is down.
     *
     * @return true when a fresh, valid payload was cached. On any failure
     *         the previous cache is left untouched.
     */
    public static boolean checkNow(Context context) {
        HttpURLConnection conn = null;
        try {
            conn = NetworkGate.openHttp(UPDATE_URL);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return false;
            byte[] body = readBounded(conn.getInputStream(), MAX_BODY_BYTES);
            String json = new String(body, StandardCharsets.UTF_8);
            if (UpdateInfo.parse(json) == null) return false;
            Settings s = MrNobodyApp.settings();
            s.setLastUpdateCheck(json);
            s.setLastUpdateCheckTs(System.currentTimeMillis());
            return true;
        } catch (Throwable t) {
            // Offline, DNS down, route fail-closed, timeout, bad JSON...
            // All of these mean "keep what we had".
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Status after a {@link #checkNow} call: fresh data, or the cache. */
    public static Map<String, Object> statusAfterCheck(Context context, boolean networkOk) {
        Settings s = MrNobodyApp.settings();
        String json = s.getLastUpdateCheck();
        return UpdateStatus.compute(
                installedVersion(context),
                json,
                s.getLastUpdateCheckTs(),
                s.getDismissedUpdateVersion(),
                json == null || json.isEmpty() ? "none" : (networkOk ? "network" : "cache"),
                !networkOk);
    }

    /**
     * "Remind me later": suppress the badge for exactly this release. A
     * newer version published later reappears, because the dismissal is
     * compared against the published version, not the concept of "update".
     */
    public static void dismiss(Context context, String version) {
        if (version == null || !UpdateInfo.isVersion(version)) return;
        MrNobodyApp.settings().setDismissedUpdateVersion(version);
    }

    private static byte[] readBounded(InputStream in, int max) throws java.io.IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > max) throw new IllegalStateException("body too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
