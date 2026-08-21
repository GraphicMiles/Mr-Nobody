package com.mrnobody.agent.core;

import android.content.Context;

/**
 * A named, typed tool the agent can invoke.
 *
 * <p>A tool declares what it is ({@link ToolSpec}) and does one thing. It never
 * decides whether it is allowed to run, never renders its own model text, and
 * never throws for an expected failure — those belong to the pipeline that
 * calls it ({@code ToolPipeline}), which is the only thing that should.
 */
public interface Tool {

    /** Identity, capability tier, parameters and output shape. */
    ToolSpec spec();

    /**
     * Run one validated call and return its canonical value. Parameters have
     * already been checked against {@link ToolSpec#params()}; the tool still
     * owns semantic checks the spec cannot express.
     */
    ToolResult execute(Context context, ToolRequest request);

    /**
     * Cancellable execution seam. Existing short tools inherit the legacy
     * implementation; side-effecting/long-running tools override it and stop
     * their underlying work, not merely the wrapper Future.
     */
    default ToolResult execute(Context context, ToolRequest request, Cancellation cancellation) {
        return execute(context, request);
    }

    /** Stable tool name used in routing and logging. */
    default String name() {
        return spec().name();
    }

    /** One-line description surfaced to the planner / provider. */
    default String description() {
        return spec().description();
    }

    /**
     * The tier <em>this particular call</em> exercises. Defaults to the tool's
     * declared tier; a tool that both reads and acts (the browser reads a page
     * and clicks a button) narrows it per request so reading never needs
     * permission to act.
     */
    default Tier tierFor(ToolRequest request) {
        return spec().tier();
    }
}
