package com.mrnobody.agent.mcp;

/** Non-secret build configuration for Canva's official remote MCP. */
public final class CanvaMcpConfig {
    public static final String ENDPOINT = "https://mcp.canva.com/mcp";
    public static final String AUTHORIZE_ENDPOINT = "https://mcp.canva.com/authorize";
    public static final String TOKEN_ENDPOINT = "https://mcp.canva.com/token";
    public static final String DEFAULT_REDIRECT = "mrnobody://oauth/canva";

    private CanvaMcpConfig() { }

    public static String clientId() {
        return configured("CANVA_MCP_CLIENT_ID", "mrnobody.canva.clientId", "");
    }

    public static String redirectUri() {
        return configured("CANVA_MCP_REDIRECT_URI", "mrnobody.canva.redirectUri",
                DEFAULT_REDIRECT);
    }

    public static boolean isBuildConfigured() {
        String id = clientId();
        return id.startsWith("https://") && id.contains("/")
                && !redirectUri().isEmpty();
    }

    private static String configured(String field, String property, String fallback) {
        String test = System.getProperty(property, "");
        if (!test.trim().isEmpty()) return test.trim();
        try {
            Class<?> build = Class.forName("com.mrnobody.browser.BuildConfig");
            Object value = build.getField(field).get(null);
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value).trim();
            }
        } catch (Throwable ignored) { }
        return fallback;
    }
}
