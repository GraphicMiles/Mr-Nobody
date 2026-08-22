package com.mrnobody.agent.mcp;

import com.mrnobody.agent.core.Cancellation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Wire seam so protocol behavior is testable without a network. */
public interface McpTransport {
    Response post(String endpoint, String bearerToken, String protocolVersion,
                  String sessionId, String method, String name, String json,
                  String idempotencyKey, Cancellation cancellation) throws Exception;

    final class Response {
        public final int status;
        public final Map<String, String> headers;
        public final String body;

        public Response(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(
                    headers == null ? Collections.emptyMap() : headers));
            this.body = body == null ? "" : body;
        }

        public String header(String name) {
            if (name == null) return "";
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
            }
            return "";
        }
    }
}
