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
import com.mrnobody.agent.util.NetworkTargetPolicy;
import com.mrnobody.browser.net.NetworkGate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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
        String requestedUrl = request.param("url");
        if (!NetworkGate.canConnect()) {
            return ToolResult.needsApproval("network", NetworkGate.blockedReason());
        }
        String outcomeHost = com.mrnobody.agent.util.Hosts.firstIn(requestedUrl);
        try {
            NetworkTargetPolicy.requirePublic(requestedUrl, NetworkGate.resolvesTargetsLocally());
            int code = 0;
            String body = "";
            String finalUrl = requestedUrl;
            Opened opened = openFollowingRedirects(requestedUrl);
            HttpURLConnection conn = opened.connection;
            finalUrl = opened.url;
            outcomeHost = com.mrnobody.agent.util.Hosts.firstIn(finalUrl);
            try {
                code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    com.mrnobody.agent.util.SiteMemory.recordHttpOutcome(outcomeHost, false);
                    long retryAfter = com.mrnobody.agent.util.FetchRetry.shouldRetry(code)
                            ? com.mrnobody.agent.util.FetchRetry.delayMs(
                                    0, conn.getHeaderField("Retry-After")) : 0L;
                    return ToolResult.fail(
                            com.mrnobody.agent.resilience.FailureClassifier.fromHttp(
                                    code, "HTTP " + code + " for " + finalUrl, retryAfter));
                }
                body = readBounded(conn.getInputStream());
            } finally {
                conn.disconnect();
            }
            PageKind.Kind kind = PageKind.classify(body);
            com.mrnobody.agent.util.SiteMemory.remember(outcomeHost, kind);
            String text = extract(finalUrl, kind, body);
            // Rule 6's evidence: did plain HTTP yield text the read loop can
            // actually use? This score ranks the next task's read candidates.
            com.mrnobody.agent.util.SiteMemory.recordHttpOutcome(outcomeHost,
                    !kind.needsBrowser() && com.mrnobody.agent.util.ReadableText.usable(text));
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("url", finalUrl);
            value.put("status", code);
            value.put("kind", kind.name());
            value.put("needsBrowser", kind.needsBrowser());
            value.put("preferBrowser", com.mrnobody.agent.util.SiteMemory.preferBrowser(outcomeHost));
            if (com.mrnobody.agent.util.RobotsRules.looksLikeRobots(body)) {
                com.mrnobody.agent.util.RobotsRules robots =
                        com.mrnobody.agent.util.RobotsRules.parse(body);
                value.put("sitemaps", robots.sitemaps());
                value.put("crawlDelay", robots.crawlDelaySeconds());
                if (text.isEmpty()) text = robots.toText();
            }
            if (com.mrnobody.agent.util.RobotsRules.looksLikeSitemap(body)) {
                value.put("locs", com.mrnobody.agent.util.RobotsRules.locsFrom(body));
            }
            String image = HtmlText.previewImage(body, finalUrl);
            if (!image.isEmpty()) value.put("image", image);
            value.put("truncated", text.length() > MAX_RESULT);
            value.put("text", truncate(text, MAX_RESULT));
            return ToolResult.ok(value);
        } catch (Exception e) {
            com.mrnobody.agent.util.SiteMemory.recordHttpOutcome(outcomeHost, false);
            return ToolResult.fail("http fetch failed: " + e.getMessage());
        }
    }

    private static final class Opened {
        final HttpURLConnection connection;
        final String url;

        Opened(HttpURLConnection connection, String url) {
            this.connection = connection;
            this.url = url;
        }
    }

    /** Follow a small redirect chain, revalidating and re-scoping cookies per hop. */
    private static Opened openFollowingRedirects(String start) throws Exception {
        String current = start;
        for (int redirects = 0; redirects <= 5; redirects++) {
            NetworkTargetPolicy.requirePublic(current, NetworkGate.resolvesTargetsLocally());
            String host = com.mrnobody.agent.util.Hosts.firstIn(current);
            if (!com.mrnobody.agent.util.HostRateLimit.tryAcquire(host)) {
                throw new java.io.IOException(com.mrnobody.agent.util.HostRateLimit.denyMessage(host));
            }
            HttpURLConnection conn = NetworkGate.openHttp(current);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", "MrNobody/1.0");
            conn.setInstanceFollowRedirects(false);
            String cookie = cookieHeader(current);
            if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            final int code;
            try {
                code = conn.getResponseCode();
            } catch (Exception e) {
                conn.disconnect();
                throw e;
            }
            if (!isRedirect(code)) return new Opened(conn, current);
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || location.trim().isEmpty()) {
                throw new java.io.IOException("redirect had no Location header");
            }
            if (redirects == 5) throw new java.io.IOException("too many redirects");
            current = new URL(new URL(current), location).toString();
        }
        throw new java.io.IOException("too many redirects");
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static String extract(String url, PageKind.Kind kind, String body) {
        if (com.mrnobody.agent.util.RobotsRules.looksLikeRobots(body)) {
            return com.mrnobody.agent.util.RobotsRules.parse(body).toText();
        }
        if (com.mrnobody.agent.util.RobotsRules.looksLikeSitemap(body)) {
            String map = com.mrnobody.agent.util.RobotsRules.sitemapToText(body);
            if (!map.isEmpty()) return map;
        }
        String host = com.mrnobody.agent.util.Hosts.firstIn(url);
        if (com.mrnobody.agent.util.XQuery.isXHost(host)) {
            String tweets = com.mrnobody.agent.util.XTimeline.toMarkdown(body);
            if (!tweets.isEmpty()) return tweets;
        }
        if (kind == PageKind.Kind.EMBEDDED_JSON) {
            String json = EmbeddedJson.readable(body);
            if (!json.isEmpty()) return json;
        }
        if (kind == PageKind.Kind.FEED) {
            String feed = FeedDiscover.toText(body);
            if (!feed.isEmpty()) return feed;
        }
        String article = HtmlText.article(body);
        return ensureText(article.isEmpty() ? HtmlText.toText(body) : article);
    }

    /**
     * Last line of the output contract: this tool promises markup-stripped
     * text, and a truncated or malformed document can defeat the structured
     * extractors above (observed on-device as "result carries raw markup in
     * \"text\""). If tags survived extraction, strip them mechanically rather
     * than smuggling the raw page through and failing the whole read.
     */
    static String ensureText(String text) {
        if (text == null || text.isEmpty()) return "";
        if (!looksLikeMarkup(text)) return text;
        String stripped = text
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]{0,300}>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        return stripped;
    }

    private static boolean looksLikeMarkup(String text) {
        String head = text.substring(0, Math.min(text.length(), 4_000));
        String lower = head.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("<html") || lower.contains("<!doctype")
                || lower.contains("<body") || lower.contains("<div")
                || lower.contains("<script")) {
            return true;
        }
        int closes = 0;
        int idx = 0;
        while ((idx = head.indexOf("</", idx)) >= 0) {
            closes++;
            idx += 2;
        }
        return closes >= 3;
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

    private static String cookieHeader(String url) {
        try {
            return com.mrnobody.browser.MrNobodyApp.accounts().headerForUrl(url);
        } catch (Throwable e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}

