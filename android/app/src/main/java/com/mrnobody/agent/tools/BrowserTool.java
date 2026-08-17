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
            default:
                return ToolResult.fail("unknown browser action: " + action);
        }
    }
}
