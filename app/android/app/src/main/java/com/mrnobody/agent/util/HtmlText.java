package com.mrnobody.agent.util;

/**
 * Best-effort HTML → plain-text extraction (pure Java, unit-testable).
 *
 * Used by the HTTP tool so a fetched document never surfaces as raw markup to
 * the user. This is NOT a browser renderer — it just strips tags/scripts and
 * decodes common entities so fetched content is readable. Actual page
 * rendering always goes through a BrowserEngine (visible WebView or headless).
 */
public final class HtmlText {

    private HtmlText() {
    }

    /**
     * Prefer the article body when the page has one. Home chrome and nav
     * otherwise drown the work the user asked about.
     */
    public static String article(String html) {
        if (html == null || html.isEmpty()) return "";
        String[] tags = {
                "article", "main",
        };
        for (String tag : tags) {
            String inner = firstElement(html, tag);
            if (inner != null) {
                String text = toText(inner);
                if (text.length() >= 40) return text;
            }
        }
        return toText(html);
    }

    private static String firstElement(String html, String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?is)<" + tag + "\\b[^>]*>([\\s\\S]*?)</" + tag + ">")
                .matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The page's own preview image: {@code og:image}, Twitter card, JSON-LD
     * {@code image}, or the first content {@code <img>} that is not chrome.
     * Relative URLs are resolved against {@code pageUrl}. Empty when none.
     */
    public static String previewImage(String html, String pageUrl) {
        if (html == null || html.isEmpty()) return "";
        String[] patterns = {
                "(?is)<meta[^>]+property=[\"']og:image(?::secure_url|:url)?[\"'][^>]+content=[\"']([^\"']+)[\"']",
                "(?is)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image(?::secure_url|:url)?[\"']",
                "(?is)<meta[^>]+name=[\"']twitter:image(?::src)?[\"'][^>]+content=[\"']([^\"']+)[\"']",
                "(?is)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+name=[\"']twitter:image(?::src)?[\"']",
                "(?is)<link[^>]+rel=[\"']image_src[\"'][^>]+href=[\"']([^\"']+)[\"']",
                "(?is)\"image\"\\s*:\\s*\"(https?:[^\"\\s]+)\"",
        };
        for (String pattern : patterns) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(html);
            if (m.find()) {
                String resolved = UrlResolve.resolve(decodeAttr(m.group(1)), pageUrl);
                if (usableImage(resolved)) return resolved;
            }
        }
        java.util.regex.Matcher imgs = java.util.regex.Pattern
                .compile("(?is)<img\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']")
                .matcher(html);
        while (imgs.find()) {
            String resolved = UrlResolve.resolve(decodeAttr(imgs.group(1)), pageUrl);
            if (usableImage(resolved) && !looksLikeChrome(resolved)) return resolved;
        }
        return "";
    }

    private static String decodeAttr(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&#38;", "&")
                .replace("&quot;", "\"").trim();
    }

    public static boolean usableImage(String url) {
        if (url == null || url.length() < 12) return false;
        String u = url.toLowerCase(java.util.Locale.ROOT);
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        if (u.startsWith("data:")) return false;
        return true;
    }

    static boolean looksLikeChrome(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        return u.contains("favicon") || u.contains("sprite") || u.contains("pixel")
                || u.contains("1x1") || u.contains("/icon") || u.contains("logo.")
                || u.endsWith(".svg") || u.contains("tracking") || u.contains("badge");
    }

    public static String toText(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = html;

        // drop scripts and styles entirely
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        // strip all remaining tags
        s = s.replaceAll("(?s)<[^>]*>", " ");
        // decode the common entities
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&ndash;", "-")
                .replace("&mdash;", "-");
        // collapse whitespace runs
        s = s.replaceAll("[ \t\r\f]+", " ");
        s = s.replaceAll("\\n\\s*\\n+", "\n");
        return s.trim();
    }
}
