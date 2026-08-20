package com.mrnobody.agent.tasks;

import com.mrnobody.agent.core.ApprovalDecision;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolResult;

import org.json.JSONObject;

import java.util.Collection;
import java.util.Map;

/**
 * Versioned, bounded payloads for the task event log.
 *
 * <p>The UI must render what happened, not reverse-engineer a sentence such as
 * {@code Http.fetch(url=...)}. These payloads keep the append-only event store
 * schema stable while making individual event details typed and extensible.
 * Unknown fields are ignored by older readers; legacy plain-text events remain
 * readable by the Flutter compatibility parser.
 *
 * <p>Only metadata reaches the log. Page bodies, model prompts, typed form
 * values and tool output are deliberately excluded: a progress trace is not a
 * second history database.
 */
public final class TaskEventDetail {

    public static final int VERSION = 1;

    private TaskEventDetail() {
    }

    /** A semantic activity selected by the planner/engine. */
    public static String activity(String label, String kind, String reason) {
        try {
            JSONObject o = base("activity");
            o.put("label", clean(label, 100));
            o.put("kind", clean(kind, 40));
            if (reason != null && !reason.trim().isEmpty()) {
                o.put("reason", clean(reason, 180));
            }
            return o.toString();
        } catch (Exception e) {
            return label == null ? "" : label;
        }
    }

    /** A tool attempt, recorded before execution. */
    public static String toolCall(ToolCall call) {
        if (call == null) return "";
        try {
            JSONObject o = base("tool_call");
            o.put("id", clean(call.id(), 60));
            o.put("tool", clean(call.tool(), 40));
            o.put("action", clean(call.action(), 40));
            o.put("tier", call.tier() == null ? "" : call.tier().name());

            // One safe subject is enough for the user-facing metric. Never log
            // browser `text`, form values, or arbitrary model arguments.
            String subject = subject(call.params());
            if (!subject.isEmpty()) o.put("subject", subject);
            String url = call.params().get("url");
            if (url != null && !url.trim().isEmpty()) o.put("url", clean(url, 300));
            return o.toString();
        } catch (Exception e) {
            // Old readers already understand this compact fallback.
            return call.summary();
        }
    }

    /** A bounded outcome paired to {@link #toolCall(ToolCall)} by call id. */
    public static String toolResult(ToolCall call, ToolResult result,
                                    ApprovalDecision decision, long durationMs) {
        try {
            JSONObject o = base("tool_result");
            if (call != null) {
                o.put("id", clean(call.id(), 60));
                o.put("tool", clean(call.tool(), 40));
                o.put("action", clean(call.action(), 40));
            }
            o.put("state", state(result, decision));
            o.put("durationMs", Math.max(0L, durationMs));

            if (result != null) {
                Map<String, Object> value = result.value();
                Metric metric = metric(value);
                if (metric.count >= 0) {
                    o.put("count", metric.count);
                    o.put("unit", metric.unit);
                }
                copyScalar(value, o, "url", 300);
                copyScalar(value, o, "name", 120);
                copyScalar(value, o, "status", 60);
                copyScalar(value, o, "provider", 80);
                copyNumber(value, o, "bytes");
                copyNumber(value, o, "total");
            }

            String reason = null;
            if (decision != null && decision.isDeny()) reason = decision.reason();
            if ((reason == null || reason.isEmpty()) && result != null && result.isError()) {
                reason = result.error();
            }
            if (reason != null && !reason.trim().isEmpty()) {
                o.put("reason", clean(reason, 200));
            }
            return o.toString();
        } catch (Exception e) {
            String tool = call == null ? "tool" : call.tool();
            boolean ok = result != null && result.isSuccess();
            return tool + (ok ? " ok" : " failed") + " in " + Math.max(0L, durationMs) + "ms";
        }
    }

    /** Snapshot of optional response modules so an older turn keeps its cards. */
    public static String presentation(String artifacts) {
        try {
            JSONObject o = base("turn_presentation");
            String value = artifacts == null ? "" : artifacts.trim();
            if (!value.isEmpty() && value.length() <= 20_000) {
                o.put("artifacts", value);
            }
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject base(String shape) throws Exception {
        JSONObject o = new JSONObject();
        o.put("v", VERSION);
        o.put("shape", shape);
        return o;
    }

    private static String state(ToolResult result, ApprovalDecision decision) {
        if (decision != null && decision.isDeny()) return "denied";
        if (result != null && result.needsApproval()) return "waiting";
        if (result != null && result.isSuccess()) return "done";
        return "failed";
    }

    private static String subject(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        // Deliberately omit `text`, `value`, `password`, and other form data.
        String[] safe = {"q", "url", "path", "file", "command", "selector", "name"};
        for (String key : safe) {
            String value = params.get(key);
            if (value != null && !value.trim().isEmpty()) return clean(value, 180);
        }
        return "";
    }

    private static void copyScalar(Map<String, Object> value, JSONObject out,
                                   String key, int max) throws Exception {
        if (value == null) return;
        Object v = value.get(key);
        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            out.put(key, clean(String.valueOf(v), max));
        }
    }

    private static void copyNumber(Map<String, Object> value, JSONObject out,
                                   String key) throws Exception {
        if (value == null) return;
        Object v = value.get(key);
        if (v instanceof Number) out.put(key, v);
    }

    private static Metric metric(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Metric.NONE;
        Object results = value.get("results");
        if (results instanceof Collection) return new Metric(((Collection<?>) results).size(), "candidates");
        Object hits = value.get("hits");
        if (hits instanceof Collection) return new Metric(((Collection<?>) hits).size(), "matches");
        Object links = value.get("links");
        if (links instanceof Collection) return new Metric(((Collection<?>) links).size(), "links");
        Object locs = value.get("locs");
        if (locs instanceof Collection) return new Metric(((Collection<?>) locs).size(), "locations");
        return Metric.NONE;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private static final class Metric {
        static final Metric NONE = new Metric(-1, "");
        final int count;
        final String unit;

        Metric(int count, String unit) {
            this.count = count;
            this.unit = unit;
        }
    }
}
