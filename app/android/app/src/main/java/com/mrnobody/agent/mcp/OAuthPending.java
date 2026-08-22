package com.mrnobody.agent.mcp;

import org.json.JSONObject;

final class OAuthPending {
    final String state;
    final String verifier;
    final long createdAt;

    OAuthPending(String state, String verifier, long createdAt) {
        this.state = state == null ? "" : state;
        this.verifier = verifier == null ? "" : verifier;
        this.createdAt = createdAt;
    }

    String encode() throws Exception {
        return new JSONObject().put("state", state).put("verifier", verifier)
                .put("createdAt", createdAt).toString();
    }

    static OAuthPending decode(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            return new OAuthPending(o.optString("state"), o.optString("verifier"),
                    o.optLong("createdAt"));
        } catch (Exception e) { return new OAuthPending("", "", 0L); }
    }
}
