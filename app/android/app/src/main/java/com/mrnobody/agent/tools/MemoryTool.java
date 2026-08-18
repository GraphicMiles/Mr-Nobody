package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;
import com.mrnobody.agent.memory.MemoryRank;
import com.mrnobody.browser.MrNobodyApp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's memory: search its own past tasks.
 *
 * <p>This is the explicit retrieval bridge — the agent can ask "what did I do
 * about this before" and get the relevant history back, rather than relying
 * only on the auto-injected digest. It reads the on-device task store, never
 * the network, and returns completed tasks only, ranked by
 * {@link MemoryRank}. Reading memory is a READ: it never writes, and it never
 * leaves the device.
 */
public final class MemoryTool implements Tool {

    private static final int MAX_HITS = 5;

    private static final ToolSpec SPEC = ToolSpec.named("memory")
            .describedAs("Search your own past tasks for related work.")
            .tier(Tier.READ)
            .param(ParamSpec.string("q", true, "What to remember."))
            .returns(OutputSpec.of(MemoryTool::render, "query", "hits"))
            .timeout(5_000)
            .build();

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        String query = request.param("q");
        if (query == null || query.trim().isEmpty()) {
            return ToolResult.fail("memory needs a 'q' parameter");
        }

        List<com.mrnobody.agent.core.Task> tasks;
        try {
            tasks = MrNobodyApp.tasks().recent(200);
        } catch (Throwable t) {
            return ToolResult.fail("memory unavailable: " + t.getMessage());
        }

        List<MemoryRank.Hit> hits = MemoryRank.search(query.trim(), tasks, MAX_HITS);

        List<Object> rows = new ArrayList<>();
        for (MemoryRank.Hit h : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("instruction", h.instruction);
            row.put("result", h.result == null ? "" : h.result);
            rows.add(row);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("query", query.trim());
        value.put("hits", rows);
        return ToolResult.ok(value);
    }

    /** Model-facing projection: the retrieved history, readable and citable. */
    private static String render(Map<String, Object> value) {
        StringBuilder sb = new StringBuilder();
        Object rows = value.get("hits");
        if (rows instanceof List && !((List<?>) rows).isEmpty()) {
            sb.append("Your past work related to this:\n");
            int n = 1;
            for (Object row : (List<?>) rows) {
                if (!(row instanceof Map)) continue;
                Map<?, ?> r = (Map<?, ?>) row;
                sb.append("\n[").append(n++).append("] ")
                        .append(String.valueOf(r.get("instruction"))).append("\n");
                String result = String.valueOf(r.get("result"));
                if (!result.isEmpty()) {
                    sb.append("    ").append(truncate(result.replaceAll("\\s+", " ").trim(), 200))
                            .append("\n");
                }
            }
        } else {
            sb.append("No past work related to this.");
        }
        return sb.toString().trim();
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
