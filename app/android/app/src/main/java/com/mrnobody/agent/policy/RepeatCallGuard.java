package com.mrnobody.agent.policy;

import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolPipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stops the agent repeating one identical call forever.
 *
 * <p>A planner that cannot make progress tends not to stop — it re-issues the
 * same fetch, gets the same failure, and re-issues it again. On a phone that
 * is someone's battery and data, so the loop needs a floor.
 *
 * <p>Two decisions worth stating:
 *
 * <ul>
 *   <li><b>Identity is tool + action + params.</b> Same question asked twice is
 *       a repeat; the same tool with a different URL is progress.
 *   <li><b>Denied calls count.</b> Otherwise a refused call is free to retry
 *       indefinitely, which is the exact loop this exists to break — and the
 *       one most likely to appear when a confirmation is declined.
 * </ul>
 *
 * <p>Costs nothing until it fires: one map lookup per call, bounded size.
 */
public final class RepeatCallGuard implements ToolPipeline.Guard {

    /**
     * How many identical calls are allowed before refusing.
     *
     * <p>Three was tight for a flaky fetch that legitimately retries.
     * Six still stops a stuck loop; a different URL or param is progress
     * and does not spend this counter.
     */
    public static final int DEFAULT_LIMIT = 6;

    /** Distinct signatures remembered. Bounded so a long task cannot grow it. */
    private static final int MAX_TRACKED = 64;

    private final int limit;

    private final Map<String, Integer> counts =
            new LinkedHashMap<String, Integer>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_TRACKED;
                }
            };

    public RepeatCallGuard() {
        this(DEFAULT_LIMIT);
    }

    public RepeatCallGuard(int limit) {
        this.limit = limit < 1 ? DEFAULT_LIMIT : limit;
    }

    @Override
    public synchronized String denyReason(ToolCall call) {
        if (call == null) return null;

        String key = call.tool() + "|" + call.action() + "|" + call.params();
        int seen = counts.getOrDefault(key, 0) + 1;
        counts.put(key, seen);

        if (seen > limit) {
            return "this exact call has already run " + limit
                    + " times without getting anywhere";
        }
        return null;
    }

    /** Forget everything. Called when a new task starts. */
    public synchronized void reset() {
        counts.clear();
    }

    /** How many times a signature has been seen. For tests and diagnostics. */
    public synchronized int timesSeen(ToolCall call) {
        if (call == null) return 0;
        return counts.getOrDefault(
                call.tool() + "|" + call.action() + "|" + call.params(), 0);
    }
}
