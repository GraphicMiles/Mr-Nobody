package com.mrnobody.agent.planner;

import android.content.Context;

import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.tools.HttpTool;
import com.mrnobody.agent.tools.SearchTool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V1 agent brain — deterministic, no LLM required. Walks the lightest-tool
 * cascade (search → HTTP → headless browser) and stops at the first tool that
 * can finish the step. V2 replaces the internals with LLM planning behind the
 * same AgentEngine interface; nothing here is rewritten.
 *
 * Runs off the UI thread (network I/O); WebView-bound tools must be invoked
 * from the main thread by the caller where required.
 */
public final class DeterministicEngine implements AgentEngine {

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

        // Step 1 — search (lightest network-capable tool)
        task.setCurrentStep("Search");
        ToolResult search = runTool(context, "search", ToolRequest.of("search", "q", instruction));
        if (!search.isSuccess()) {
            fail(task, search);
            return;
        }

        // Step 2 — HTTP fetch/extract (still lighter than a full browser)
        task.setCurrentStep("Fetch & extract");
        ToolResult http = runTool(context, "http",
                ToolRequest.of("http", "url", ddgHtmlUrl(instruction)));

        String combined = search.result()
                + "\n\n" + (http.isSuccess() ? http.result() : "Fetch: " + http.error());
        task.setResult(combined);
        task.setStatus(Task.Status.COMPLETED);
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

    private static String ddgHtmlUrl(String query) {
        return "https://html.duckduckgo.com/html/?q=" + query.replace(' ', '+');
    }
}
