package com.mrnobody.agent.execution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.ToolResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class ToolResultCodecTest {

    @Test
    public void structuredResultRoundTripsForReplay() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("title", "Draft");
        nested.put("pages", Arrays.asList(1, 2, 3));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("artifact", nested);
        ToolResult original = ToolResult.ok(value).renderedAs("Draft [artifact-1]");

        ToolResult restored = ToolResultCodec.decode(ToolResultCodec.encode(original));

        assertTrue(restored.isSuccess());
        assertEquals("Draft [artifact-1]", restored.result());
        assertEquals("Draft", ((Map<?, ?>) restored.value().get("artifact")).get("title"));
    }

    @Test
    public void waitingResultKeepsItsPendingTool() {
        ToolResult original = ToolResult.needsApproval("design", "review this draft");
        ToolResult restored = ToolResultCodec.decode(ToolResultCodec.encode(original));
        assertTrue(restored.needsApproval());
        assertEquals("design", restored.pendingTool());
    }
}
