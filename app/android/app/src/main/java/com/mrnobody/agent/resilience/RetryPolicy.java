package com.mrnobody.agent.resilience;

import com.mrnobody.agent.core.Tier;

import java.util.concurrent.ThreadLocalRandom;

/** One bounded retry policy for tools, providers, MCP calls, and polling. */
public final class RetryPolicy {
    public static final int MAX_ATTEMPTS = 2;
    public static final long MAX_DELAY_MS = 10_000L;

    private RetryPolicy() { }

    public static boolean shouldRetry(OperationFailure failure, int attempt,
                                      Tier tier, boolean idempotent) {
        if (failure == null || !failure.retryable || attempt + 1 >= MAX_ATTEMPTS) return false;
        if (failure.kind == FailureKind.CANCELLED
                || failure.kind == FailureKind.AUTHENTICATION
                || failure.kind == FailureKind.CONFIGURATION
                || failure.kind == FailureKind.SAFETY
                || failure.kind == FailureKind.VALIDATION) return false;
        // Reads can always be repeated. Effects require the same propagated key.
        return tier == Tier.READ || idempotent;
    }

    public static long delayMs(OperationFailure failure, int attempt) {
        if (failure != null && failure.retryAfterMs > 0) {
            return Math.min(failure.retryAfterMs, MAX_DELAY_MS);
        }
        long base = Math.min(400L * (1L << Math.max(0, Math.min(attempt, 4))),
                MAX_DELAY_MS);
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 3L + 1L));
        return Math.min(MAX_DELAY_MS, base + jitter);
    }
}
