package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.browser.BrowserEngine;
import com.mrnobody.agent.browser.PageAnchor;
import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;

import org.json.JSONArray;

/**
 * Drives a {@link BrowserEngine} (visible or headless) with typed actions.
 * The agent never touches engine internals; it goes through this tool.
 */
public final class BrowserTool implements Tool {

    private final BrowserEngine engine;

    public BrowserTool(BrowserEngine engine) {
        this.engine = engine;
    }

    /**
     * Actions that only observe the page. Everything else changes it.
     *
     * <p>{@code open} is navigation, not mutation — loading a URL to read it is
     * the same class of action as {@code fetch}, and charging it as a WRITE is
     * what made a simple "watch twitter" task pop an approval prompt for a page
     * the agent merely wanted to read. Reads must never ask permission, or the
     * agent nags the user for doing its job.
     */
    private static final java.util.Set<String> READ_ACTIONS =
            java.util.Set.of("open", "fetch", "extract", "title", "links");

    /** The last URL this tool navigated to, for anchoring. */
    private volatile String lastKnownUrl = "";

    private static final ToolSpec SPEC = ToolSpec.named("browser")
            .describedAs("Open, navigate and extract text from a web page.")
            // The declared tier is the worst this tool can do; a read-only
            // action narrows it per call (see tierFor).
            .tier(Tier.WRITE)
            .param(ParamSpec.enumOf("action", false, "What to do with the page.",
                    "open", "fetch", "back", "forward", "reload", "extract", "title", "links",
                    "click", "type", "scroll", "wait"))
            .param(ParamSpec.url("url", false, "Page to open or fetch."))
            .param(ParamSpec.string("selector", false, "CSS selector to click or type into."))
            .param(ParamSpec.text("text", false, "Text to type.", 4096))
            .param(ParamSpec.enumOf("direction", false, "Scroll direction.", "up", "down"))
            .param(ParamSpec.integer("ms", false, "Milliseconds to wait."))
            .param(ParamSpec.integer("timeout", false, "Page load budget in milliseconds."))
            .param(ParamSpec.text("anchorText", false,
                    "Page text the decision was based on; the action is refused if it moved.", 8192))
            .param(ParamSpec.url("anchorUrl", false, "URL the decision was based on."))
            .returns(OutputSpec.of(BrowserTool::render, "action"))
            .timeout(30_000)
            .build();

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Tier tierFor(ToolRequest request) {
        return READ_ACTIONS.contains(request.action()) ? Tier.READ : Tier.WRITE;
    }

    /**
     * Refuse an action when the page is no longer the one the agent read.
     *
     * <p>Opt-in per call: the caller passes the text it based its decision on.
     * Without it we cannot tell drift from replacement, and refusing every
     * unanchored action would break the visible-browser path that has no
     * prior read to anchor against.
     *
     * @return a reason to refuse, or null to proceed
     */
    private String checkAnchor(ToolRequest request) {
        String expected = request.param("anchorText");
        if (expected == null || expected.isEmpty()) return null;

        PageAnchor anchor = PageAnchor.of(request.param("anchorUrl"), expected);
        String reason = anchor.staleReason(currentUrl(), engine.extractText());
        if (reason == null) return null;

        // Refusing is recoverable -- the agent can re-read and decide again.
        // Clicking the wrong element is not.
        return "Refused: " + reason + ". Read the page again before acting on it.";
    }

