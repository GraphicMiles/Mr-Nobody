package com.mrnobody.agent.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A structured tool request. The agent/LLM produces this; the tool validates it.
 * Never pass arbitrary strings straight from a model into execution.
 */
public final class ToolRequest {

    private final String action;
    private final Map<String, String> params;

    public ToolRequest(String action, Map<String, String> params) {
        this.action = action == null ? "" : action;
        this.params = params == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public static ToolRequest of(String action) {
        return new ToolRequest(action, Collections.emptyMap());
    }

    public static ToolRequest of(String action, String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return new ToolRequest(action, m);
    }

    public String action() {
        return action;
    }

    public String param(String key) {
        return params.get(key);
    }

    public String param(String key, String fallback) {
        String v = params.get(key);
        return v == null ? fallback : v;
    }

    public Map<String, String> params() {
        return params;
    }
}
