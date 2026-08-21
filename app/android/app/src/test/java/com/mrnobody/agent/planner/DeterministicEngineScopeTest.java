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
 * Tool scope, end-to-end: the run decides which tools exist for it, the
 * scoped call path enforces it, and the host's own unscoped entry point is
 * untouched. Fakes record every call.
 */
public class DeterministicEngineScopeTest {

    private static final Context NO_CONTEXT = null;
    private static final String QUESTION = "eiffel tower construction history";

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

        @Override
        public synchronized ToolResult execute(Context context, ToolRequest request) {
            calls.add(request);
            return responder.apply(request);
        }
    }

    private static ToolResult page(String url, String text) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("url", url);
        v.put("status", 200);
        v.put("text", text);
        return ToolResult.ok(v);
    }

    private static String body(String url) {
        String tag = url.replaceAll("[^a-z0-9]", " ");
        return "The Eiffel Tower construction started in 1887 according to " + tag
                + " which covers those early years at length. "
                + "The history of the tower construction on " + tag
                + " also describes the 1889 opening for the world fair.";
    }

    private DeterministicEngine engine;
    private FakeTool search;
    private FakeTool http;
    private FakeTool browser;
    private FakeTool download;
    private FakeTool terminal;

    @Before
    public void setUp() {
        SiteMemory.reset();
        engine = new DeterministicEngine();

        search = new FakeTool(ToolSpec.named("search").describedAs("Fake search.")
                .tier(Tier.READ)
                .param(ParamSpec.string("q", true, "Query."))
                .param(ParamSpec.string("provider", false, "Engine."))
                .returns(OutputSpec.of(v -> "results", "results"))
                .timeout(5_000).build(),
                req -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (String u : new String[]{
                            "https://one.example/a", "https://two.example/b"}) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("title", "Result " + u);
                        row.put("url", u);
                        row.put("snippet", "About the eiffel tower construction history.");
                        rows.add(row);
                    }
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("results", rows);
                    return ToolResult.ok(v);
                });

        http = new FakeTool(ToolSpec.named("http").describedAs("Fake http.")
                .tier(Tier.READ)
                .param(ParamSpec.url("url", true, "URL."))
                .returns(OutputSpec.of(v -> String.valueOf(v.get("text")),
                        "url", "status", "text"))
                .timeout(5_000).build(),
                req -> page(req.param("url", ""), body(req.param("url", ""))));

        // Mirrors the real BrowserTool contract: only "action" is required,
        // so fetch and links results both validate.
        browser = new FakeTool(ToolSpec.named("browser").describedAs("Fake browser.")
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
                        v.put("links", new ArrayList<String>());
                    } else {
                        v.put("text", body(req.param("url", "")));
                    }
                    return ToolResult.ok(v);
                });

        download = new FakeTool(ToolSpec.named("download").describedAs("Fake download.")
                .tier(Tier.READ)
                .param(ParamSpec.url("url", true, "File."))
                .returns(OutputSpec.of(v -> "queued", "status"))
                .timeout(5_000).build(),
                req -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("status", "COMPLETED");
                    v.put("name", "file.png");
                    v.put("folder", "Downloads");
                    v.put("customFolder", Boolean.TRUE);
                    return ToolResult.ok(v);
                });

        terminal = new FakeTool(ToolSpec.named("terminal").describedAs("Fake terminal.")
                .tier(Tier.EXEC)
                .param(ParamSpec.string("cmd", false, "Command."))
                .returns(OutputSpec.of(v -> String.valueOf(v.get("out")), "out"))
                .timeout(5_000).build(),
                req -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("out", "ran");
                    return ToolResult.ok(v);
                });

        engine.registerTool(search);
        engine.registerTool(http);
        engine.registerTool(browser);
        engine.registerTool(download);
        engine.registerTool(terminal);
    }

    @After
    public void tearDown() {
        SiteMemory.reset();
    }

    private Task run(String instruction) {
        Task task = new Task(7L, instruction);
        engine.run(NO_CONTEXT, task, Cancellation.NONE);
        return task;
    }

    @Test
    public void aQuestionRunNeverTouchesDownloadOrTerminal() {
        Task task = run(QUESTION);
        assertEquals(Task.Status.COMPLETED, task.status());
        assertEquals(0, download.calls.size());
        assertEquals(0, terminal.calls.size());
    }

    @Test
    public void theScopedCallPathRefusesToolsOutsideTheRunsShape() {
        run(QUESTION); // leaves a research scope on the engine
        ToolResult refused = engine.callScoped(NO_CONTEXT, "terminal",
                ToolRequest.of("run", "cmd", "id"), Cancellation.NONE);
        assertFalse(refused.isSuccess());
        assertTrue(refused.error(), refused.error().contains("not in scope"));
        assertEquals("the tool itself was never reached", 0, terminal.calls.size());

        ToolResult alsoRefused = engine.callScoped(NO_CONTEXT, "download",
                ToolRequest.of("download", "url", "https://x.example/f.png"), Cancellation.NONE);
        assertFalse(alsoRefused.isSuccess());
        assertEquals(0, download.calls.size());
    }

    @Test
    public void aDownloadInstructionKeepsTheDownloadToolInScope() {
        run("download a png icon of a cat");
        ToolResult allowed = engine.callScoped(NO_CONTEXT, "download",
                ToolRequest.of("download", "url", "https://x.example/cat.png"), Cancellation.NONE);
        assertTrue(String.valueOf(allowed.error()), allowed.isSuccess());
        assertFalse("terminal stays out even for downloads",
                engine.callScoped(NO_CONTEXT, "terminal",
                        ToolRequest.of("run", "cmd", "id"), Cancellation.NONE).isSuccess());
    }

    @Test
    public void theHostsUnscopedEntryPointIsUnaffected() {
        run(QUESTION); // research scope active
        // The address-bar search goes through the public callTool and must
        // not inherit task scope (it is not a task step).
        ToolResult direct = engine.callTool(NO_CONTEXT, "terminal",
                ToolRequest.of("run", "cmd", "id"), Cancellation.NONE);
        assertTrue(String.valueOf(direct.error()), direct.isSuccess());
    }
}
