package com.mrnobody.browser.download;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Works out what a downloaded file should be called.
 *
 * <p>Android's {@code URLUtil.guessFileName} gives up quickly: a server that
 * says {@code application/octet-stream} and a URL ending in a redirect token
 * produce {@code downloadfile.bin} — which is how a .mkv arrives named .bin,
 * unopenable until the user renames it by hand.
 *
 * <p>This tries, in order: the name the server asked for
 * (Content-Disposition, including RFC 5987 {@code filename*}), then the URL's
 * own last path segment, then an extension derived from the MIME type. Pure
 * string handling, so every rule is unit-tested.
 */
public final class DownloadNaming {

    private static final String FALLBACK = "download";

    /** MIME → extension for types Android's own map is unreliable about. */
    private static final Map<String, String> EXTENSIONS = new LinkedHashMap<>();

    static {
        EXTENSIONS.put("video/x-matroska", "mkv");
        EXTENSIONS.put("video/mp4", "mp4");
        EXTENSIONS.put("video/webm", "webm");
        EXTENSIONS.put("video/quicktime", "mov");
        EXTENSIONS.put("video/x-msvideo", "avi");
        EXTENSIONS.put("video/mpeg", "mpeg");
        EXTENSIONS.put("audio/mpeg", "mp3");
        EXTENSIONS.put("audio/mp4", "m4a");
        EXTENSIONS.put("audio/ogg", "ogg");
        EXTENSIONS.put("audio/flac", "flac");
        EXTENSIONS.put("audio/wav", "wav");
        EXTENSIONS.put("image/jpeg", "jpg");
        EXTENSIONS.put("image/png", "png");
        EXTENSIONS.put("image/gif", "gif");
        EXTENSIONS.put("image/webp", "webp");
        EXTENSIONS.put("image/svg+xml", "svg");
        EXTENSIONS.put("application/pdf", "pdf");
        EXTENSIONS.put("application/zip", "zip");
        EXTENSIONS.put("application/x-7z-compressed", "7z");
        EXTENSIONS.put("application/x-rar-compressed", "rar");
        EXTENSIONS.put("application/vnd.rar", "rar");
        EXTENSIONS.put("application/gzip", "gz");
        EXTENSIONS.put("application/x-tar", "tar");
        EXTENSIONS.put("application/epub+zip", "epub");
        EXTENSIONS.put("application/json", "json");
        EXTENSIONS.put("text/plain", "txt");
        EXTENSIONS.put("text/csv", "csv");
        EXTENSIONS.put("text/html", "html");
        EXTENSIONS.put("application/vnd.android.package-archive", "apk");
        EXTENSIONS.put("application/x-bittorrent", "torrent");
        EXTENSIONS.put("application/x-subrip", "srt");
    }

    /** Extensions we trust when they appear in a URL, even with a vague MIME type. */
    private static final String KNOWN_EXTENSIONS =
            "mkv,mp4,webm,mov,avi,mpg,mpeg,m4v,ts,flv,wmv,"
                    + "mp3,m4a,aac,flac,wav,ogg,opus,"
                    + "jpg,jpeg,png,gif,webp,bmp,svg,heic,"
                    + "pdf,epub,mobi,djvu,txt,csv,json,xml,html,htm,md,"
                    + "zip,rar,7z,gz,tar,bz2,xz,iso,apk,aab,apks,xapk,exe,msi,dmg,deb,rpm,"
                    + "sh,bash,zsh,bat,cmd,ps1,jar,js,mjs,vbs,reg,dex,dll,so,"
                    + "srt,ass,sub,vtt,torrent,doc,docx,docm,xls,xlsx,xlsm,ppt,pptx,pptm";

    private DownloadNaming() {
    }

    /**
     * @param url                where the file is coming from
     * @param contentDisposition the header, if the server sent one
     * @param mimeType           the server's declared type, if any
     */
    public static String fileName(String url, String contentDisposition, String mimeType) {
        String fromHeader = fromContentDisposition(contentDisposition);
        if (fromHeader != null) {
            return ensureExtension(sanitize(fromHeader), mimeType);
        }

        String fromUrl = fromUrlPath(url);
        if (fromUrl != null) {
            return ensureExtension(sanitize(fromUrl), mimeType);
        }

        String extension = extensionForMime(mimeType);
        return extension == null ? FALLBACK + ".bin" : FALLBACK + "." + extension;
    }

