package com.mrnobody.browser.download;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Classifies files that should require an explicit user confirmation. */
public final class DownloadRisk {

    private static final Set<String> EXECUTABLE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "apk", "aab", "apks", "xapk", "exe", "msi", "msp", "com", "scr",
            "dll", "so", "dex", "jar", "dmg", "pkg", "deb", "rpm", "appimage",
            "bat", "cmd", "ps1", "sh", "bash", "zsh", "vbs", "vb", "reg",
            "js", "mjs", "docm", "xlsm", "pptm"
    ));

    private static final Set<String> EXECUTABLE_MIMES = new HashSet<>(Arrays.asList(
            "application/vnd.android.package-archive",
            "application/x-msdownload",
            "application/x-dosexec",
            "application/x-executable",
            "application/x-elf",
            "application/java-archive",
            "application/x-java-archive",
            "application/x-sh",
            "application/x-shellscript",
            "text/x-shellscript",
            "text/javascript",
            "application/javascript"
    ));

    public static final class Assessment {
        public final boolean requiresConfirmation;
        public final String reason;

        Assessment(boolean requiresConfirmation, String reason) {
            this.requiresConfirmation = requiresConfirmation;
            this.reason = reason == null ? "" : reason;
        }
    }

    private DownloadRisk() {
    }

    public static Assessment assess(String fileName, String mimeType) {
        return assess(fileName, mimeType, null);
    }

    public static Assessment assess(String fileName, String mimeType, String sourceUrl) {
        String extension = extension(fileName);
        String sourceExtension = extension(sourceUrl);
        String mime = normaliseMime(mimeType);

        if (EXECUTABLE_EXTENSIONS.contains(extension)
                || EXECUTABLE_EXTENSIONS.contains(sourceExtension)
                || EXECUTABLE_MIMES.contains(mime)) {
            return new Assessment(true,
                    "This file can install software, run code, or change the device.");
        }
        if ("application/octet-stream".equals(mime)
                && (extension.isEmpty() || "bin".equals(extension))) {
            return new Assessment(true,
                    "The website did not identify this binary file type.");
        }
        return new Assessment(false, "");
    }

    /**
     * A warning for a download whose transport is insecure (plain http://).
     * Distinct from file-type risk: even a benign file is exposed on the wire,
     * so the user should choose whether to continue. Returns null for https.
     */
    public static String cleartextReason(String url) {
        if (url == null) return null;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://")) return null;
        return "This file is on a plain, unencrypted HTTP connection. "
                + "Any data it carries can be read or altered by others on the "
                + "network. It is being sought only because you chose to continue.";
    }

    private static String extension(String name) {
        if (name == null) return "";
        String clean = name.toLowerCase(Locale.ROOT);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int dot = clean.lastIndexOf('.');
        if (dot < 0 || dot == clean.length() - 1) return "";
        return clean.substring(dot + 1).replaceAll("[^a-z0-9]", "");
    }

    private static String normaliseMime(String mime) {
        if (mime == null) return "";
        String value = mime.trim().toLowerCase(Locale.ROOT);
        int semicolon = value.indexOf(';');
        return semicolon >= 0 ? value.substring(0, semicolon).trim() : value;
    }
}
