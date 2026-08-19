package com.mrnobody.agent.util;

import java.util.List;
import java.util.Locale;

/**
 * Resolves which of a page's links is the file the user asked to download.
 *
 * <p>The gap this closes: the agent can search and read, and it has a download
 * tool, but nothing connected the two. "Download moci" found pages, read them,
 * and then had no URL to hand the downloader — so the model guessed one and the
 * download refused it. This class is the deterministic bridge: given the links
 * a page exposes, pick the one that is plainly a file.
 *
 * <p>Pure on purpose: the decision is a ranking of strings, so it is
 * unit-tested without a device or a network, and a wrong choice here is a wrong
 * file on someone's phone rather than a wrong sentence.
 */
public final class DownloadLinkResolver {

    /** Path extensions that identify a downloadable file in a link. */
    private static final String[] FILE_EXTENSIONS = {
            ".mkv", ".mp4", ".avi", ".mov", ".webm", ".mp3", ".m4a", ".flac",
            ".zip", ".rar", ".7z", ".tar", ".gz", ".iso", ".apk", ".pdf",
            ".epub", ".mobi", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".csv", ".torrent",
    };

    private DownloadLinkResolver() {
    }

    /**
     * True when {@code url} is an http(s) URL that is plainly a file, or a
     * download endpoint that does not advertise an extension.
     *
     * <p>Requiring {@code .mp4}/{@code .mkv} was the bug: CDNs, signed URLs
     * and {@code /download?id=} links are real files and were skipped, so the
     * agent reported "no downloadable file" on pages that had one.
     */
    public static boolean isDownloadable(String url) {
        if (url == null) return false;
        String raw = url.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;

        String path = pathOf(lower);
        // A download *page* named film.mkv.html is not a file. The watch-party
        // resolver learned this the hard way: those URLs returned HTML.
        if (path.endsWith(".html") || path.endsWith(".htm")
                || path.endsWith(".php") || path.endsWith(".aspx")
                || path.endsWith(".jsp") || path.endsWith(".asp")) {
            return hasDownloadSignal(lower, path);
        }
        for (String ext : FILE_EXTENSIONS) {
            if (path.endsWith(ext)) return true;
        }
        return hasDownloadSignal(lower, path);
    }

    /**
     * Path/query/host signals that a URL is a file even without an extension.
     * Conservative on purpose: a generic {@code /page} must stay a page.
     */
    static boolean hasDownloadSignal(String lowerUrl, String path) {
        if (lowerUrl.contains("export=download")
                || lowerUrl.contains("download=1")
                || lowerUrl.contains("download=true")
                || lowerUrl.contains("attachment=1")
                || lowerUrl.contains("dl=1")
                || lowerUrl.contains("dl=true")) {
            return true;
        }
        if (path.contains("/download") || path.contains("/dl/")
                || path.contains("/getfile") || path.contains("/file/download")
                || path.contains("/media/download")) {
            return true;
        }
        // Signed / ranged CDN objects: a long hex-ish last segment, no page suffix.
        String last = lastSegment(path);
        if (last.length() >= 16 && last.matches("[a-z0-9._-]{16,}")
                && (lowerUrl.contains("cdn.") || lowerUrl.contains("storage.")
                || lowerUrl.contains("s3.") || lowerUrl.contains("blob."))) {
            return true;
        }
        return false;
    }

    static String pathOf(String url) {
        String u = url;
        int scheme = u.indexOf("://");
        if (scheme >= 0) u = u.substring(scheme + 3);
        int slash = u.indexOf('/');
        u = slash >= 0 ? u.substring(slash) : "/";
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        return u;
    }

    static String lastSegment(String path) {
        if (path == null || path.isEmpty()) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * The best file to download from {@code candidates}, or null when none is
     * downloadable.
     *
     * <p>Ranking is deterministic: a candidate on the user's own named site
     * (e.g. "download it from nkiri.ink") wins; otherwise the first
     * downloadable candidate in page order.
     */
    public static String resolve(List<String> candidates, String preferredHost) {
        return resolve(candidates, preferredHost, null);
    }

    /**
     * Best file among {@code candidates}. A named host still wins; among
     * equals, a filename that matches {@code query} (the work the user
     * asked for) beats a random other file on the same page.
     */
    public static String resolve(List<String> candidates, String preferredHost,
                                 String query) {
        if (candidates == null) return null;
        String best = null;
        int bestScore = -1;
        String wantHost = preferredHost == null ? "" : preferredHost.toLowerCase(Locale.ROOT);
        if (wantHost.startsWith("www.")) wantHost = wantHost.substring(4);
        for (String c : candidates) {
            if (!isDownloadable(c)) continue;
            int score = 10;
            String host = hostOf(c);
            if (!wantHost.isEmpty() && wantHost.equals(host)) score += 100;
            if (query != null && !query.isEmpty()) {
                score += TitleMatch.score(filenameOf(c), query);
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    static String filenameOf(String url) {
        if (url == null) return "";
        String u = url;
        int cut = u.indexOf('?');
        if (cut >= 0) u = u.substring(0, cut);
        int slash = u.lastIndexOf('/');
        return slash >= 0 ? u.substring(slash + 1) : u;
    }

    /** The bare host of a URL, lower-cased with any {@code www.} removed. */
    static String hostOf(String url) {
        if (url == null) return "";
        String u = url;
        int scheme = u.indexOf("://");
        if (scheme >= 0) u = u.substring(scheme + 3);
        int cut = u.indexOf('/');
        if (cut >= 0) u = u.substring(0, cut);
        int colon = u.indexOf(':');
        if (colon >= 0) u = u.substring(0, colon);
        int at = u.indexOf('@');
        if (at >= 0) u = u.substring(at + 1);
        u = u.toLowerCase(Locale.ROOT);
        if (u.startsWith("www.")) u = u.substring(4);
        return u;
    }
}
