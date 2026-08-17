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
