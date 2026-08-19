package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;
import com.mrnobody.agent.util.EmbeddedJson;
import com.mrnobody.agent.util.FeedDiscover;
import com.mrnobody.agent.util.HtmlText;
import com.mrnobody.agent.util.PageKind;
import com.mrnobody.browser.net.NetworkGate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fetch a URL over plain HTTP(S) and return readable text. This is a document
 * fetcher, NOT a browser — it strips markup and returns plain text. Actual page
 * rendering goes through the BrowserEngine. Runs synchronously (call on a
 * background thread).
 */
public final class HttpTool implements Tool {

    private static final int MAX_BYTES = 256 * 1024; // 256 KB bound on the body
    private static final int MAX_RESULT = 8000;      // bound on the returned text

    private static final ToolSpec SPEC = ToolSpec.named("http")
            .describedAs("Fetch a URL and return its readable text (bounded, markup-stripped).")
            .tier(Tier.READ)
            .param(ParamSpec.url("url", true, "The http(s) URL to fetch."))
            .returns(OutputSpec.of(
                    value -> String.valueOf(value.get("text")), "url", "status", "text"))
            .timeout(25_000)
            .build();

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        String url = request.param("url");
        try {
            HttpURLConnection conn = NetworkGate.openHttp(url);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", "MrNobody/1.0");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return ToolResult.fail("HTTP " + code + " for " + url);
            }
            String body = readBounded(conn.getInputStream());
            PageKind.Kind kind = PageKind.classify(body);
            String text = extract(kind, body);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("url", url);
            value.put("status", code);
            value.put("kind", kind.name());
            value.put("needsBrowser", kind.needsBrowser());
            value.put("truncated", text.length() > MAX_RESULT);
            value.put("text", truncate(text, MAX_RESULT));
            return ToolResult.ok(value);
        } catch (Exception e) {
            return ToolResult.fail("http fetch failed: " + e.getMessage());
        }
    }

    private static String extract(PageKind.Kind kind, String body) {
        if (kind == PageKind.Kind.EMBEDDED_JSON) {
            String json = EmbeddedJson.readable(body);
            if (!json.isEmpty()) return json;
        }
        if (kind == PageKind.Kind.FEED) {
            String feed = FeedDiscover.toText(body);
            if (!feed.isEmpty()) return feed;
        }
        String article = HtmlText.article(body);
        return article.isEmpty() ? HtmlText.toText(body) : article;
    }

    private static String readBounded(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
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

