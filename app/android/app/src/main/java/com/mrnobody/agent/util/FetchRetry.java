package com.mrnobody.agent.util;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * When to retry a fetch, and how long to wait.
 *
 * <p>SwiftAgent's {@code .retry(3, delay:)} plus honouring {@code Retry-After}.
 * Only transient statuses retry. A fail-closed privacy route must not be
 * retried as a direct connection — that decision lives in {@code NetworkGate},
 * not here.
 */
public final class FetchRetry {

    public static final int MAX_ATTEMPTS = 3;
    public static final long MAX_WAIT_MS = 5_000L;

    private FetchRetry() {
    }

    public static boolean shouldRetry(int status) {
        return status == 429 || status == 502 || status == 503;
    }

    /**
     * Wait before {@code attempt} (0-based) is retried.
     * {@code retryAfter} is the header, seconds or an HTTP date, or null.
     */
    public static long delayMs(int attempt, String retryAfter) {
        long fromHeader = parseRetryAfter(retryAfter);
        if (fromHeader > 0) return Math.min(fromHeader, MAX_WAIT_MS);
        int n = Math.max(0, Math.min(attempt, 4));
        long backoff = 400L * (1L << n);
        return Math.min(backoff, MAX_WAIT_MS);
    }

    public static boolean hasAttemptsLeft(int attempt) {
        return attempt + 1 < MAX_ATTEMPTS;
    }

    static long parseRetryAfter(String header) {
        if (header == null) return 0;
        String v = header.trim();
        if (v.isEmpty()) return 0;
        try {
            double seconds = Double.parseDouble(v);
            if (seconds < 0) return 0;
            return Math.round(seconds * 1000.0);
        } catch (NumberFormatException ignored) {
        }
        try {
            SimpleDateFormat fmt = new SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
            long when = fmt.parse(v).getTime();
            long wait = when - System.currentTimeMillis();
            return wait < 0 ? 0 : wait;
        } catch (Exception ignored) {
        }
        return 0;
    }
}
