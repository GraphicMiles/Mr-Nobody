package com.mrnobody.agent.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of a tool call.
 *
 * <p>A tool returns one <em>canonical value</em> — a plain structure of maps,
 * lists, strings and numbers. What the model reads and what the UI draws are
 * projections of that value ({@link OutputSpec}), not things a tool writes by
 * hand. Failures are returned, never thrown, so one tool's bad day never
 * crashes the agent.
 */
public final class ToolResult {

    private final boolean success;
    private final Map<String, Object> value;
    private final String error;
    private final String modelText;

    private ToolResult(boolean success, Map<String, Object> value, String error, String modelText) {
        this.success = success;
        this.value = value == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
        this.error = error;
        this.modelText = modelText;
    }

    /** Success carrying a structured value. */
    public static ToolResult ok(Map<String, Object> value) {
        return new ToolResult(true, value, null, null);
    }

    /** Success whose whole value is one piece of text (a status line, a page's text). */
    public static ToolResult okText(String text) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", text == null ? "" : text);
        return new ToolResult(true, value, null, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error == null ? "unknown error" : error, null);
    }

    /** The pipeline attaches the rendered model text once the value has passed its spec. */
    public ToolResult renderedAs(String text) {
        return new ToolResult(success, value, error, text);
    }

    public boolean isSuccess() { return success; }

    public boolean isError() { return !success; }

    /** The canonical structured value. Empty on failure. */
    public Map<String, Object> value() { return value; }

    public String error() { return error; }

    /**
     * What a model should read. The rendered projection when the pipeline has
     * produced one; otherwise a plain description of the value, so a direct
     * call in a test still returns something legible.
     */
    public String result() {
        if (!success) return null;
        if (modelText != null) return modelText;
        if (value.size() == 1 && value.containsKey("text")) return String.valueOf(value.get("text"));
        return OutputSpec.describe(value);
    }

    /** Convenience for the vertical slice: the thing to show the user. */
    public String display() {
        return success ? result() : ("Error: " + error);
    }
}
