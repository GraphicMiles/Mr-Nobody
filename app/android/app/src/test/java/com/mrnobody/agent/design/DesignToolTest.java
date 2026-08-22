package com.mrnobody.agent.design;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.execution.ExecutionIdentity;
import com.mrnobody.agent.tools.DesignTool;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class DesignToolTest {
    @Test
    public void sessionBudgetStopsCreateFlood() {
        InMemoryDesignSessions sessions = new InMemoryDesignSessions();
        DesignSession session = sessions.getOrCreate(1L, "poster");
        FakeDesignAdapter adapter = new FakeDesignAdapter();
        DesignTool tool = new DesignTool(() -> adapter, () -> sessions);
        ToolResult result = null;

        for (int i = 0; i <= DesignQuota.MAX_CREATES; i++) {
            ToolRequest request = request(session.id, "generate");
            ExecutionIdentity identity = ExecutionIdentity.of(1L, "run", "create", i,
                    "design", "generate", request.params());
            result = tool.execute(null, request, Cancellation.NONE, identity);
        }

        assertTrue(result.isError());
        assertTrue(result.error().contains("budget reached"));
        assertEquals(DesignQuota.MAX_CREATES, adapter.calls.get());
    }

    @Test
    public void replayingTheSameEffectDoesNotSpendQuotaTwice() {
        InMemoryDesignSessions sessions = new InMemoryDesignSessions();
        DesignSession session = sessions.getOrCreate(2L, "poster");
        FakeDesignAdapter adapter = new FakeDesignAdapter();
        DesignTool tool = new DesignTool(() -> adapter, () -> sessions);
        ToolRequest request = request(session.id, "generate");
        ExecutionIdentity identity = ExecutionIdentity.of(2L, "run", "create", 0,
                "design", "generate", request.params());

        assertTrue(tool.execute(null, request, Cancellation.NONE, identity).isSuccess());
        assertTrue(tool.execute(null, request, Cancellation.NONE, identity).isSuccess());
        assertEquals(1, session.createCount);
        assertEquals(1, adapter.calls.get());
    }

    @Test
    public void exportIsAlwaysExecTierAndIdempotent() {
        DesignTool tool = new DesignTool(
                FakeDesignAdapter::new, () -> null);
        assertEquals(com.mrnobody.agent.core.Tier.EXEC,
                tool.tierFor(request(1L, "export")));
        assertTrue(tool.supportsIdempotency(request(1L, "export")));
    }

    private static ToolRequest request(long sessionId, String action) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sessionId", String.valueOf(sessionId));
        params.put("instruction", "poster");
        return new ToolRequest(action, params);
    }
}
