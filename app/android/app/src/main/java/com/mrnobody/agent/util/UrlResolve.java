package com.mrnobody.agent.util;

/**
 * Join a possibly-relative reference to a page URL.
 *
 * <p>Lifted from the watch-party scraper's {@code resolveUrl}: pages expose
 * {@code //cdn…}, {@code /file.mkv} and {@code episode/2} and a naive
 * concatenation either drops the host or doubles it. This is structure, not
 * a site list.
 */
public final class UrlResolve {

    private UrlResolve() {
    }

    /**
     * An absolute http(s) URL, or {@code null} when {@code ref} cannot be
     * turned into one.
     */
    public static String resolve(String ref, String baseUrl) {
        if (ref == null) return null;
        String src = ref.trim();
        if (src.isEmpty()) return null;
        src = src.replace("&amp;", "&").replace("\\/", "/");
        if (src.startsWith("https://") || src.startsWith("http://")) return src;
        if (src.startsWith("//")) return "https:" + src;
        if (baseUrl == null || baseUrl.isEmpty()) return null;
        try {
            java.net.URI base = java.net.URI.create(baseUrl);
            return base.resolve(src).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
