package com.mrnobody.agent.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the JSON a results-page script produced into clean {@link SearchResult}
 * objects.
 *
 * <p>Cleaning matters as much as extracting: engines wrap destinations in
 * redirectors, hang tracking parameters off them, and repeat the same site
 * under several headlines. What the agent reads should be the places it can
 * actually go.
 */
public final class SearchResultsJson {

    private SearchResultsJson() {
    }

    public static List<SearchResult> parse(String json, int max) {
        return parse(json, max, true);
    }

    /** Platform searches may legitimately return several items from one host. */
    public static List<SearchResult> parse(String json, int max, boolean onePerHost) {
        List<SearchResult> results = new ArrayList<>();
        if (json == null) return results;
        String trimmed = json.trim();
        if (trimmed.isEmpty() || trimmed.equals("null")) return results;
        try {
            JSONArray array = new JSONArray(trimmed);
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < array.length() && results.size() < max; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String url = clean(item.optString("url", ""));
                String title = tidy(item.optString("title", ""));
                if (url.isEmpty() || title.isEmpty()) continue;

                String host = hostOf(url);
                // General research uses one result per site. A platform skill
                // such as YouTube needs several distinct items from one host.
                String key = onePerHost && !host.isEmpty() ? host : url;
                if (!seen.add(key)) continue;

                results.add(new SearchResult(title, url, tidy(item.optString("snippet", ""))));
            }
        } catch (Exception e) {
            return results;
        }
        return results;
    }

    /** Unwrap redirectors and drop tracking parameters. */
    static String clean(String rawUrl) {
        if (rawUrl == null) return "";
        String url = rawUrl.trim();
        if (url.isEmpty()) return "";

        // Google: /url?q=<real>&sa=...   DuckDuckGo: /l/?uddg=<real>
        String unwrapped = paramOf(url, "uddg");
        if (unwrapped == null) unwrapped = paramOf(url, "q");
        if (unwrapped == null) unwrapped = paramOf(url, "url");
        if (unwrapped != null && unwrapped.startsWith("http")) url = unwrapped;

        if (!url.startsWith("http://") && !url.startsWith("https://")) return "";
        return stripTracking(url);
    }

    private static String paramOf(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (!pair.substring(0, eq).equalsIgnoreCase(name)) continue;
            try {
                String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                return value.startsWith("http") ? value : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Remove the campaign parameters engines and sites bolt on. */
    static String stripTracking(String url) {
        int q = url.indexOf('?');
        if (q < 0) return url;
        String base = url.substring(0, q);
        StringBuilder kept = new StringBuilder();
        for (String pair : url.substring(q + 1).split("&")) {
            if (pair.isEmpty()) continue;
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.startsWith("utm_") || lower.equals("gclid") || lower.equals("fbclid")
                    || lower.equals("msclkid") || lower.equals("ref") || lower.equals("ved")
                    || lower.equals("usg") || lower.equals("sa") || lower.equals("ei")) {
                continue;
            }
            if (kept.length() > 0) kept.append('&');
            kept.append(pair);
        }
        return kept.length() == 0 ? base : base + "?" + kept;
    }

    /** Host without "www.", or empty when the URL is unusable. */
    public static String hostOf(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            if (host == null) return "";
            return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String tidy(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }
}