    /**
     * Where this tool last navigated.
     *
     * <p>{@link BrowserEngine} exposes no current-URL accessor, so the anchor
     * compares against what we last opened. That is weaker than asking the
     * engine -- a page that redirected itself is not detected -- but it does
     * catch the case the anchor exists for: the agent navigating elsewhere
     * between reading and acting. Adding {@code url()} to the engine would
     * close the gap and is a wider change than this.
     */
    private String currentUrl() {
        return lastKnownUrl;
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        if (engine == null) return ToolResult.fail("no browser engine configured");
        String action = request.action();
        switch (action) {
            case "open":
                String url = request.param("url");
                if (url == null || url.isEmpty()) return ToolResult.fail("browser.open needs 'url'");
                engine.open(url);
                lastKnownUrl = url;
                return value("open", "url", url, "status", "opened");
            case "fetch":
                // Load + extract in one blocking call (the agent's extraction path).
                String fetchUrl = request.param("url");
                if (fetchUrl == null || fetchUrl.isEmpty()) {
                    return ToolResult.fail("browser.fetch needs 'url'");
                }
                long timeout = parseLong(request.param("timeout"), 20_000);
                String text = engine.loadAndExtract(fetchUrl, timeout);
                lastKnownUrl = fetchUrl;
                return value("fetch", "url", fetchUrl, "text", text);
            case "back":
                engine.back();
                return value("back", "status", "navigated back");
            case "forward":
                engine.forward();
                return value("forward", "status", "navigated forward");
            case "reload":
                engine.reload();
                return value("reload", "status", "reloaded");
            case "extract":
                return value("extract", "text", engine.extractText());
            case "links": {
                // Every anchor the rendered page exposes, as absolute URLs.
                // This is how the agent finds the file to download: it reads
                // the page, then asks for its links, rather than guessing a
                // URL the page never offered.
                String linkUrl = request.param("url");
                if (linkUrl == null || linkUrl.isEmpty()) {
                    return ToolResult.fail("browser.links needs 'url'");
                }
                long linkTimeout = parseLong(request.param("timeout"), 20_000);
                String json = engine.loadAndEvaluate(linkUrl, LINKS_SCRIPT, linkTimeout);
                lastKnownUrl = linkUrl;
                java.util.Map<String, Object> v = new java.util.LinkedHashMap<>();
                v.put("action", "links");
                v.put("url", linkUrl);
                v.put("links", parseLinks(json));
                return ToolResult.ok(v);
            }
            case "title":
                return value("title", "title", engine.title());
            case "click": {
                String sel = request.param("selector");
                if (sel == null || sel.isEmpty()) return ToolResult.fail("browser.click needs 'selector'");
                String stale = checkAnchor(request);
                if (stale != null) return ToolResult.fail(stale);
                return engine.click(sel)
                        ? value("click", "selector", sel, "status", "clicked")
                        : ToolResult.fail("no element matched " + sel);
            }
            case "type": {
                String sel = request.param("selector");
                String typedText = request.param("text");
                if (sel == null || sel.isEmpty()) return ToolResult.fail("browser.type needs 'selector'");
                if (typedText == null) typedText = "";
                String staleType = checkAnchor(request);
                if (staleType != null) return ToolResult.fail(staleType);
                return engine.type(sel, typedText)
                        ? value("type", "selector", sel, "status", "typed")
                        : ToolResult.fail("no element matched " + sel);
            }
            case "scroll": {
                String dir = request.param("direction", "down");
                return engine.scroll(dir)
                        ? value("scroll", "direction", dir, "status", "scrolled")
                        : ToolResult.fail("scroll failed");
            }
            case "wait": {
                long ms = parseLong(request.param("ms"), 1000);
                engine.waitFor(ms);
                return value("wait", "ms", String.valueOf(ms), "status", "waited");
            }
            default:
                return ToolResult.fail("unknown browser action: " + action);
        }
    }

    /** Build the canonical value: the action, plus whatever that action produced. */
    private static ToolResult value(String action, String... pairs) {
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("action", action);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            value.put(pairs[i], pairs[i + 1]);
        }
        return ToolResult.ok(value);
    }

    /** Model-facing projection: the page text when there is one, else a status line. */
    private static String render(java.util.Map<String, Object> value) {
        Object text = value.get("text");
        if (text != null) return String.valueOf(text);
        Object title = value.get("title");
        if (title != null) return String.valueOf(title);
        StringBuilder sb = new StringBuilder(String.valueOf(value.get("action")));
        Object status = value.get("status");
        if (status != null) sb.append(": ").append(status);
        Object url = value.get("url");
        if (url != null) sb.append(' ').append(url);
        Object selector = value.get("selector");
        if (selector != null) sb.append(' ').append(selector);
        return sb.toString();
    }

    /**
     * Collect the page's anchors as absolute URLs. Bounded at 200 so a page
     * with tens of thousands of links cannot flood the result.
     */
    private static final String LINKS_SCRIPT =
            "(function(){try{var out=[],seen={};var as=document.querySelectorAll('a[href]');"
            + "for(var i=0;i<as.length;i++){var u=as[i].href;"
            + "if(u&&u.indexOf('http')===0&&!seen[u]){seen[u]=1;out.push(u);}"
            + "if(out.length>=200)break;}return JSON.stringify(out);"
            + "}catch(e){return '[]'}})()";

    /** A JSON array of link strings, or an empty list on any failure. */
    private static java.util.List<String> parseLinks(String json) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if (!u.isEmpty()) out.add(u);
            }
        } catch (Exception ignored) {
            // A page that returned something unparseable simply has no links.
        }
        return out;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
