package com.mrnobody.agent.core;

/**
 * The outcome of a tool call. Carries a success flag, an optional result string,
 * and an optional error message. Tools return this instead of throwing, so a
 * failure in one tool never crashes the agent loop.
 */
public final class ToolResult {

    private final boolean success;
    private final String result;
    private final String error;

    private ToolResult(boolean success, String result, String error) {
        this.success = success;
        this.result = result;
        this.error = error;
    }

    public static ToolResult ok(String result) {
        return new ToolResult(true, result == null ? "" : result, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error == null ? "unknown error" : error);
    }

    public boolean isSuccess() {
        return success;
    }

    public String result() {
        return result;
    }

    public String error() {
        return error;
    }

    /** Convenience for the vertical slice: the thing to show the user. */
    public String display() {
        return success ? result : ("Error: " + error);
    }
}
