package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fetch a URL over plain HTTP(S) and return the body (bounded). Used for simple
 * "fetch and extract" steps where a full browser is overkill. Runs synchronously
 * — callers invoke it on a background thread.
 */
public final class HttpTool implements Tool {

    private static final int MAX_BYTES = 256 * 1024; // 256 KB bound on the body

    @Override
    public String name() {
        return "http";
    }

    @Override
    public String description() {
        return "Fetch a URL and return its body text (bounded).";
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        String url = request.param("url");
        if (url == null || url.isEmpty()) return ToolResult.fail("http requires a 'url'");
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return ToolResult.fail("only http(s) URLs are allowed");
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", "MrNobody/1.0");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return ToolResult.fail("HTTP " + code + " for " + url);
            }
            String body = readBounded(conn.getInputStream());
            return ToolResult.ok(body);
        } catch (Exception e) {
            return ToolResult.fail("http fetch failed: " + e.getMessage());
        }
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
}
