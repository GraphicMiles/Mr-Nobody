package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.browser.BrowserEngine;
import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;
import com.mrnobody.agent.util.DdgHtmlParser;
import com.mrnobody.agent.util.SearchChallenge;
import com.mrnobody.agent.util.SearchProviders;
import com.mrnobody.agent.util.SearchResult;
import com.mrnobody.agent.util.SearchResultsJson;
import com.mrnobody.browser.MrNobodyApp;
import com.mrnobody.browser.net.NetworkGate;

import java.util.function.Supplier;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a web search and returns PARSED results — title, URL and snippet.
 *
 * <p>Two ways of asking, in order of cost:
 *
 * <ol>
 *   <li>A plain HTTPS fetch of DuckDuckGo's HTML endpoint. Cheap, no browser,
 *       and enough when it works.
 *   <li>The headless WebView, running the query on each engine in turn and
 *       reading results out of the rendered DOM. Slower, but JavaScript runs
 *       and the request looks like a browser, which is the difference between
 *       results and a challenge page.
 * </ol>
 *
 * <p>If every engine refuses, this returns a failure that says so. It never
 * returns an empty result set that reads like "there is nothing to find" —
 * that is what let a model invent five restaurants and cite four sources that
 * were never read.
 */
public final class SearchTool implements Tool {

    private static final int MAX_RESULTS = 6;
    private static final int MAX_BYTES = 512 * 1024;
    private static final long PER_ENGINE_TIMEOUT_MS = 12_000;

    private final Supplier<BrowserEngine> engines;

    /** Without an engine, only the cheap HTTP path is available. */
    public SearchTool() {
        this((Supplier<BrowserEngine>) null);
    }

    public SearchTool(BrowserEngine engine) {
        this(() -> engine);
    }

    public SearchTool(Supplier<BrowserEngine> engines) {
        this.engines = engines == null ? () -> null : engines;
    }

    private static final ToolSpec SPEC = ToolSpec.named("search")
            .describedAs("Search the web and return parsed results (title, URL, snippet).")
            .tier(Tier.READ)
            .param(ParamSpec.string("q", true, "What to search for."))
            .returns(OutputSpec.of(SearchTool::render, "query", "results"))
            .timeout(45_000)
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
        List<String> refused = new ArrayList<>();

        // 1. The cheap path.
        try {
            String html = fetch("https://html.duckduckgo.com/html/?q="
                    + SearchProviders.encode(q));
            List<SearchResult> results = DdgHtmlParser.parse(html, MAX_RESULTS);
            if (!results.isEmpty()) return value(q, results, "DuckDuckGo");
            if (SearchChallenge.isChallenge(html)) refused.add("DuckDuckGo");
        } catch (Exception e) {
            refused.add("DuckDuckGo (" + e.getMessage() + ")");
        }

        // 2. The browser path, engine by engine.
        BrowserEngine engine = engines.get();
        if (engine != null) {
            String preferred = safeSearchEngineSetting();
            for (SearchProviders.Provider provider : SearchProviders.chain(preferred)) {
                try {
                    String json = engine.loadAndEvaluate(
                            provider.url(q), provider.script(MAX_RESULTS), PER_ENGINE_TIMEOUT_MS);
                    List<SearchResult> results = SearchResultsJson.parse(json, MAX_RESULTS);
                    if (results.size() >= SearchProviders.ENOUGH) {
                        return value(q, results, provider.name);
                    }
                    if (!results.isEmpty()) {
                        // Something, but thin — keep it only if nothing better turns up.
                        return value(q, results, provider.name);
                    }
                    refused.add(provider.name);
                } catch (Exception e) {
                    refused.add(provider.name + " (" + e.getMessage() + ")");
                }
            }
        }

        return ToolResult.fail(refused.isEmpty()
                ? "No search results for: " + q
                : "No engine would answer \"" + q + "\". Tried: " + String.join(", ", refused)
                        + ". Nothing was searched, so nothing is being guessed.");
    }

    private static String safeSearchEngineSetting() {
        try {
            return MrNobodyApp.settings().getSearchEngine();
        } catch (Exception e) {
            return null; // core not up (tests) — the default chain is fine
        }
    }

    private static ToolResult value(String query, List<SearchResult> results, String provider) {
        List<Object> rows = new ArrayList<>();
        for (SearchResult r : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", r.title);
            row.put("url", r.url);
            row.put("snippet", r.snippet);
            rows.add(row);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("query", query);
        value.put("provider", provider);
        value.put("results", rows);
        return ToolResult.ok(value);
    }

    /**
     * The model-facing projection of the canonical value. Pure: it reads the
     * structure and writes text, and cannot reach the page it came from.
     * Sources are numbered so an answer can cite them and be checked.
     */
    private static String render(Map<String, Object> value) {
        StringBuilder sb = new StringBuilder();
        sb.append("Search results for \"").append(value.get("query")).append("\"");
        Object provider = value.get("provider");
        if (provider != null) sb.append(" (via ").append(provider).append(")");
        sb.append(":\n");
        Object rows = value.get("results");
        int n = 1;
        if (rows instanceof List) {
            for (Object row : (List<?>) rows) {
                if (!(row instanceof Map)) continue;
                Map<?, ?> r = (Map<?, ?>) row;
                sb.append("\n[").append(n++).append("] ").append(r.get("title")).append("\n");
                String url = String.valueOf(r.get("url"));
                String snippet = String.valueOf(r.get("snippet"));
                if (!url.isEmpty()) sb.append("    ").append(url).append("\n");
                if (!snippet.isEmpty()) sb.append("    ").append(truncate(snippet, 220)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection conn = NetworkGate.openHttp(url);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        // A plausible browser string: the endpoint answers a bare tool name
        // with a challenge page, which is not a useful "no results".
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/126.0.0.0 Mobile Safari/537.36");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
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
