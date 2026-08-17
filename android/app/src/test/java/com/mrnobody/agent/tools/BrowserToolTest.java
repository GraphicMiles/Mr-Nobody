package com.mrnobody.agent.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.browser.BrowserEngine;
import com.mrnobody.agent.core.ToolResult;

import org.junit.Test;

/** JVM tests for the BrowserTool's routing (against a fake engine, no Android). */
public class BrowserToolTest {

    /** Fake engine that records the last URL opened. */
    static final class FakeEngine implements BrowserEngine {
        String opened;
        @Override public void open(String url) { opened = url; }
        @Override public void back() { }
        @Override public void forward() { }
        @Override public void reload() { }
        @Override public String extractText() { return "text"; }
        @Override public String title() { return "title"; }
        @Override public String loadAndExtract(String url, long timeoutMs) { return "body of " + url; }
        @Override public void close() { }
    }

    @Test
    public void openRequiresUrl() {
        BrowserTool tool = new BrowserTool(new FakeEngine());
        ToolResult r = tool.execute(null, com.mrnobody.agent.core.ToolRequest.of("open"));
        assertTrue(!r.isSuccess());
    }

    @Test
    public void fetchReturnsExtractedText() {
        FakeEngine engine = new FakeEngine();
        BrowserTool tool = new BrowserTool(engine);
        ToolResult r = tool.execute(null,
                com.mrnobody.agent.core.ToolRequest.of("fetch", "url", "https://example.com"));
        assertTrue(r.isSuccess());
        assertEquals("body of https://example.com", r.result());
    }

    @Test
    public void unknownActionFails() {
        BrowserTool tool = new BrowserTool(new FakeEngine());
        ToolResult r = tool.execute(null, com.mrnobody.agent.core.ToolRequest.of("explode"));
        assertTrue(!r.isSuccess());
    }
}
