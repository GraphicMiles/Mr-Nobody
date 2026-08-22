package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.ai.ProviderSnapshot;
import com.mrnobody.agent.core.AgentRunContext;
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
 * The Tier 0 fast path: with a remote provider enabled, a direct action (a
 * file download, a terminal command) must not spend an LLM round-trip deciding
 * what to do. The deterministic router already knows, so the action runs
 * locally through the same guarded pipeline. A normal question must still reach
 * the provider (the fast path is narrow, not a bypass).
 */
public class DeterministicEngineFastPathTest {

    private static final Context NO_CONTEXT = null;

    /** Records every provider call; the fast path must make none of them. */
    private static final class RecordingProvider implements AiProvider {
        int calls = 0;

        @Override public String id() { return "scripted"; }
        @Override public String displayName() { return "Scripted"; }
        @Override public boolean isRemote() { return true; }
        @Override
        public void complete(String system, String user, CompletionCallback cb) {
            calls++;
            cb.onResult("{\"done\":true}");
        }
        @Override
        public RequestHandle streamCancellable(String system, String user, StreamCallback cb) {
            calls++;
            cb.onDone("Satoshi Nakamoto created Bitcoin.");
            return RequestHandle.NONE;
        }
    }

    private static final class FakeTool implements Tool {
        final ToolSpec spec;
        final List<ToolRequest> calls = new ArrayList<>();
        final java.util.function.Function<ToolRequest, ToolResult> responder;

        FakeTool(ToolSpec spec, java.util.function.Function<ToolRequest, ToolResult> responder) {
            this.spec = spec;
            this.responder = responder;
        }

        @Override public ToolSpec spec() { return spec; }
        @Override public Tier tierFor(ToolRequest request) { return spec.tier(); }

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
        download = new FakeTool(ToolSpec.named("download").describedAs("Fake download.")
                .tier(Tier.READ)
                .param(ParamSpec.url("url", true, "File."))
                .returns(OutputSpec.of(v -> "queued", "status"))
                .timeout(5_000).build(),
                req -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("status", "COMPLETED");
                    v.put("name", "file.zip");
                    v.put("folder", "Downloads");
                    v.put("customFolder", Boolean.TRUE);
                    return ToolResult.ok(v);
                });
        engine.registerTool(download);
    }

    @After
    public void tearDown() {
        SiteMemory.reset();
    }

    private static AgentRunContext remoteRun(Task task, AiProvider provider) {
        return new AgentRunContext(task.id(), task.runId(),
                new ProviderSnapshot("scripted", "", ""), new ArrayList<>(), provider, "");
    }

    @Test
    public void aDirectDownloadDoesNotConsultTheRemoteProvider() {
        RecordingProvider provider = new RecordingProvider();
        Task task = new Task(21L, "download https://example.com/file.zip");

        AgentRunContext bound = remoteRun(task, provider);
        AgentRunContext.bind(bound);
        try {
            engine.run(NO_CONTEXT, task, Cancellation.NONE);
        } finally {
            AgentRunContext.clear();
        }

        assertEquals(Task.Status.COMPLETED, task.status());
        assertEquals("the download ran exactly once", 1, download.calls.size());
        assertEquals("the downloader was given the named file",
                "https://example.com/file.zip",
                download.calls.get(0).param("url", ""));
        assertEquals("the remote model was never consulted", 0, provider.calls);
    }

    @Test
    public void aQuestionStillReachesTheRemoteProvider() {
        RecordingProvider provider = new RecordingProvider();
        Task task = new Task(22L, "who created bitcoin");

        AgentRunContext bound = remoteRun(task, provider);
        AgentRunContext.bind(bound);
        try {
            engine.run(NO_CONTEXT, task, Cancellation.NONE);
        } finally {
            AgentRunContext.clear();
        }

        assertTrue("a question is not fast-pathed; the provider is consulted",
                provider.calls > 0);
        assertEquals("a question never opens the download tool", 0, download.calls.size());
    }
}
