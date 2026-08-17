package com.mrnobody.agent.core;

import android.content.Context;

/**
 * The agent brain. V1 implements a deterministic engine (no LLM required);
 * V2 swaps in an LLM-backed engine behind the same interface. The engine owns
 * intent routing, planning, tool selection and verification.
 */
public interface AgentEngine {

    /**
     * Handle one user instruction end-to-end, updating the task as it goes.
     * Implementations must observe {@code cancellation} at their step
     * boundaries and while waiting on anything slow.
     */
    void run(Context context, Task task, Cancellation cancellation);

    /**
     * Run a single named tool.
     *
     * <p>This is the ONLY sanctioned way to invoke a tool. Callers must not
     * hold a {@link Tool} and call {@code execute} themselves: the guarded
     * pipeline (validation, policy, confirmation, timeout, error
     * normalisation, audit record) hangs off this method, and a call that
     * skips it skips all of that. `ToolCallPathTest` enforces it.
     */
    ToolResult callTool(Context context, String name, ToolRequest request);
}
