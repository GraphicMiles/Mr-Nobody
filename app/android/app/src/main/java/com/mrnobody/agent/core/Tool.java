package com.mrnobody.agent.core;

import android.content.Context;

/**
 * A named, typed tool the agent can invoke. Every tool is behind this interface
 * so the agent never talks to a concrete engine. The LLM (or deterministic
 * router) emits a structured request; the tool validates it before doing work.
 */
public interface Tool {

    /** Stable tool name used in routing and logging (e.g. "search", "http"). */
    String name();

    /** One-line description surfaced to the planner / provider. */
    String description();

    /**
     * Execute the tool. Implementations must validate their input, return a
     * {@link ToolResult} (never throw for expected failures), and never perform
     * work outside the app's sandbox.
     */
    ToolResult execute(Context context, ToolRequest request);
}
