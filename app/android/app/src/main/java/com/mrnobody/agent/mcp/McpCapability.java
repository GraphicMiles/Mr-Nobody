package com.mrnobody.agent.mcp;

import org.json.JSONObject;

/** Server-discovered MCP capability; discovery does not grant permission. */
public final class McpCapability {
    public final String name;
    public final String description;
    public final JSONObject inputSchema;

    private McpCapability(String name, String description, JSONObject inputSchema) {
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema == null ? new JSONObject() : inputSchema;
    }

    public static McpCapability discovered(String name, String description,
                                           JSONObject inputSchema) {
        return new McpCapability(name, description, inputSchema);
    }
}
