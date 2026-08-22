package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;
import com.mrnobody.agent.util.SiteMemory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A download URL that is actually a landing page (…/film.mkv.html) must be
 * resolved to the real file, not fetched as a file and saved as HTML. This is
 * the on-device failure: the agent "downloaded" the page. End-to-end with the
 * real engine, fake tools recording what was fetched.
 */
public class DownloadResolutionTest {

    private static final Context NO_CONTEXT = null;

    static final class FakeTool implements Tool {
        final ToolSpec spec;
        final List<ToolRequest> calls = new ArrayList<>();
        java.util.function.Function<ToolRequest, ToolResult> responder;

        FakeTool(ToolSpec spec, java.util.function.Function<ToolRequest, ToolResult> responder) {
            this.spec = spec;
            this.responder = responder;
        }

        @Override public ToolSpec spec() { return spec; }
        @Override public Tier tierFor(ToolRequest request) { return Tier.READ; }
        @Override public synchronized ToolResult execute(Context context, ToolRequest request) {
            calls.add(request);
            return responder.apply(request);
        }
    }

    private DeterministicEngine engine;
    private FakeTool download;

    @Before
    public void setUp() {
        SiteMemory.reset();
        engine = new DeterministicEngine();

        FakeTool search = new FakeTool(ToolSpec.named("search").describedAs("Fake search.")
                .tier(Tier.READ)
                .param(ParamSpec.string("q", true, "Query."))
                .param(ParamSpec.string("provider", false, "Engine."))
                .returns(OutputSpec.of(v -> "results", "results"))
                .timeout(5_000).build(),
                req -> {
                    // A search is not needed for a named URL; return empty so the
                    // cascade still takes the named page path.
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("results", new ArrayList<>());
                    return ToolResult.ok(v);
                });
        engine.registerTool(search);

        // The named landing page returns HTML that links to the real file.
        FakeTool http = new FakeTool(ToolSpec.named("http").describedAs("Fake http.")
                .tier(Tier.READ)
                .param(ParamSpec.url("url", true, "URL."))
                .returns(OutputSpec.of(v -> String.valueOf(v.get("text")),
                        "url", "status", "text"))
                .timeout(5_000).build(),
                req -> page(req.param("url", ""),
                        "<html><body>Click <a href=\"https://cdn.example.com/Silo.S03E01.mkv\">download</a></body></html>"));
        engine.registerTool(http);

        // The browser harvests the page's links, exposing the real .mkv.
        // Mirrors the real BrowserTool contract: action + url + timeout, so a
        // "links" step sent by resolveDownload validates.
        FakeTool browser = new FakeTool(ToolSpec.named("browser").describedAs("Fake browser.")
                .tier(Tier.WRITE)
                .param(ParamSpec.enumOf("action", false, "Action.", "fetch", "links"))
                .param(ParamSpec.url("url", false, "URL."))
                .param(ParamSpec.integer("timeout", false, "Budget ms."))
                .param(ParamSpec.bool("images", false, "Collect img srcs."))
                .returns(OutputSpec.of(v -> String.valueOf(v.get("action")), "action"))
                .timeout(30_000).build(),
                req -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("action", req.action());
                    if ("links".equals(req.action())) {
                        List<String> links = new ArrayList<>();
                        links.add("https://cdn.example.com/Silo.S03E01.mkv");
                        v.put("links", links);
                    } else {
                        v.put("text",
                                "<html><body>Click <a href=\"https://cdn.example.com/Silo.S03E01.mkv\">download</a></body></html>");
                    }
                    return ToolResult.ok(v);
                });
        engine.registerTool(browser);

        // The download tool records what it was asked to fetch. Mirrors the
        // real DownloadTool contract: url required, referer optional (resolve
        // sends the page that exposed the link, for hotlink-protecting CDNs).
        download = new FakeTool(ToolSpec.named("download").describedAs("Fake download.")
                .tier(Tier.READ)
                .param(ParamSpec.url("url", true, "File."))
                .param(ParamSpec.url("referer", false, "Source page."))
                .returns(OutputSpec.of(v -> "queued", "status"))
                .timeout(5_000).build(),
                req -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("status", "COMPLETED");
                    v.put("name", DownloadName(req.param("url", "")));
                    v.put("folder", "Downloads");
                    v.put("customFolder", Boolean.TRUE);
                    return ToolResult.ok(v);
                });
        engine.registerTool(download);
    }

    private static String DownloadName(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    @After
    public void tearDown() {
        SiteMemory.reset();
    }

    private static ToolResult page(String url, String text) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("url", url);
        v.put("status", 200);
        v.put("text", text);
        return ToolResult.ok(v);
    }

    @Test
    public void aLandingPageUrlIsResolvedToTheRealFileNotSavedAsHtml() {
        Task task = new Task(31L,
                "download https://downloadwella.com/pttlauhsc2ap/Silo.S03E01.(THENKIRI.COM).mkv.html");
        engine.run(NO_CONTEXT, task, Cancellation.NONE);

        assertEquals("the task should complete once the file is found",
                Task.Status.COMPLETED, task.status());
        assertTrue("the real .mkv was downloaded",
                task.result().contains("Silo.S03E01.mkv"));
        assertFalse("the .html page must not be the downloaded file",
                task.result().contains("mkv.html"));
        // The downloader was asked for the resolved file, not the landing page.
        String fetched = download.calls.isEmpty() ? "" : download.calls.get(0).param("url", "");
        assertTrue("fetched " + fetched, fetched.endsWith(".mkv"));
        assertFalse(fetched, fetched.contains(".html"));
    }
}
