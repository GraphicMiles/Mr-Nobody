package com.mrnobody.browser.update;

import java.util.HashMap;
import java.util.Map;

/**
 * The pure computation behind the update badge: given the installed
 * version, the last successful payload, and the user's dismissal, what
 * should the UI show?
 *
 * <p>Separate from {@link UpdateChecker} on purpose — this class has no
 * Android imports, so the JVM test suite can pin the UX contract (badge
 * visibility, dismissal semantics, offline fallback) without a device.
 * The MethodChannel returns this map verbatim.
 */
public final class UpdateStatus {

    private UpdateStatus() {
    }

    /**
     * @param installed        this build's versionName
     * @param cachedJson       last successful payload, or ""
     * @param cachedTs         its epoch millis, 0 when never checked
     * @param dismissedVersion version the user dismissed, or ""
     * @param source           "none" | "cache" | "network"
     * @param networkFailed    a check was attempted and the server did not answer
     */
    public static Map<String, Object> compute(
            String installed,
            String cachedJson,
            long cachedTs,
            String dismissedVersion,
            String source,
            boolean networkFailed) {
        Map<String, Object> m = new HashMap<>();
        String dismissed = dismissedVersion == null ? "" : dismissedVersion;
        UpdateInfo info = UpdateInfo.parse(cachedJson);
        if (info == null) {
            m.put("installedVersion", installed == null ? "" : installed);
            m.put("latestVersion", "");
            m.put("updateAvailable", false);
            m.put("required", false);
            m.put("releaseNotes", "");
            m.put("downloadUrl", "");
            m.put("sha256", "");
            m.put("signature", "");
            m.put("publishedAt", "");
            m.put("lastCheckedAt", 0L);
            m.put("source", "none");
            m.put("dismissed", false);
            m.put("networkFailed", networkFailed);
            return m;
        }
        boolean available = UpdateInfo.isNewer(info.version, installed);
        boolean isDismissed = available && info.version.equals(dismissed);
        m.put("installedVersion", installed == null ? "" : installed);
        m.put("latestVersion", info.version);
        m.put("updateAvailable", available);
        // "required" only has meaning while an update is actually on offer.
        m.put("required", info.required && available);
        m.put("releaseNotes", info.releaseNotes);
        m.put("downloadUrl", info.downloadUrl);
        m.put("sha256", info.sha256);
        m.put("signature", info.signature);
        m.put("publishedAt", info.publishedAt);
        m.put("lastCheckedAt", cachedTs);
        m.put("source", source);
        m.put("dismissed", isDismissed);
        m.put("networkFailed", networkFailed);
        return m;
    }
}
