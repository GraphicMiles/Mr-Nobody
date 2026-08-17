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

/**
 * Drives a {@link BrowserEngine} (visible or headless) with typed actions.
 * The agent never touches engine internals; it goes through this tool.
 */
public final class BrowserTool implements Tool {

    private final BrowserEngine engine;

    public BrowserTool(BrowserEngine engine) {
        this.engine = engine;
    }

    /** Actions that only observe the page. Everything else changes it. */
    private static final java.util.Set<String> READ_ACTIONS =
            java.util.Set.of("fetch", "extract", "title");

    private static final ToolSpec SPEC = ToolSpec.named("browser")
            .describedAs("Open, navigate and extract text from a web page.")
            // The declared tier is the worst this tool can do; a read-only
            // action narrows it per call (see tierFor).
            .tier(Tier.WRITE)
            .param(ParamSpec.enumOf("action", false, "What to do with the page.",
                    "open", "fetch", "back", "forward", "reload", "extract", "title",
                    "click", "type", "scroll", "wait"))
            .param(ParamSpec.url("url", false, "Page to open or fetch."))
            .param(ParamSpec.string("selector", false, "CSS selector to click or type into."))
            .param(ParamSpec.text("text", false, "Text to type.", 4096))
            .param(ParamSpec.enumOf("direction", false, "Scroll direction.", "up", "down"))
            .param(ParamSpec.integer("ms", false, "Milliseconds to wait."))
            .param(ParamSpec.integer("timeout", false, "Page load budget in milliseconds."))
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

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        if (engine == null) return ToolResult.fail("no browser engine configured");
        String action = request.action();
        switch (action) {
            case "open":
                String url = request.param("url");
                if (url == null || url.isEmpty()) return ToolResult.fail("browser.open needs 'url'");
                engine.open(url);
                return value("open", "url", url, "status", "opened");
            case "fetch":
                // Load + extract in one blocking call (the agent's extraction path).
                String fetchUrl = request.param("url");
                if (fetchUrl == null || fetchUrl.isEmpty()) {
                    return ToolResult.fail("browser.fetch needs 'url'");
                }
                long timeout = parseLong(request.param("timeout"), 20_000);
                String text = engine.loadAndExtract(fetchUrl, timeout);
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
            case "title":
                return value("title", "title", engine.title());
            case "click": {
                String sel = request.param("selector");
                if (sel == null || sel.isEmpty()) return ToolResult.fail("browser.click needs 'selector'");
                return engine.click(sel)
                        ? value("click", "selector", sel, "status", "clicked")
                        : ToolResult.fail("no element matched " + sel);
            }
            case "type": {
                String sel = request.param("selector");
                String typedText = request.param("text");
                if (sel == null || sel.isEmpty()) return ToolResult.fail("browser.type needs 'selector'");
                if (typedText == null) typedText = "";
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

    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
