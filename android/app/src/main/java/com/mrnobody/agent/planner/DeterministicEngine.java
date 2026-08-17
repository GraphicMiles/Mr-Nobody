package com.mrnobody.agent.planner;

import android.content.Context;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.Tool;
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
 *   1. search
 *   2. HTTP fetch/extract
 *   3. headless browser (when the instruction names a URL, or HTTP came back empty)
 *   4. optional AI synthesis (only if a remote provider is enabled)
 *
 * V2 replaces the internals with LLM planning behind the same AgentEngine
 * interface; nothing here is rewritten.
 */
public final class DeterministicEngine implements AgentEngine {

    private static final String SYSTEM_PROMPT =
            "You are Mr Nobody, a privacy-respecting web assistant. "
            + "Answer the user's request using the provided context. "
            + "Be concise and factual. Do not invent sources.";

    private static final Pattern URL_IN_TEXT =
            Pattern.compile("(https?://[^\\s\"'<>]+)");

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public DeterministicEngine() {
        tools.put("search", new SearchTool());
        tools.put("http", new HttpTool());
    }

    /** Register an additional tool (e.g. a BrowserTool wrapping a headless engine). */
    public void registerTool(Tool tool) {
        tools.put(tool.name(), tool);
    }

    @Override
    public void run(Context context, Task task) {
        String instruction = task.instruction();
        task.setStatus(Task.Status.RUNNING);

        // 1. Search (lightest network-capable tool)
        task.setCurrentStep("Search");
        ToolResult search = runTool(context, "search", ToolRequest.of("search", "q", instruction));
        if (!search.isSuccess()) {
            fail(task, search);
            return;
        }

        // 2. Fetch/extract. If the instruction names a URL, drive the headless
        //    browser on it; otherwise try plain HTTP first.
        String namedUrl = findUrl(instruction);
        ToolResult content;
        if (namedUrl != null) {
            task.setCurrentStep("Open in headless browser");
            content = runTool(context, "browser",
                    ToolRequest.of("fetch", "url", namedUrl));
            if (!content.isSuccess()) content = fetchViaHttp(context, namedUrl);
        } else {
            task.setCurrentStep("Fetch & extract");
            content = fetchViaHttp(context, ddgHtmlUrl(instruction));
            if (!content.isSuccess() || content.result().trim().length() < 40) {
                // Plain fetch too thin — let the headless browser render it.
                task.setCurrentStep("Render in headless browser");
                ToolResult browser = runTool(context, "browser",
                        ToolRequest.of("fetch", "url", ddgHtmlUrl(instruction)));
                if (browser.isSuccess()) content = browser;
            }
        }

        // 3. Optional AI synthesis (remote provider only; local echoes).
        task.setCurrentStep("Synthesize");
        String contextText = search.result() + "\n\n" + content.display();
        AiProvider provider = MrNobodyApp.activeProvider();
        String answer = askProvider(provider, instruction, contextText);

        task.setResult(answer);
        task.setStatus(Task.Status.COMPLETED);
    }

    private ToolResult fetchViaHttp(Context context, String url) {
        return runTool(context, "http", ToolRequest.of("http", "url", url));
    }

    private ToolResult runTool(Context context, String name, ToolRequest request) {
        Tool tool = tools.get(name);
        if (tool == null) return ToolResult.fail("no tool named " + name);
        try {
            return tool.execute(context, request);
        } catch (Exception e) {
            return ToolResult.fail(name + " threw: " + e.getMessage());
        }
    }

    private void fail(Task task, ToolResult r) {
        task.setError(r.error());
        task.setStatus(Task.Status.FAILED);
    }

    private static String findUrl(String text) {
        Matcher m = URL_IN_TEXT.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String ddgHtmlUrl(String query) {
        return "https://html.duckduckgo.com/html/?q=" + query.replace(' ', '+');
    }

    private String askProvider(AiProvider provider, String instruction, String context) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = {"(no AI response)"};
        String prompt = instruction + "\n\nContext:\n" + truncate(context, 4000);
        try {
            provider.complete(SYSTEM_PROMPT, prompt, new AiProvider.CompletionCallback() {
                @Override public void onResult(String text) { out[0] = text; latch.countDown(); }
                @Override public void onError(String error) { out[0] = "AI error: " + error; latch.countDown(); }
            });
            latch.await(90, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
