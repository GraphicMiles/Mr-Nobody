package com.mrnobody.browser.update;

import org.json.JSONObject;

/**
 * One release's metadata, as published by the update server.
 *
 * <p>This class is deliberately paranoid. The server's payload is
 * untrusted input in the same sense as page content: anything that does not
 * match the contract is treated as "no update", never as an instruction.
 * The contract is metadata only — a version, notes, a download URL, a
 * flag, and integrity data. There is no code in it and none is executed.
 */
public final class UpdateInfo {

    public final String version;
    public final String releaseNotes;
    public final String downloadUrl;
    public final boolean required;
    /** Hex sha256 of the release APK, or "" when not yet published. */
    public final String sha256;
    /** Signing-certificate digest metadata, or "" when not yet published. */
    public final String signature;
    public final String publishedAt;

    private UpdateInfo(
            String version,
            String releaseNotes,
            String downloadUrl,
            boolean required,
            String sha256,
            String signature,
            String publishedAt) {
        this.version = version;
        this.releaseNotes = releaseNotes;
        this.downloadUrl = downloadUrl;
        this.required = required;
        this.sha256 = sha256;
        this.signature = signature;
        this.publishedAt = publishedAt;
    }

    /** Dotted triple of digits: "1.0.0". Anything else is not a version. */
    public static boolean isVersion(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.");
        if (parts.length != 3) return false;
        for (String part : parts) {
            if (part.isEmpty()) return false;
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') return false;
            }
        }
        return true;
    }

    /** 64 hex characters, case-insensitive. */
    public static boolean isSha256(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    /**
     * Parse a server payload.
     *
     * @return the release, or {@code null} when the payload is malformed or
     *     would be unsafe to act on. Callers must treat null as "no update".
     */
    public static UpdateInfo parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            String version = o.optString("latestVersion", "");
            String downloadUrl = o.optString("downloadUrl", "");
            if (!isVersion(version)) return null;
            // A non-HTTPS download URL would be a downgrade path the user
            // could be pushed down, so it is refused at the parse boundary.
            if (!downloadUrl.startsWith("https://")) return null;
            String sha256 = o.optString("sha256", "");
            // Checksum and signature are "where appropriate": they are
            // optional until release hosting is settled, but when present
            // they must be well-formed — a placeholder is rejected, not
            // displayed as if it were a real digest.
            if (!sha256.isEmpty() && !isSha256(sha256)) return null;
            return new UpdateInfo(
                    version,
                    o.optString("releaseNotes", ""),
                    downloadUrl,
                    o.optBoolean("required", false),
                    sha256,
                    o.optString("signature", ""),
                    o.optString("publishedAt", ""));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Component-wise numeric comparison. "1.10.0" is newer than "1.9.9" —
     * lexicographic comparison would get that backwards. Any malformed
     * input is simply never "newer".
     */
    public static boolean isNewer(String latest, String current) {
        if (!isVersion(latest) || !isVersion(current)) return false;
        String[] a = latest.split("\\.");
        String[] b = current.split("\\.");
        for (int i = 0; i < 3; i++) {
            int x = Integer.parseInt(a[i]);
            int y = Integer.parseInt(b[i]);
            if (x != y) return x > y;
        }
        return false;
    }
}
