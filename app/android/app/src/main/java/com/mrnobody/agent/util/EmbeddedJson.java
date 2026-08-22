package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Readable prose hidden in page-owned JSON.
 *
 * <p>Next.js {@code __NEXT_DATA__}, Nuxt, Redux SSR and JSON-LD are the
 * data the web-scraper skill finds in Phase 0 — before a browser. We do
 * not parse the whole graph; we lift string values that look like titles
 * and articles so the model sees the work, not a spinner.
 */
public final class EmbeddedJson {

    private static final Pattern SCRIPT_JSON = Pattern.compile(
            "<script[^>]*(?:id=[\"']__NEXT_DATA__[\"']"
                    + "|id=[\"']__NUXT_DATA__[\"']"
                    + "|type=[\"']application/ld\\+json[\"'])[^>]*>"
                    + "([\\s\\S]*?)</script>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STATE = Pattern.compile(
            "window\\.__(?:NUXT|INITIAL_STATE)__\\s*=\\s*(\\{[\\s\\S]*?\\});");

    private static final Pattern STRING = Pattern.compile(
            "\"((?:[^\"\\\\]|\\\\.){16,400})\"");

    private EmbeddedJson() {
    }

    /** Flattened prose from embedded JSON, or empty. */
    public static String readable(String html) {
        if (html == null || html.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String blob : blobs(html)) {
            for (String s : stringsOf(blob)) {
                if (!looksLikeProse(s)) continue;
                if (out.length() > 0) out.append('\n');
                out.append(unescape(s));
                if (out.length() > 6000) return out.toString();
            }
        }
        return out.toString().trim();
    }

    static List<String> blobs(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = SCRIPT_JSON.matcher(html);
        while (m.find() && out.size() < 8) out.add(m.group(1));
        Matcher s = STATE.matcher(html);
        while (s.find() && out.size() < 8) out.add(s.group(1));
        return out;
    }

    private static List<String> stringsOf(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = STRING.matcher(json);
        while (m.find() && out.size() < 80) out.add(m.group(1));
        return out;
    }

    // JSON-LD / schema metadata keys whose values are never article prose.
    private static final String[] META_KEYS = {
            "@type", "@context", "displayType", "headline", "description",
            "name", "url", "image", "datePublished", "dateModified", "author",
            "publisher", "mainEntityOfPage", "inLanguage", "articleBody",
            "articleSection", "keywords", "text", "title", "type", "id",
    };

    private static boolean looksLikeProse(String s) {
        if (s == null) return false;
        if (s.startsWith("http://") || s.startsWith("https://")) return false;
        if (s.startsWith("/") || s.contains("function(")) return false;
        // "displayType": "standard article" is metadata, not a sentence.
        for (String key : META_KEYS) {
            String k = "\"" + key + "\"";
            if (s.startsWith(k + ":") || s.startsWith(k + " :")) return false;
        }
        int letters = 0;
        int spaces = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) letters++;
            if (c == ' ') spaces++;
        }
        return letters >= 12 && spaces >= 2;
    }

    private static String unescape(String s) {
        // JSON escapes first, then the same transport cleaning prose evidence
        // gets, so a lifted string never carries tags, entities or markdown
        // into the answer.
        String out = s.replace("\\n", "\n").replace("\\\"", "\"")
                .replace("\\/", "/").replace("\\\\", "\\");
        return com.mrnobody.agent.util.Sanitize.prose(out);
    }
}
