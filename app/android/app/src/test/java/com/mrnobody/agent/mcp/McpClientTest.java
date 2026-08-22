package com.mrnobody.agent.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Cancellation;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

public class McpClientTest {

    @Test
    public void initializesDiscoversAndCallsOnlyServerListedTools() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, "session-1", rpc(1,
                new JSONObject().put("protocolVersion", McpClient.LATEST_PROTOCOL)));
        transport.add(202, "session-1", ""); // notifications/initialized
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("prompt",
                        new JSONObject().put("type", "string")));
        JSONObject tools = new JSONObject().put("tools",
                new org.json.JSONArray().put(new JSONObject()
                        .put("name", "generate-design")
                        .put("description", "generate")
                        .put("inputSchema", schema)));
        transport.add(200, "session-1", rpc(2, tools));
        JSONObject called = new JSONObject().put("structuredContent",
                new JSONObject().put("job", new JSONObject().put("status", "success")));
        transport.add(200, "session-1", rpc(3, called));

        McpClient client = new McpClient("https://mcp.canva.com/mcp", transport,
                () -> "token-not-exposed");
        List<McpCapability> discovered = client.listTools(Cancellation.NONE);
        McpResult result = client.callTool("generate-design",
                new JSONObject().put("prompt", "poster"), "idem-1", Cancellation.NONE);

        assertEquals(1, discovered.size());
        assertEquals("success", result.structured.getJSONObject("job").getString("status"));
        assertEquals("session-1", transport.sessions.get(transport.sessions.size() - 1));
        assertEquals("idem-1", transport.idempotencyKeys.get(transport.idempotencyKeys.size() - 1));
        assertTrue(transport.bearers.stream().allMatch("token-not-exposed"::equals));
    }

    @Test
    public void unlistedToolFailsBeforeNetworkCall() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, "s", rpc(1,
                new JSONObject().put("protocolVersion", McpClient.LATEST_PROTOCOL)));
        transport.add(202, "s", "");
        transport.add(200, "s", rpc(2,
                new JSONObject().put("tools", new org.json.JSONArray())));
        McpClient client = new McpClient("https://mcp.canva.com/mcp", transport, () -> "t");
        boolean failed = false;
        try { client.callTool("delete-everything", new JSONObject(), "k", Cancellation.NONE); }
        catch (McpException expected) { failed = true; }
        assertTrue(failed);
        assertEquals(3, transport.calls);
    }

    private static String rpc(long id, JSONObject result) throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("result", result).toString();
    }

    private static final class FakeTransport implements McpTransport {
        final Queue<Response> responses = new ArrayDeque<>();
        final List<String> sessions = new ArrayList<>();
        final List<String> idempotencyKeys = new ArrayList<>();
        final List<String> bearers = new ArrayList<>();
        int calls;

        void add(int status, String session, String body) {
            responses.add(new Response(status,
                    session.isEmpty() ? Collections.emptyMap()
                            : Collections.singletonMap("Mcp-Session-Id", session), body));
        }

        @Override
        public Response post(String endpoint, String bearerToken, String protocolVersion,
                             String sessionId, String method, String name, String json,
                             String idempotencyKey, Cancellation cancellation) {
            calls++; sessions.add(sessionId); idempotencyKeys.add(idempotencyKey);
            bearers.add(bearerToken);
            return responses.remove();
        }
    }
}
