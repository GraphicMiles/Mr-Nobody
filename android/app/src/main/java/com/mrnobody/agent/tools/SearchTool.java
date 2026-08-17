package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.util.DdgHtmlParser;
import com.mrnobody.agent.util.SearchResult;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs a web search and returns PARSED results — title, URL and snippet — not
 * a page scrape. Queries DuckDuckGo's HTML endpoint directly (never proxied
 * through us) and extracts results with {@link DdgHtmlParser}.
 *
 * Runs synchronously; call on a background thread.
 */
public final class SearchTool implements Tool {

    private static final int MAX_RESULTS = 5;
    private static final int MAX_BYTES = 512 * 1024;

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "Search the web and return parsed results (title, URL, snippet).";
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        String query = request.param("q");
        if (query == null || query.trim().isEmpty()) {
            return ToolResult.fail("search requires a 'q' parameter");
        }
        String q = query.trim();
        try {
            String html = fetch("https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(q, "UTF-8"));
            List<SearchResult> results = DdgHtmlParser.parse(html, MAX_RESULTS);
            if (results.isEmpty()) {
                return ToolResult.fail("no results parsed for: " + q);
            }
            return ToolResult.ok(format(q, results));
        } catch (Exception e) {
            return ToolResult.fail("search failed: " + e.getMessage());
        }
    }

    private static String format(String query, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("Top results for \"").append(query).append("\":\n");
        int n = 1;
        for (SearchResult r : results) {
            sb.append("\n").append(n++).append(". ").append(r.title).append("\n");
            if (!r.url.isEmpty()) sb.append("   ").append(r.url).append("\n");
            if (!r.snippet.isEmpty()) sb.append("   ").append(truncate(r.snippet, 180)).append("\n");
        }
        return sb.toString().trim();
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent", "MrNobody/1.0");
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int total = 0;
            int n;
            while ((n = r.read(buf)) != -1) {
                int keep = Math.min(n, MAX_BYTES - total);
                if (keep <= 0) break;
                sb.append(buf, 0, keep);
                total += keep;
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
