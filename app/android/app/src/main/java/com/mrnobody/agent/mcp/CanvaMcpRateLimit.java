package com.mrnobody.agent.mcp;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Per-process preflight below Canva's documented per-user request/minute limits. */
final class CanvaMcpRateLimit {
    private static final long WINDOW_MS = 60_000L;
    private static final Map<String, ArrayDeque<Long>> CALLS = new HashMap<>();

    private CanvaMcpRateLimit() { }

    static synchronized boolean tryAcquire(String tool) {
        int limit = limit(tool);
        long now = System.currentTimeMillis();
        ArrayDeque<Long> times = CALLS.computeIfAbsent(tool, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MS) times.removeFirst();
        if (times.size() >= limit) return false;
        times.addLast(now);
        return true;
    }

    static int limit(String tool) {
        if ("perform-editing-operations".equals(tool)) return 50;
        if ("search-designs".equals(tool) || "get-design".equals(tool)
                || "get-design-content".equals(tool) || "get-design-pages".equals(tool)
                || "get-design-thumbnail".equals(tool)) return 100;
        return 20; // generate/select/export/start/commit/cancel and other mutations
    }

    static synchronized void resetForTest() { CALLS.clear(); }
}
