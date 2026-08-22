package com.mrnobody.agent.resilience;

import java.util.Locale;

/** Converts transport/provider text into the common failure taxonomy. */
public final class FailureClassifier {
    private FailureClassifier() { }

    public static OperationFailure fromHttp(int status, String message, long retryAfterMs) {
        if (status == 401 || status == 403) {
            return new OperationFailure(FailureKind.AUTHENTICATION, message, status,
                    0L, false, false);
        }
        if (status == 408 || status == 425 || status == 429
                || status == 502 || status == 503 || status == 504) {
            FailureKind kind = status == 429 ? FailureKind.RATE_LIMIT
                    : status == 408 || status == 504 ? FailureKind.TIMEOUT
                    : FailureKind.TRANSIENT_NETWORK;
            return new OperationFailure(kind, message, status, retryAfterMs, true, false);
        }
        if (status >= 500) {
            return new OperationFailure(FailureKind.TRANSIENT_NETWORK, message, status,
                    retryAfterMs, true, false);
        }
        if (status == 400 || status == 404 || status == 409 || status == 422) {
            return new OperationFailure(FailureKind.VALIDATION, message, status,
                    0L, false, false);
        }
        return new OperationFailure(FailureKind.PERMANENT, message, status,
                0L, false, false);
    }

    public static OperationFailure fromMessage(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        int status = statusIn(m);
        if (status > 0) return fromHttp(status, message, 0L);
        if (contains(m, "cancelled", "canceled")) {
            return new OperationFailure(FailureKind.CANCELLED, message, 0, 0L, false, false);
        }
        if (contains(m, "timed out", "timeout", "read timed out")) {
            return new OperationFailure(FailureKind.TIMEOUT, message, 0, 0L, true, true);
        }
        if (contains(m, "unknown host", "no connection", "connection reset",
                "connection refused", "temporarily unavailable", "dns")) {
            return new OperationFailure(FailureKind.TRANSIENT_NETWORK, message,
                    0, 0L, true, false);
        }
        if (contains(m, "rate limit", "rate-limit", "too many requests")) {
            return new OperationFailure(FailureKind.RATE_LIMIT, message, 429,
                    0L, true, false);
        }
        if (contains(m, "api key", "unauthorized", "forbidden", "authentication",
                "invalid token", "expired token")) {
            return new OperationFailure(FailureKind.AUTHENTICATION, message,
                    0, 0L, false, false);
        }
        if (contains(m, "quota", "credit", "billing", "license_required")) {
            return new OperationFailure(FailureKind.QUOTA, message, 0, 0L, false, false);
        }
        if (contains(m, "no model", "not configured", "base url", "missing credential")) {
            return new OperationFailure(FailureKind.CONFIGURATION, message,
                    0, 0L, false, false);
        }
        if (contains(m, "safety", "blocked content", "prohibited")) {
            return new OperationFailure(FailureKind.SAFETY, message, 0, 0L, false, false);
        }
        return OperationFailure.unknown(message);
    }

    private static int statusIn(String message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:http\\s*)?(\\d{3})", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(message == null ? "" : message);
        while (matcher.find()) {
            int value;
            try { value = Integer.parseInt(matcher.group(1)); }
            catch (Exception ignored) { continue; }
            if (value >= 400 && value <= 599) return value;
        }
        return 0;
    }

    private static boolean contains(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}
