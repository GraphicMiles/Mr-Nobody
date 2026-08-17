package com.mrnobody.agent.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One attempted tool call: what was asked, by whom, with which arguments.
 *
 * <p>It exists so that the record of an attempt can be written <em>before</em>
 * the attempt runs. A call that crashes the process still leaves evidence that
 * it was made.
 */
public final class ToolCall {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final String id;
    private final String tool;
    private final String action;
    private final Map<String, String> params;
    private final Tier tier;
    private final long createdAt;

    private ToolCall(String id, String tool, String action, Map<String, String> params,
                     Tier tier, long createdAt) {
        this.id = id;
        this.tool = tool;
        this.action = action;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        this.tier = tier;
        this.createdAt = createdAt;
    }

    public static ToolCall of(String tool, ToolRequest request, Tier tier) {
        String id = "call-" + SEQUENCE.incrementAndGet();
        return new ToolCall(id, tool, request.action(), request.params(), tier,
                System.currentTimeMillis());
    }

    public String id() { return id; }
    public String tool() { return tool; }
    public String action() { return action; }
    public Map<String, String> params() { return params; }
    public Tier tier() { return tier; }
    public long createdAt() { return createdAt; }

    /**
     * A one-line summary for a confirmation prompt or a log. Values are
     * truncated: an argument can be a whole page of text, and neither a
     * notification nor a log line should carry it.
     */
    public String summary() {
        StringBuilder sb = new StringBuilder(tool);
        if (!action.isEmpty() && !action.equals(tool)) sb.append('.').append(action);
        if (!params.isEmpty()) {
            sb.append('(');
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append('=').append(preview(e.getValue()));
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private static String preview(String value) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').trim();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }
}
