package com.mrnobody.agent.planner;

import android.content.Context;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolPipeline;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.tools.HttpTool;
import com.mrnobody.agent.tools.SearchTool;
import com.mrnobody.browser.MrNobodyApp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V1 agent brain — deterministic by default, with an optional AI synthesis step.
 *
 * Lightest-tool cascade for a task:
 *   1. search (returns PARSED results — title/url/snippet)
 *   2. if the instruction names a URL, drive the headless browser on it
 *   3. optional AI synthesis (only if a remote provider is enabled)
 *
 * The deterministic path never dumps raw HTML or page scrapes: it shows the
 * parsed search results (or extracted page text), which is a clean, readable
 * answer. V2 replaces the internals with LLM planning behind the same
 * AgentEngine interface; nothing here is rewritten.
 */
public final class DeterministicEngine implements AgentEngine {

    private static final String SYSTEM_PROMPT =
            "You are Mr Nobody, a privacy-respecting web assistant. "
            + "Answer the user's request using the provided context. "
            + "Be concise and factual. Do not invent sources.";

    /** How long a remote provider gets before we give up on it. */
    private static final long PROVIDER_TIMEOUT_MS = 90_000L;

    /** How often that wait checks for a cancel request. */
    private static final long POLL_MS = 250L;

    private static final Pattern URL_IN_TEXT =
            Pattern.compile("(https?://[^\\s\"'<>]+)");

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** Every tool call goes through this — see {@link ToolPipeline}. */
    private final ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

    public DeterministicEngine() {
        tools.put("search", new SearchTool());
        tools.put("http", new HttpTool());
    }

    /** Register an additional tool (e.g. a BrowserTool wrapping a headless engine). */
    public void registerTool(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** Remove a tool — used when the user switches the terminal back off. */
    public void unregisterTool(String name) {
        tools.remove(name);
    }

    /** Whether a tool is currently available to the planner. */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    @Override
    public void run(Context context, Task task, Cancellation cancellation) {
        String instruction = task.instruction();
        task.setStatus(Task.Status.RUNNING);

        // Cancellation is observed between steps: the task stops in a state we
        // can describe, never halfway through one.
        if (stopped(task, cancellation)) return;

        // 1. Search — returns parsed results, never a page scrape.
        task.setCurrentStep("Search");
        ToolResult search = callTool(context, "search",
                ToolRequest.of("search", "q", instruction), cancellation);
        if (!search.isSuccess()) {
            fail(task, search);
            return;
        }
        if (stopped(task, cancellation)) return;

        // 2. If the instruction names a URL, also extract that page's text so
        //    the answer is grounded in the specific page the user asked about.
        String namedUrl = findUrl(instruction);
        String contextText = search.result();
        if (namedUrl != null) {
            task.setCurrentStep("Open page");
            ToolResult page = callTool(context, "browser",
                    ToolRequest.of("fetch", "url", namedUrl), cancellation);
            if (page.isSuccess() && page.result().trim().length() > 0) {
                contextText = contextText + "\n\nPage (" + namedUrl + "):\n"
                        + truncate(page.result(), 2000);
            }
        }

        // 3. Result synthesis. A remote provider may summarize; the local
        //    (deterministic) provider shows the parsed results directly.
        if (stopped(task, cancellation)) return;

        task.setCurrentStep("Summarize");
        AiProvider provider = MrNobodyApp.activeProvider();
        String answer;
        if (provider.isRemote()) {
            answer = askProvider(provider, instruction, truncate(contextText, 4000), cancellation);
        } else {
            answer = contextText;
        }
        if (stopped(task, cancellation)) return;

        task.setResult(truncate(answer, 4000));
        task.setStatus(Task.Status.COMPLETED);
    }

    /**
     * The single tool entry point (see {@link AgentEngine#callTool}): resolve
     * the tool, then hand the call to the pipeline, which owns validation,
     * policy, guards, confirmation, timeouts, output checking and the record.
     */
    @Override
    public ToolResult callTool(Context context, String name, ToolRequest request) {
        return callTool(context, name, request, Cancellation.NONE);
    }

    /** As above, but able to abandon a slow tool when the task is cancelled. */
    public ToolResult callTool(Context context, String name, ToolRequest request,
                               Cancellation cancellation) {
        Tool tool = tools.get(name);
        if (tool == null) return ToolResult.fail("no tool named " + name);
        return pipeline.run(context, tool, request, cancellation);
    }

    /** The pipeline, so the host can attach a confirmer and a recorder to it. */
    public ToolPipeline pipeline() {
        return pipeline;
    }

    /** Mark the task cancelled and stop, if a cancel request is outstanding. */
    private boolean stopped(Task task, Cancellation cancellation) {
        if (cancellation == null || !cancellation.isCancelled()) return false;
        task.setCurrentStep("");
        task.setStatus(Task.Status.CANCELLED);
        return true;
    }

    private void fail(Task task, ToolResult r) {
        task.setError(r.error());
        task.setStatus(Task.Status.FAILED);
        // The debug overlay is the only channel a user has for reporting a
        // failure — a task that fails silently there is a failure we never see.
        com.mrnobody.debug.ErrorLog.record("task " + task.id() + " failed: " + r.error());
    }

    private static String findUrl(String text) {
        Matcher m = URL_IN_TEXT.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Wait for a provider, in short slices so a cancel request is noticed in
     * under a second rather than after the whole timeout. A blocking
     * {@code await(90s)} here is what made "cancel" mean "cancel, eventually".
     */
    private String askProvider(AiProvider provider, String instruction, String context,
                               Cancellation cancellation) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = {"(no AI response)"};
        String prompt = instruction + "\n\nContext:\n" + truncate(context, 4000);
        try {
            provider.complete(SYSTEM_PROMPT, prompt, new AiProvider.CompletionCallback() {
                @Override public void onResult(String text) { out[0] = text; latch.countDown(); }
                @Override public void onError(String error) {
                    out[0] = "AI error: " + error;
                    com.mrnobody.debug.ErrorLog.record("AI provider: " + error);
                    latch.countDown();
                }
            });
            long deadline = System.currentTimeMillis() + PROVIDER_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (latch.await(POLL_MS, TimeUnit.MILLISECONDS)) return out[0];
                if (cancellation != null && cancellation.isCancelled()) return "(cancelled)";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
