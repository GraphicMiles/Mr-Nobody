package com.mrnobody.agent.mcp;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.resilience.FailureClassifier;
import com.mrnobody.agent.resilience.OperationFailure;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal standards-based MCP client over Streamable HTTP. */
public final class McpClient {

    public interface TokenProvider { String accessToken() throws Exception; }

    public static final String LATEST_PROTOCOL = "2026-07-28";
    public static final String COMPAT_PROTOCOL = "2025-11-25";

    private final String endpoint;
    private final McpTransport transport;
    private final TokenProvider tokens;
    private final AtomicLong ids = new AtomicLong();
    private final Object initLock = new Object();

    private volatile String protocolVersion = LATEST_PROTOCOL;
    private volatile String sessionId = "";
    private volatile boolean initialized;
    private volatile Map<String, McpCapability> tools = Collections.emptyMap();
    private volatile long toolsAt;

    public McpClient(String endpoint, McpTransport transport, TokenProvider tokens) {
        this.endpoint = endpoint;
        this.transport = transport;
        this.tokens = tokens;
    }

    public List<McpCapability> listTools(Cancellation cancellation) throws Exception {
        ensureInitialized(cancellation);
        if (!tools.isEmpty() && System.currentTimeMillis() - toolsAt < 5 * 60_000L) {
            return new ArrayList<>(tools.values());
        }
        JSONObject result = request("tools/list", "", new JSONObject(), "",
                cancellation, true);
        JSONArray listed = result.optJSONArray("tools");
        Map<String, McpCapability> found = new LinkedHashMap<>();
        if (listed != null) {
            for (int i = 0; i < listed.length(); i++) {
                JSONObject item = listed.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "");
                if (name.isEmpty()) continue;
                found.put(name, McpCapability.discovered(name,
                        item.optString("description", ""), item.optJSONObject("inputSchema")));
            }
        }
        tools = Collections.unmodifiableMap(found);
        toolsAt = System.currentTimeMillis();
        return new ArrayList<>(found.values());
    }

    public McpResult callTool(String name, JSONObject arguments, String idempotencyKey,
                              Cancellation cancellation) throws Exception {
        ensureInitialized(cancellation);
        if (tools.isEmpty() || !tools.containsKey(name)) listTools(cancellation);
        if (!tools.containsKey(name)) {
            throw new McpException(new OperationFailure(
                    com.mrnobody.agent.resilience.FailureKind.UNSUPPORTED,
                    "Canva MCP does not expose the required tool: " + name,
                    0, 0L, false, false));
        }
        JSONObject safeArguments = arguments == null ? new JSONObject() : arguments;
        validateArguments(tools.get(name), safeArguments);
        JSONObject params = new JSONObject();
        params.put("name", name);
        params.put("arguments", safeArguments);
        JSONObject meta = new JSONObject();
        meta.put("io.mrnobody/idempotencyKey", idempotencyKey == null ? "" : idempotencyKey);
        params.put("_meta", meta);
        return McpResult.from(request("tools/call", name, params,
                idempotencyKey, cancellation, false));
    }

    public McpCapability tool(String name, Cancellation cancellation) throws Exception {
        if (tools.isEmpty() || !tools.containsKey(name)) listTools(cancellation);
        return tools.get(name);
    }

    public void resetSession() {
        initialized = false;
        sessionId = "";
        tools = Collections.emptyMap();
        toolsAt = 0L;
    }

    private static void validateArguments(McpCapability tool, JSONObject arguments)
            throws McpException {
        if (tool == null) return;
        JSONArray required = tool.inputSchema.optJSONArray("required");
        if (required == null) return;
        for (int i = 0; i < required.length(); i++) {
            String key = required.optString(i, "");
            if (!key.isEmpty() && (!arguments.has(key) || arguments.isNull(key))) {
                throw new McpException(new OperationFailure(
                        com.mrnobody.agent.resilience.FailureKind.VALIDATION,
                        "MCP tool " + tool.name + " requires " + key,
                        0, 0L, false, false));
            }
        }
    }

    private void ensureInitialized(Cancellation cancellation) throws Exception {
        if (initialized) return;
        synchronized (initLock) {
            if (initialized) return;
            JSONObject params = new JSONObject();
            params.put("protocolVersion", LATEST_PROTOCOL);
            params.put("capabilities", new JSONObject());
            params.put("clientInfo", new JSONObject()
                    .put("name", "Mr Nobody")
                    .put("version", "1.0.0"));
            JSONObject result;
            try {
                result = request("initialize", "", params, "", cancellation, true);
            } catch (McpException first) {
                protocolVersion = COMPAT_PROTOCOL;
                params.put("protocolVersion", COMPAT_PROTOCOL);
                result = request("initialize", "", params, "", cancellation, true);
            }
            String negotiated = result.optString("protocolVersion", protocolVersion);
            if (!negotiated.isEmpty()) protocolVersion = negotiated;
            initialized = true;
            sendInitialized(cancellation);
        }
    }

    private void sendInitialized(Cancellation cancellation) throws Exception {
        JSONObject notification = new JSONObject();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        String token = tokens == null ? "" : tokens.accessToken();
        transport.post(endpoint, token, protocolVersion, sessionId,
                "notifications/initialized", "", notification.toString(), "", cancellation);
    }

    private JSONObject request(String method, String name, JSONObject params,
                               String idempotencyKey, Cancellation cancellation,
                               boolean retryableRead) throws Exception {
        long id = ids.incrementAndGet();
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params == null ? new JSONObject() : params);
        int attempts = retryableRead ? 2 : 1;
        McpTransport.Response response = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            String token = tokens == null ? "" : tokens.accessToken();
            response = transport.post(endpoint, token, protocolVersion, sessionId,
                    method, name, request.toString(), idempotencyKey, cancellation);
            if (response.status >= 200 && response.status < 300) break;
            OperationFailure failure = FailureClassifier.fromHttp(response.status,
                    "MCP HTTP " + response.status, retryAfter(response));
            if (!retryableRead || !failure.retryable || failure.ambiguous
                    || attempt + 1 >= attempts) throw new McpException(failure);
            Thread.sleep(Math.min(5_000L, Math.max(100L, failure.retryAfterMs)));
        }
        if (response == null) throw new McpException(OperationFailure.ambiguous("No MCP response"));
        String nextSession = response.header("Mcp-Session-Id");
        if (!nextSession.isEmpty()) sessionId = nextSession;
        if (response.body.isEmpty()) return new JSONObject();
        JSONObject envelope;
        try { envelope = new JSONObject(response.body); }
        catch (Exception e) {
            throw new McpException(OperationFailure.unknown("MCP returned malformed JSON"));
        }
        JSONObject error = envelope.optJSONObject("error");
        if (error != null) {
            int code = error.optInt("code", 0);
            String message = error.optString("message", "MCP request failed");
            throw new McpException(FailureClassifier.fromMessage(
                    (code == 0 ? "" : code + " ") + message));
        }
        JSONObject result = envelope.optJSONObject("result");
        return result == null ? new JSONObject() : result;
    }

    private static long retryAfter(McpTransport.Response response) {
        String raw = response.header("Retry-After");
        if (raw == null || raw.trim().isEmpty()) return 0L;
        try { return Math.max(0L, Math.round(Double.parseDouble(raw.trim()) * 1000.0)); }
        catch (Exception e) { return 0L; }
    }
}
