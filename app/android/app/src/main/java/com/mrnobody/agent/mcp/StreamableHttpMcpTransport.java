package com.mrnobody.agent.mcp;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.util.EndpointPolicy;
import com.mrnobody.browser.net.NetworkGate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP Streamable HTTP transport through the app's fail-closed network gate. */
public final class StreamableHttpMcpTransport implements McpTransport {

    private static final int MAX_RESPONSE_CHARS = 1024 * 1024;

    @Override
    public Response post(String endpoint, String bearerToken, String protocolVersion,
                         String sessionId, String method, String name, String json,
                         String idempotencyKey, Cancellation cancellation) throws Exception {
        EndpointPolicy.requireSecureBase(endpoint);
        if (cancellation != null && cancellation.isCancelled()) {
            throw new java.io.IOException("cancelled");
        }
        HttpURLConnection connection = NetworkGate.openHttp(endpoint);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(70_000);
            connection.setDoOutput(true);
            // Never forward OAuth headers through a server-selected redirect.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            if (protocolVersion != null && !protocolVersion.isEmpty()) {
                connection.setRequestProperty("MCP-Protocol-Version", protocolVersion);
            }
            if (method != null && !method.isEmpty()) {
                connection.setRequestProperty("Mcp-Method", method);
            }
            if (name != null && !name.isEmpty()) {
                connection.setRequestProperty("Mcp-Name", name);
            }
            if (sessionId != null && !sessionId.isEmpty()) {
                connection.setRequestProperty("Mcp-Session-Id", sessionId);
            }
            if (bearerToken != null && !bearerToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                connection.setRequestProperty("Idempotency-Key", idempotencyKey);
            }
            try (OutputStream output = connection.getOutputStream()) {
                output.write((json == null ? "" : json).getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : readBounded(stream,
                    connection.getHeaderField("Content-Type"));
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : connection.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    headers.put(e.getKey(), e.getValue().get(0));
                }
            }
            return new Response(status, headers, body);
        } finally {
            connection.disconnect();
        }
    }

    private static String readBounded(InputStream stream, String contentType) throws Exception {
        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && raw.length() < MAX_RESPONSE_CHARS) {
                if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (!data.isEmpty()) raw.append(data).append('\n');
                    }
                } else {
                    raw.append(line).append('\n');
                }
            }
        }
        String value = raw.toString().trim();
        // A Streamable HTTP response can carry several SSE events. Return the
        // last JSON-RPC message; notifications before it have no request id.
        if (value.contains("\n")) {
            String[] lines = value.split("\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i].trim().startsWith("{")) return lines[i].trim();
            }
        }
        return value;
    }
}
