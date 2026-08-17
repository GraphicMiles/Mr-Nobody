package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;
import com.mrnobody.agent.util.DdgHtmlParser;
import com.mrnobody.agent.util.SearchResult;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final ToolSpec SPEC = ToolSpec.named("search")
            .describedAs("Search the web and return parsed results (title, URL, snippet).")
            .tier(Tier.READ)
            .param(ParamSpec.string("q", true, "What to search for."))
            .returns(OutputSpec.of(SearchTool::render, "query", "results"))
            .timeout(20_000)
            .build();

    @Override
    public ToolSpec spec() {
        return SPEC;
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
            // The canonical value is the parsed structure. The page that
            // produced it never leaves this method.
            List<Object> rows = new ArrayList<>();
            for (SearchResult r : results) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("title", r.title);
                row.put("url", r.url);
                row.put("snippet", r.snippet);
                rows.add(row);
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("query", q);
            value.put("results", rows);
            return ToolResult.ok(value);
        } catch (Exception e) {
            return ToolResult.fail("search failed: " + e.getMessage());
        }
    }

    /**
     * The model-facing projection of the canonical value. Pure: it reads the
     * structure and writes text, and cannot reach the page the structure came
     * from.
     */
    private static String render(Map<String, Object> value) {
        StringBuilder sb = new StringBuilder();
        sb.append("Top results for \"").append(value.get("query")).append("\":\n");
        Object rows = value.get("results");
        int n = 1;
        if (rows instanceof List) {
            for (Object row : (List<?>) rows) {
                if (!(row instanceof Map)) continue;
                Map<?, ?> r = (Map<?, ?>) row;
                sb.append("\n").append(n++).append(". ").append(r.get("title")).append("\n");
                String url = String.valueOf(r.get("url"));
                String snippet = String.valueOf(r.get("snippet"));
                if (!url.isEmpty()) sb.append("   ").append(url).append("\n");
                if (!snippet.isEmpty()) sb.append("   ").append(truncate(snippet, 180)).append("\n");
            }
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
