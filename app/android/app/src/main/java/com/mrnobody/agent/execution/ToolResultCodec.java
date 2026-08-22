package com.mrnobody.agent.execution;

import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.resilience.FailureKind;
import com.mrnobody.agent.resilience.OperationFailure;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned JSON representation of a replayable tool result. */
final class ToolResultCodec {

    private static final int VERSION = 1;
    private static final int MAX_JSON_CHARS = 512 * 1024;

    private ToolResultCodec() {
    }

    static String encode(ToolResult result) {
        if (result == null) return null;
        try {
            JSONObject root = new JSONObject();
            root.put("v", VERSION);
            root.put("success", result.isSuccess());
            root.put("awaitingApproval", result.needsApproval());
            if (result.error() != null) root.put("error", result.error());
            putFailure(root, result.failure());
            if (result.isSuccess() && result.result() != null) {
                root.put("modelText", result.result());
            }
            root.put("value", toJson(result.value()));
            String json = root.toString();
            if (json.length() <= MAX_JSON_CHARS) return json;
            return fallback(result, "result exceeded the ledger bound");
        } catch (Exception e) {
            return fallback(result, "result could not be encoded");
        }
    }

    static ToolResult decode(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("v", 0) != VERSION) return null;
            boolean success = root.optBoolean("success", false);
            boolean waiting = root.optBoolean("awaitingApproval", false);
            String error = root.has("error") ? root.optString("error", null) : null;
            String modelText = root.has("modelText")
                    ? root.optString("modelText", null) : null;
            Object raw = root.opt("value");
            Map<String, Object> value = raw instanceof JSONObject
                    ? object((JSONObject) raw) : new LinkedHashMap<>();
            OperationFailure failure = readFailure(root, error);
            return ToolResult.restore(success, value, error, modelText, waiting, failure);
        } catch (Exception e) {
            return null;
        }
    }

    private static void putFailure(JSONObject root, OperationFailure failure) throws Exception {
        if (failure == null) return;
        JSONObject out = new JSONObject();
        out.put("kind", failure.kind.name());
        out.put("status", failure.statusCode);
        out.put("retryAfterMs", failure.retryAfterMs);
        out.put("retryable", failure.retryable);
        out.put("ambiguous", failure.ambiguous);
        root.put("failure", out);
    }

    private static OperationFailure readFailure(JSONObject root, String message) {
        try {
            JSONObject value = root.optJSONObject("failure");
            if (value == null) return null;
            FailureKind kind;
            try { kind = FailureKind.valueOf(value.optString("kind", "UNKNOWN")); }
            catch (Exception e) { kind = FailureKind.UNKNOWN; }
            return new OperationFailure(kind, message, value.optInt("status", 0),
                    value.optLong("retryAfterMs", 0L),
                    value.optBoolean("retryable", false),
                    value.optBoolean("ambiguous", false));
        } catch (Exception e) {
            return null;
        }
    }

    private static String fallback(ToolResult result, String note) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", VERSION);
            root.put("success", result != null && result.isSuccess());
            root.put("awaitingApproval", result != null && result.needsApproval());
            if (result != null && result.error() != null) {
                root.put("error", truncate(result.error(), 8_000));
            }
            if (result != null) putFailure(root, result.failure());
            String rendered = result == null ? "" : result.result();
            if (rendered != null) root.put("modelText", truncate(rendered, 32_000));
            JSONObject value = new JSONObject();
            value.put("text", rendered == null ? "" : truncate(rendered, 32_000));
            value.put("ledgerNote", note);
            root.put("value", value);
            return root.toString();
        } catch (Exception impossible) {
            // Fixed primitive fields should always encode under org.json.
            return "{\"v\":1,\"success\":false,\"awaitingApproval\":false,"
                    + "\"error\":\"execution result unavailable\",\"value\":{}}";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max) + "…";
    }

    private static Object toJson(Object value) throws Exception {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Map) {
            JSONObject out = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(e.getKey()), toJson(e.getValue()));
            }
            return out;
        }
        if (value instanceof Iterable) {
            JSONArray out = new JSONArray();
            for (Object item : (Iterable<?>) value) out.put(toJson(item));
            return out;
        }
        if (value.getClass().isArray()) {
            JSONArray out = new JSONArray();
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < n; i++) out.put(toJson(java.lang.reflect.Array.get(value, i)));
            return out;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> object(JSONObject json) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            out.put(key, fromJson(json.opt(key)));
        }
        return out;
    }

    private static Object fromJson(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return object((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) out.add(fromJson(array.opt(i)));
            return out;
        }
        return value;
    }
}
