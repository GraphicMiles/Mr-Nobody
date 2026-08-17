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
        String lastClicked;
        String lastTyped;
        @Override public void open(String url) { opened = url; }
        @Override public void back() { }
        @Override public void forward() { }
        @Override public void reload() { }
        @Override public String extractText() { return "text"; }
        @Override public String title() { return "title"; }
        @Override public String loadAndExtract(String url, long timeoutMs) { return "body of " + url; }
        @Override public boolean click(String selector) { lastClicked = selector; return true; }
        @Override public boolean type(String selector, String text) { lastTyped = selector + ":" + text; return true; }
        @Override public boolean scroll(String direction) { return true; }
        @Override public void waitFor(long millis) { }
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
        // The canonical value carries the page text under a declared key; the
        // model-facing string is the pipeline's projection of it, not the
        // tool's prose.
        assertEquals("fetch", r.value().get("action"));
        assertEquals("body of https://example.com", r.value().get("text"));
    }

    @Test
    public void unknownActionFails() {
        BrowserTool tool = new BrowserTool(new FakeEngine());
        ToolResult r = tool.execute(null, com.mrnobody.agent.core.ToolRequest.of("explode"));
        assertTrue(!r.isSuccess());
    }

    @Test
    public void clickRequiresSelector() {
        BrowserTool tool = new BrowserTool(new FakeEngine());
        ToolResult r = tool.execute(null, com.mrnobody.agent.core.ToolRequest.of("click"));
        assertTrue(!r.isSuccess());
    }

    @Test
    public void clickDelegatesToEngine() {
        FakeEngine engine = new FakeEngine();
        BrowserTool tool = new BrowserTool(engine);
        ToolResult r = tool.execute(null,
                com.mrnobody.agent.core.ToolRequest.of("click", "selector", "#submit"));
        assertTrue(r.isSuccess());
        assertEquals("#submit", engine.lastClicked);
        assertEquals("click", r.value().get("action"));
    }

    @Test
    public void typeRequiresSelector() {
        BrowserTool tool = new BrowserTool(new FakeEngine());
        ToolResult r = tool.execute(null, com.mrnobody.agent.core.ToolRequest.of("type"));
        assertTrue(!r.isSuccess());
    }

    @Test
    public void typeDelegatesToEngine() {
        FakeEngine engine = new FakeEngine();
        BrowserTool tool = new BrowserTool(engine);
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("selector", "#q");
        params.put("text", "hello");
        ToolResult r = tool.execute(null, new com.mrnobody.agent.core.ToolRequest("type", params));
        assertTrue(r.isSuccess());
        assertEquals("#q:hello", engine.lastTyped);
        assertEquals("type", r.value().get("action"));
    }

    /** Reading a page must not need the permission that clicking one does. */
    @org.junit.Test
    public void readActionsAreReadTierAndInteractionsAreWrite() {
        BrowserTool tool = new BrowserTool(new FakeEngine());
        assertEquals(com.mrnobody.agent.core.Tier.READ,
                tool.tierFor(com.mrnobody.agent.core.ToolRequest.of("fetch", "url", "https://x.com")));
        assertEquals(com.mrnobody.agent.core.Tier.READ,
                tool.tierFor(com.mrnobody.agent.core.ToolRequest.of("extract")));
        assertEquals(com.mrnobody.agent.core.Tier.WRITE,
                tool.tierFor(com.mrnobody.agent.core.ToolRequest.of("click", "selector", "#a")));
        assertEquals(com.mrnobody.agent.core.Tier.WRITE,
                tool.tierFor(com.mrnobody.agent.core.ToolRequest.of("type", "selector", "#a")));
    }

    /** Through the pipeline, the model sees the rendered projection. */
    @org.junit.Test
    public void thePipelineRendersTheFetchedText() {
        BrowserTool tool = new BrowserTool(new FakeEngine());
        com.mrnobody.agent.core.ToolPipeline pipeline =
                new com.mrnobody.agent.core.ToolPipeline(
                        new com.mrnobody.agent.core.ToolPipeline.TierApproval());

        ToolResult r = pipeline.run(null, tool,
                com.mrnobody.agent.core.ToolRequest.of("fetch", "url", "https://example.com"));

        assertTrue(r.isSuccess());
        assertEquals("body of https://example.com", r.result());
    }
}
