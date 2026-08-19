package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-host sliding window, from mcp-twikit's rate-limit tracker.
 *
 * <p>Twikit counted tweets per 15 minutes. We count fetches per host so a
 * recurring monitor cannot hammer one site. Empty / unknown hosts are
 * allowed — this is a courtesy, not a lock.
 */
public final class HostRateLimit {

    public static final int DEFAULT_MAX = 12;
    public static final long WINDOW_MS = 60_000L;

    private static final int MAX_HOSTS = 64;

    public interface Clock {
        long now();
    }

    private static final Clock SYSTEM = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };

    private static volatile Clock clock = SYSTEM;
    private static volatile int maxPerWindow = DEFAULT_MAX;

    private static final Map<String, List<Long>> HITS =
            new LinkedHashMap<String, List<Long>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Long>> eldest) {
                    return size() > MAX_HOSTS;
                }
            };

    private HostRateLimit() {
    }

    public static synchronized void reset() {
        HITS.clear();
        clock = SYSTEM;
        maxPerWindow = DEFAULT_MAX;
    }

    /** Tests inject a clock and a tighter ceiling. */
    public static synchronized void configure(Clock next, int max) {
        clock = next == null ? SYSTEM : next;
        maxPerWindow = max < 1 ? DEFAULT_MAX : max;
    }

    public static synchronized boolean tryAcquire(String host) {
        String h = norm(host);
        if (h.isEmpty()) return true;
        long now = clock.now();
        List<Long> times = HITS.get(h);
        if (times == null) {
            times = new ArrayList<>();
            HITS.put(h, times);
        }
        prune(times, now);
        if (times.size() >= maxPerWindow) return false;
        times.add(now);
        return true;
    }

    public static synchronized long retryAfterMs(String host) {
        String h = norm(host);
        if (h.isEmpty()) return 0;
        List<Long> times = HITS.get(h);
        if (times == null || times.isEmpty()) return 0;
        long now = clock.now();
        prune(times, now);
        if (times.size() < maxPerWindow) return 0;
        long oldest = times.get(0);
        long wait = (oldest + WINDOW_MS) - now;
        return wait < 0 ? 0 : wait;
    }

    public static String denyMessage(String host) {
        long wait = retryAfterMs(host);
        long seconds = Math.max(1, (wait + 999) / 1000);
        String h = norm(host);
        return "paused " + (h.isEmpty() ? "this host" : h)
                + " for " + seconds + "s (rate limit)";
    }

    private static void prune(List<Long> times, long now) {
        Iterator<Long> it = times.iterator();
        while (it.hasNext()) {
            if (now - it.next() >= WINDOW_MS) it.remove();
            else break;
        }
    }

    private static String norm(String host) {
        if (host == null || host.isEmpty()) return "";
        String h = host.toLowerCase(Locale.ROOT).trim();
        if (h.startsWith("www.")) h = h.substring(4);
        return h;
    }
}