    /** The name the server asked for: {@code filename*=} wins over {@code filename=}. */
    static String fromContentDisposition(String header) {
        if (header == null || header.trim().isEmpty()) return null;
        String extended = valueOf(header, "filename*");
        if (extended != null) {
            // RFC 5987: charset'language'percent-encoded-value
            int lastQuote = extended.lastIndexOf('\'');
            String encoded = lastQuote >= 0 ? extended.substring(lastQuote + 1) : extended;
            String decoded = decode(encoded);
            if (!decoded.isEmpty()) return decoded;
        }
        String plain = valueOf(header, "filename");
        if (plain != null && !plain.trim().isEmpty()) return plain.trim();
        return null;
    }

    private static String valueOf(String header, String key) {
        String lower = header.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(key.toLowerCase(Locale.ROOT) + "=");
        if (at < 0) return null;
        // "filename=" must not match inside "filename*=" when we asked for the plain one
        if ("filename".equals(key) && at > 0 && lower.charAt(at - 1) == '*') return null;
        String rest = header.substring(at + key.length() + 1).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end > 0 ? rest.substring(1, end) : rest.substring(1);
        }
        int semi = rest.indexOf(';');
        return semi >= 0 ? rest.substring(0, semi).trim() : rest;
    }

    /** The last path segment, when it looks like a file rather than a route. */
    static String fromUrlPath(String url) {
        if (url == null || url.isEmpty()) return null;
        String path;
        try {
            URI uri = new URI(url);
            path = uri.getPath();
        } catch (Exception e) {
            int query = url.indexOf('?');
            path = query >= 0 ? url.substring(0, query) : url;
        }
        if (path == null || path.isEmpty()) return null;
        int slash = path.lastIndexOf('/');
        String segment = slash >= 0 ? path.substring(slash + 1) : path;
        segment = decode(segment).trim();
        if (segment.isEmpty()) return null;
        String extension = extensionOf(segment);
        // A segment with a recognised extension is a filename; "watch" or a
        // signed token is not, and inventing a name from it helps nobody.
        return extension != null && isKnownExtension(extension) ? segment : null;
    }

    /** Add an extension when the name has none and the MIME type tells us one. */
    static String ensureExtension(String name, String mimeType) {
        String existing = extensionOf(name);
        if (existing != null && isKnownExtension(existing)) return name;
        String fromMime = extensionForMime(mimeType);
        if (fromMime == null) return existing != null ? name : name + ".bin";
        if (existing != null && existing.equalsIgnoreCase(fromMime)) return name;
        return name + "." + fromMime;
    }

    static String extensionForMime(String mimeType) {
        if (mimeType == null) return null;
        String type = mimeType.trim().toLowerCase(Locale.ROOT);
        int semi = type.indexOf(';');
        if (semi > 0) type = type.substring(0, semi).trim();
        if (type.isEmpty() || type.equals("application/octet-stream")
                || type.equals("binary/octet-stream")) {
            // The server is saying "bytes"; it has told us nothing.
            return null;
        }
        String known = EXTENSIONS.get(type);
        if (known != null) return known;
        int slash = type.indexOf('/');
        if (slash < 0) return null;
        String subtype = type.substring(slash + 1);
        if (subtype.startsWith("x-")) subtype = subtype.substring(2);
        if (subtype.startsWith("vnd.")) return null;
        return subtype.matches("[a-z0-9]{1,5}") ? subtype : null;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        String extension = name.substring(dot + 1);
        return extension.matches("[A-Za-z0-9]{1,6}") ? extension : null;
    }

    private static boolean isKnownExtension(String extension) {
        return ("," + KNOWN_EXTENSIONS + ",").contains("," + extension.toLowerCase(Locale.ROOT) + ",");
    }

    /**
     * Strip anything that would let a name escape its directory or break the
     * media store. A download must never be able to choose its own path.
     */
    static String sanitize(String name) {
        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(slash + 1);
        cleaned = cleaned.replaceAll("[\\x00-\\x1f\\x7f:*?\"<>|]", "_").trim();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) return FALLBACK;
        return cleaned.length() <= 120 ? cleaned : truncateKeepingExtension(cleaned);
    }

    private static String truncateKeepingExtension(String name) {
        String extension = extensionOf(name);
        if (extension == null) return name.substring(0, 120);
        String stem = name.substring(0, name.length() - extension.length() - 1);
        int keep = Math.max(1, 119 - extension.length());
        return stem.substring(0, Math.min(stem.length(), keep)) + "." + extension;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }
}
