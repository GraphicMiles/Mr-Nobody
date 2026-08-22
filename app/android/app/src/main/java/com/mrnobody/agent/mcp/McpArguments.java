package com.mrnobody.agent.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Adapts canonical design fields to the server-discovered input schema. */
final class McpArguments {
    private McpArguments() { }

    static JSONObject generate(McpCapability tool, String instruction) throws Exception {
        JSONObject out = new JSONObject();
        putFirst(tool, out, instruction,
                "prompt", "description", "design_brief", "query", "text");
        putIf(tool, out, "design_type",
                enumValue(property(tool, "design_type"), designType(instruction)));
        return out;
    }

    static JSONObject select(McpCapability tool, String jobId, String candidateId) throws Exception {
        JSONObject out = new JSONObject();
        putFirst(tool, out, jobId, "job_id", "generation_job_id");
        putFirst(tool, out, candidateId, "candidate_id", "id");
        return out;
    }

    static JSONObject designId(McpCapability tool, String designId) throws Exception {
        JSONObject out = new JSONObject();
        putFirst(tool, out, designId, "id", "design_id");
        return out;
    }

    static JSONObject export(McpCapability tool, String designId, String format) throws Exception {
        JSONObject out = designId(tool, designId);
        JSONObject property = property(tool, "format");
        if (property != null && "object".equals(property.optString("type"))) {
            out.put("format", new JSONObject().put("type", format.toLowerCase(Locale.ROOT)));
        } else {
            out.put("format", format.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    static JSONObject performEdit(McpCapability tool, String transactionId, int page,
                                  String elementId, String oldText, String newText,
                                  boolean responsive) throws Exception {
        JSONObject out = new JSONObject();
        putFirst(tool, out, transactionId, "transaction_id", "id");
        putIf(tool, out, "page_index", page);
        JSONObject operation = new JSONObject();
        operation.put("type", responsive ? "find_and_replace_text" : "replace_text");
        operation.put("element_id", elementId);
        if (responsive) {
            operation.put("find_text", oldText);
            operation.put("replace_text", newText);
        } else {
            operation.put("text", newText);
        }
        out.put("operations", new JSONArray().put(operation));
        return out;
    }

    static JSONObject commit(McpCapability tool, String transactionId) throws Exception {
        JSONObject out = new JSONObject();
        putFirst(tool, out, transactionId, "transaction_id", "id");
        return out;
    }

    private static void putFirst(McpCapability tool, JSONObject out, Object value,
                                 String... candidates) throws Exception {
        for (String candidate : candidates) {
            if (has(tool, candidate)) { out.put(candidate, value); return; }
        }
        // Documentation's first canonical field is the compatibility fallback.
        out.put(candidates[0], value);
    }

    private static void putIf(McpCapability tool, JSONObject out, String key, Object value)
            throws Exception {
        if (has(tool, key)) out.put(key, value);
    }

    private static boolean has(McpCapability tool, String key) {
        JSONObject properties = tool == null ? null : tool.inputSchema.optJSONObject("properties");
        return properties != null && properties.has(key);
    }

    private static JSONObject property(McpCapability tool, String key) {
        JSONObject properties = tool == null ? null : tool.inputSchema.optJSONObject("properties");
        return properties == null ? null : properties.optJSONObject(key);
    }

    private static String enumValue(JSONObject property, String preferred) {
        JSONArray allowed = property == null ? null : property.optJSONArray("enum");
        if (allowed == null || allowed.length() == 0) return preferred;
        String normalized = preferred.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        for (int i = 0; i < allowed.length(); i++) {
            String candidate = allowed.optString(i, "");
            String comparable = candidate.replace("_", "").replace("-", "")
                    .replace(" ", "").toLowerCase(Locale.ROOT);
            if (comparable.contains(normalized) || normalized.contains(comparable)) return candidate;
        }
        return allowed.optString(0, preferred);
    }

    private static String designType(String instruction) {
        String text = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        if (text.contains("instagram")) return "instagram_post";
        if (text.contains("presentation") || text.contains("slide")) return "presentation";
        if (text.contains("poster")) return "poster";
        if (text.contains("flyer")) return "flyer";
        if (text.contains("logo")) return "logo";
        if (text.contains("banner")) return "banner";
        return "custom";
    }
}
