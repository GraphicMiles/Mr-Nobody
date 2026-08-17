package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.browser.BrowserEngine;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;

/**
 * Drives a {@link BrowserEngine} (visible or headless) with typed actions.
 * The agent never touches engine internals; it goes through this tool.
 */
public final class BrowserTool implements Tool {

    private final BrowserEngine engine;

    public BrowserTool(BrowserEngine engine) {
        this.engine = engine;
    }

    @Override
    public String name() {
        return "browser";
    }

    @Override
    public String description() {
        return "Open, navigate and extract text from a web page.";
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
                return ToolResult.ok("Opened " + url);
            case "fetch":
                // Load + extract in one blocking call (the agent's extraction path).
                String fetchUrl = request.param("url");
                if (fetchUrl == null || fetchUrl.isEmpty()) {
                    return ToolResult.fail("browser.fetch needs 'url'");
                }
                long timeout = parseLong(request.param("timeout"), 20_000);
                String text = engine.loadAndExtract(fetchUrl, timeout);
                return ToolResult.ok(text);
            case "back":
                engine.back();
                return ToolResult.ok("Navigated back");
            case "forward":
                engine.forward();
                return ToolResult.ok("Navigated forward");
            case "reload":
                engine.reload();
                return ToolResult.ok("Reloaded");
            case "extract":
                return ToolResult.ok(engine.extractText());
            case "title":
                return ToolResult.ok(engine.title());
            case "click": {
                String sel = request.param("selector");
                if (sel == null || sel.isEmpty()) return ToolResult.fail("browser.click needs 'selector'");
                return engine.click(sel)
                        ? ToolResult.ok("Clicked " + sel)
                        : ToolResult.fail("no element matched " + sel);
            }
            case "type": {
                String sel = request.param("selector");
                String typedText = request.param("text");
                if (sel == null || sel.isEmpty()) return ToolResult.fail("browser.type needs 'selector'");
                if (typedText == null) typedText = "";
                return engine.type(sel, typedText)
                        ? ToolResult.ok("Typed into " + sel)
                        : ToolResult.fail("no element matched " + sel);
            }
            case "scroll": {
                String dir = request.param("direction", "down");
                return engine.scroll(dir)
                        ? ToolResult.ok("Scrolled " + dir)
                        : ToolResult.fail("scroll failed");
            }
            case "wait": {
                long ms = parseLong(request.param("ms"), 1000);
                engine.waitFor(ms);
                return ToolResult.ok("Waited " + ms + "ms");
            }
            default:
                return ToolResult.fail("unknown browser action: " + action);
        }
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
