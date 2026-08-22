package com.mrnobody.agent.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

/** Normalized result of tools/call. */
public final class McpResult {
    public final boolean error;
    public final JSONObject structured;
    public final String text;

    McpResult(boolean error, JSONObject structured, String text) {
        this.error = error;
        this.structured = structured == null ? new JSONObject() : structured;
        this.text = text == null ? "" : text;
    }

    static McpResult from(JSONObject result) {
        if (result == null) return new McpResult(true, null, "MCP returned no result");
        boolean isError = result.optBoolean("isError", false);
        JSONObject structured = result.optJSONObject("structuredContent");
        StringBuilder text = new StringBuilder();
        JSONArray content = result.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block == null || !"text".equals(block.optString("type"))) continue;
                if (text.length() > 0) text.append('\n');
                text.append(block.optString("text", ""));
            }
        }
        if (structured == null && text.length() > 0) {
            String raw = text.toString().trim();
            try { structured = new JSONObject(raw); }
            catch (Exception ignored) { }
        }
        return new McpResult(isError, structured, text.toString());
    }
}
