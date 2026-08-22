package com.mrnobody.agent.mcp;

import org.json.JSONObject;

final class OAuthToken {
    final String accessToken;
    final String refreshToken;
    final String tokenType;
    final String scope;
    final long expiresAt;

    OAuthToken(String accessToken, String refreshToken, String tokenType,
               String scope, long expiresAt) {
        this.accessToken = clean(accessToken);
        this.refreshToken = clean(refreshToken);
        this.tokenType = clean(tokenType);
        this.scope = clean(scope);
        this.expiresAt = expiresAt;
    }

    boolean usable(long now) { return !accessToken.isEmpty() && expiresAt - now > 60_000L; }

    String encode() throws Exception {
        return new JSONObject().put("access", accessToken).put("refresh", refreshToken)
                .put("type", tokenType).put("scope", scope).put("expiresAt", expiresAt)
                .toString();
    }

    static OAuthToken decode(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            return new OAuthToken(o.optString("access"), o.optString("refresh"),
                    o.optString("type"), o.optString("scope"), o.optLong("expiresAt"));
        } catch (Exception e) { return new OAuthToken("", "", "", "", 0L); }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
