package com.mrnobody.agent.tasks;

import com.mrnobody.agent.core.ApprovalDecision;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TaskEventDetailTest {

    @Test
    public void activityIsSemanticAndVersioned() throws Exception {
        JSONObject o = new JSONObject(TaskEventDetail.activity(
                "Searching broadly", "search", "Find relevant sources."));
        assertEquals(1, o.getInt("v"));
        assertEquals("activity", o.getString("shape"));
        assertEquals("Searching broadly", o.getString("label"));
        assertEquals("search", o.getString("kind"));
    }

    @Test
    public void toolCallCarriesIdentityButNotTypedFormText() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("url", "https://example.com/form");
        params.put("text", "a secret the user typed");
        ToolCall call = ToolCall.of("browser", new ToolRequest("type", params), Tier.WRITE);

        String encoded = TaskEventDetail.toolCall(call);
        JSONObject o = new JSONObject(encoded);
        assertEquals(call.id(), o.getString("id"));
        assertEquals("browser", o.getString("tool"));
        assertEquals("type", o.getString("action"));
        assertEquals("https://example.com/form", o.getString("subject"));
        assertFalse(encoded.contains("a secret the user typed"));
    }

    @Test
    public void resultReportsCountsWithoutCopyingOutput() throws Exception {
        List<Object> results = new ArrayList<>();
        results.add(new LinkedHashMap<>());
        results.add(new LinkedHashMap<>());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("results", results);
        value.put("provider", "DuckDuckGo");
        value.put("text", "large page body must not be logged");
        ToolResult result = ToolResult.ok(value);
        ToolCall call = ToolCall.of("search",
                ToolRequest.of("search", "q", "weather Lagos"), Tier.READ);

        String encoded = TaskEventDetail.toolResult(call, result,
                ApprovalDecision.allow(ApprovalDecision.Source.TIER, "read"), 842);
        JSONObject o = new JSONObject(encoded);
        assertEquals(call.id(), o.getString("id"));
        assertEquals("done", o.getString("state"));
        assertEquals(842, o.getLong("durationMs"));
        assertEquals(2, o.getInt("count"));
        assertEquals("candidates", o.getString("unit"));
        assertFalse(encoded.contains("large page body"));
    }

    @Test
    public void approvalAndDenialRemainDistinct() throws Exception {
        ToolCall call = ToolCall.of("download",
                ToolRequest.of("download", "url", "https://example.com/a.zip"), Tier.SANDBOX);
        JSONObject waiting = new JSONObject(TaskEventDetail.toolResult(call,
                ToolResult.needsApproval("download", "Needs approval"),
                ApprovalDecision.confirm(ApprovalDecision.Source.MODE, "sandbox write"), 4));
        assertEquals("waiting", waiting.getString("state"));

        JSONObject denied = new JSONObject(TaskEventDetail.toolResult(call,
                ToolResult.fail("not run"),
                ApprovalDecision.deny(ApprovalDecision.Source.GUARD, "destructive"), 2));
        assertEquals("denied", denied.getString("state"));
        assertTrue(denied.getString("reason").contains("destructive"));
    }
}
