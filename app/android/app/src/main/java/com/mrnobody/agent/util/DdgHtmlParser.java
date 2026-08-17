package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses DuckDuckGo's HTML results page (html.duckduckgo.com/html/) into clean
 * SearchResult objects — title, real URL, snippet — discarding the navigation,
 * region list, filter bars and footer that surround the actual results.
 *
 * This is pure Java and unit-testable. It is deliberately conservative: if the
 * page structure changes, it returns fewer (or zero) results rather than
 * emitting garbage. It never returns raw markup.
 */
public final class DdgHtmlParser {

    // A result title link: <a ... class="result__a" href="...">Title</a>
    private static final Pattern RESULT_A = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // A snippet: <a ... class="result__snippet" ...>text</a>
    private static final Pattern SNIPPET = Pattern.compile(
            "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // The "display" URL shown under a result (already human-readable).
    private static final Pattern RESULT_URL = Pattern.compile(
            "<a[^>]*class=\"result__url\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private DdgHtmlParser() {
    }

    /** Extract up to {@code max} search results from DDG HTML. */
    public static List<SearchResult> parse(String html, int max) {
        List<SearchResult> results = new ArrayList<>();
        if (html == null || html.isEmpty()) return results;

        List<String> titles = new ArrayList<>();
        List<String> hrefs = new ArrayList<>();
        Matcher tm = RESULT_A.matcher(html);
        while (tm.find() && titles.size() < max) {
            hrefs.add(tm.group(1));
            titles.add(HtmlText.toText(tm.group(2)));
        }

        List<String> snippets = new ArrayList<>();
        Matcher sm = SNIPPET.matcher(html);
        while (sm.find() && snippets.size() < max) {
            snippets.add(HtmlText.toText(sm.group(1)));
        }

        // Fallback display URLs (only used if redirect decoding fails).
        List<String> displayUrls = new ArrayList<>();
        Matcher um = RESULT_URL.matcher(html);
        while (um.find() && displayUrls.size() < max) {
            displayUrls.add(HtmlText.toText(um.group(1)));
        }

        for (int i = 0; i < titles.size(); i++) {
            String realUrl = decodeRedirect(hrefs.get(i));
            if (realUrl == null || realUrl.isEmpty()) {
                realUrl = i < displayUrls.size() ? displayUrls.get(i) : "";
            }
            String snippet = i < snippets.size() ? snippets.get(i) : "";
            results.add(new SearchResult(titles.get(i), realUrl, snippet));
        }
        return results;
    }

    /** DDG wraps result URLs in a /l/?uddg=... redirect; extract the real URL. */
    static String decodeRedirect(String href) {
        if (href == null) return null;
        String h = href;
        int q = h.indexOf('?');
        if (q < 0) {
            // Some results are relative (e.g. //duckduckgo.com/...) — not usable.
            return h.startsWith("http") ? h : null;
        }
        String query = h.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if ("uddg".equalsIgnoreCase(pair.substring(0, eq))) {
                String encoded = pair.substring(eq + 1);
                try {
                    return java.net.URLDecoder.decode(encoded, "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Convenience: top 8 results. */
    public static List<SearchResult> parse(String html) {
        return parse(html, 8);
    }
}
