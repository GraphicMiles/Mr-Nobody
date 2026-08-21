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
            ".csv", ".torrent", ".txt", ".json", ".xml",
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".heic",
    };

    private DownloadLinkResolver() {
    }

    /** Extensions that identify an image file specifically. */
    private static final String[] IMAGE_EXTENSIONS = {
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg", ".heic",
    };

    /**
     * True when the instruction asks for an image (png, icon, wallpaper,
     * photo…). Such tasks harvest {@code <img>} sources as candidates, not
     * just anchor links — "download a png icon from pngtree" found 0 links in
     * 92.6 seconds because the files were in {@code img src}, which no anchor
     * ever pointed at.
     */
    public static boolean wantsImage(String instruction) {
        if (instruction == null) return false;
        String t = " " + instruction.toLowerCase(Locale.ROOT) + " ";
        String[] signals = {"png", "jpg", "jpeg", "svg", "gif", "webp",
                "icon", "icons", "wallpaper", "wallpapers", "photo", "photos",
                "image", "images", "picture", "pictures", "logo", "logos"};
        for (String s : signals) {
            if (t.contains(" " + s + " ") || t.contains(" " + s + "s ")
                    || t.contains("." + s + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The image extension the instruction names ({@code ".png"} for
     * "download a png icon"), or null when it wants an image generically.
     */
    public static String requestedImageExt(String instruction) {
        if (instruction == null) return null;
        String t = instruction.toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTENSIONS) {
            String name = ext.substring(1);
            if (t.matches(".*\\b" + name + "\\b.*")) return ext;
        }
        if (t.matches(".*\\bjpg\\b.*") || t.matches(".*\\bjpeg\\b.*")) return ".jpg";
        return null;
    }

    /** True when the URL's path plainly names an image file. */
    public static boolean isImage(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT).trim();
        String path = pathOf(lower);
        for (String ext : IMAGE_EXTENSIONS) {
            if (path.endsWith(ext)) return true;
        }
        // Icon CDNs declare the format in the query, not the path:
        // img.icons8.com/?size=100&id=…&format=png — device-observed as the
        // reason "download a png icon" found nothing downloadable.
        return queryImageExt(lower) != null;
    }

    /** The image extension the URL's query declares ("format=png" → ".png"), or null. */
    static String queryImageExt(String lowerUrl) {
        int q = lowerUrl.indexOf('?');
        if (q < 0) return null;
        String query = lowerUrl.substring(q + 1);
        for (String ext : IMAGE_EXTENSIONS) {
            String name = ext.substring(1);
            if (query.contains("format=" + name) || query.contains("ext=" + name)
                    || query.contains("type=" + name)) {
                return ext.equals(".jpeg") ? ".jpg" : ext;
            }
        }
        return null;
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
        // A download *page* named film.mkv.html is not a file: such URLs
        // return HTML, learned the hard way in an earlier resolver.
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
        // A query that declares an image format is a file endpoint even with
        // a bare path (icon CDNs: /?size=100&id=…&format=png).
        if (queryImageExt(lowerUrl) != null) return true;
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

    /**
     * Best image to download. A candidate that is plainly an image wins over
     * one that is merely downloadable; a candidate with the extension the
     * user named ("a png icon" → {@code .png}) wins over other images; the
     * named host and a filename matching the request still count, exactly as
     * in {@link #resolve(List, String, String)}.
     *
     * @param ext the extension the instruction names, or null for any image
     */
    public static String resolveImage(List<String> candidates, String preferredHost,
                                      String query, String ext) {
        if (candidates == null) return null;
        String best = null;
        int bestScore = -1;
        String wantHost = preferredHost == null ? "" : preferredHost.toLowerCase(Locale.ROOT);
        if (wantHost.startsWith("www.")) wantHost = wantHost.substring(4);
        for (String c : candidates) {
            if (!isDownloadable(c)) continue;
            int score = 10;
            String lower = c.toLowerCase(Locale.ROOT);
            if (isImage(c)) score += 40;
            if (ext != null && (pathOf(lower).endsWith(ext)
                    || ext.equals(queryImageExt(lower)))) {
                score += 60;
            }
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

    static String filenameOf(String url) {        if (url == null) return "";
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
