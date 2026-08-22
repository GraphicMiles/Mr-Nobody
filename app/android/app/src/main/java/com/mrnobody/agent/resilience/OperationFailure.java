package com.mrnobody.agent.resilience;

/** Typed, bounded failure information; never carries a response body or secret. */
public final class OperationFailure {
    public final FailureKind kind;
    public final String message;
    public final int statusCode;
    public final long retryAfterMs;
    public final boolean retryable;
    public final boolean ambiguous;

    public OperationFailure(FailureKind kind, String message, int statusCode,
                            long retryAfterMs, boolean retryable, boolean ambiguous) {
        this.kind = kind == null ? FailureKind.UNKNOWN : kind;
        this.message = bounded(message);
        this.statusCode = statusCode;
        this.retryAfterMs = Math.max(0L, retryAfterMs);
        this.retryable = retryable;
        this.ambiguous = ambiguous;
    }

    public static OperationFailure unknown(String message) {
        return new OperationFailure(FailureKind.UNKNOWN, message, 0, 0L, false, false);
    }

    public static OperationFailure ambiguous(String message) {
        return new OperationFailure(FailureKind.AMBIGUOUS, message, 0, 0L, false, true);
    }

    private static String bounded(String value) {
        String clean = value == null || value.trim().isEmpty() ? "unknown failure" : value.trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500) + "…";
    }
}
