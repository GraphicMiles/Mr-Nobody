package com.mrnobody.agent.util;

import java.net.URI;

/** Transport requirements for endpoints that carry prompts, keys, or task data. */
public final class EndpointPolicy {

    private EndpointPolicy() { }

    /** A refusal reason, or null when {@code raw} is a safe HTTPS base URL. */
    public static String secureBaseReason(String raw) {
        final URI uri;
        try {
            uri = new URI(raw == null ? "" : raw.trim());
        } catch (Exception e) {
            return "endpoint URL is invalid";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "endpoint must use HTTPS";
        }
        if (uri.getHost() == null || uri.getHost().trim().isEmpty()) {
            return "endpoint needs a valid host";
        }
        if (uri.getRawUserInfo() != null) {
            return "endpoint URL credentials are not allowed";
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "endpoint base must not contain a query or fragment";
        }
        return null;
    }

    /** Throw before credentials or task data are attached to an unsafe endpoint. */
    public static void requireSecureBase(String raw) throws java.io.IOException {
        String reason = secureBaseReason(raw);
        if (reason != null) throw new java.io.IOException(reason);
    }
}
