package com.mrnobody.agent.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class McpArgumentsTest {
    @Test
    public void usesDiscoveredFieldNamesInsteadOfAssumingAliases() throws Exception {
        McpCapability tool = tool(new JSONObject()
                .put("generation_job_id", type("string"))
                .put("candidate_id", type("string")));
        JSONObject args = McpArguments.select(tool, "job-1", "candidate-2");
        assertEquals("job-1", args.getString("generation_job_id"));
        assertEquals("candidate-2", args.getString("candidate_id"));
    }

    @Test
    public void objectExportFormatGetsTypedPayload() throws Exception {
        McpCapability tool = tool(new JSONObject()
                .put("id", type("string"))
                .put("format", type("object")));
        JSONObject args = McpArguments.export(tool, "design-1", "PDF");
        assertEquals("design-1", args.getString("id"));
        assertEquals("pdf", args.getJSONObject("format").getString("type"));
    }

    @Test
    public void editOperationsAreBoundedAndStructured() throws Exception {
        McpCapability tool = tool(new JSONObject()
                .put("transaction_id", type("string"))
                .put("page_index", type("integer"))
                .put("operations", type("array")));
        JSONObject args = McpArguments.performEdit(tool, "tx", 1,
                "element", "Old", "New", false);
        JSONArray operations = args.getJSONArray("operations");
        assertEquals(1, operations.length());
        assertEquals("replace_text", operations.getJSONObject(0).getString("type"));
        assertTrue(!args.toString().contains("password"));
    }

    private static McpCapability tool(JSONObject properties) throws Exception {
        return McpCapability.discovered("x", "", new JSONObject().put("type", "object")
                .put("properties", properties));
    }
    private static JSONObject type(String name) throws Exception {
        return new JSONObject().put("type", name);
    }
}
