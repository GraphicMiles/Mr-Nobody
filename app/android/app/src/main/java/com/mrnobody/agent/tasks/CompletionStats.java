package com.mrnobody.agent.tasks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unattended-completion rate for the current week.
 *
 * <p>The brief: most search / read / monitor / sandbox-download tasks
 * should finish with zero CONFIRM prompts. This is the number we tune
 * the tiers against. Target ~90%, never 100% — 100% means something
 * risky started auto-allowing.
 *
 * <p>Pure counters. Persistence is optional and best-effort; a process
 * death loses the in-flight week, not the policy.
 */
public final class CompletionStats {

    public static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    public static final double TARGET = 0.90;

    private static final ThreadLocal<Boolean> ASKED = new ThreadLocal<>();

    private static long weekStart = System.currentTimeMillis();
    private static int finished;
    private static int unattended;
    private static int interrupted;

    private CompletionStats() {
    }

    /** Call at the start of a worker run. */
    public static void beginRun() {
        ASKED.set(Boolean.FALSE);
    }

    /** A CONFIRM fired or the task parked WAITING. */
    public static void markConfirm() {
        ASKED.set(Boolean.TRUE);
    }

    /**
     * Close the run. Only {@code completed} tasks count toward the rate.
     * Failed / cancelled / waiting are not "completed unattended".
     */
    public static synchronized void endRun(boolean completed) {
        boolean asked = Boolean.TRUE.equals(ASKED.get());
        ASKED.remove();
        if (!completed) return;
        rollWeek();
        finished++;
        if (asked) interrupted++;
        else unattended++;
    }

    public static synchronized void reset() {
        weekStart = System.currentTimeMillis();
        finished = 0;
        unattended = 0;
        interrupted = 0;
        ASKED.remove();
    }

    /** 1.0 when there is no data yet — not a claim that we hit the target. */
    public static synchronized double unattendedRate() {
        if (finished == 0) return Double.NaN;
        return unattended / (double) finished;
    }

    public static synchronized int finished() {
        return finished;
    }

    public static synchronized int unattended() {
        return unattended;
    }

    public static synchronized int interrupted() {
        return interrupted;
    }

    public static synchronized Map<String, Object> snapshot() {
        rollWeek();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("finished", finished);
        m.put("unattended", unattended);
        m.put("interrupted", interrupted);
        m.put("target", TARGET);
        double rate = unattendedRate();
        m.put("rate", Double.isNaN(rate) ? null : rate);
        m.put("weekStart", weekStart);
        return m;
    }

    private static void rollWeek() {
        long now = System.currentTimeMillis();
        if (now - weekStart < WEEK_MS) return;
        weekStart = now;
        finished = 0;
        unattended = 0;
        interrupted = 0;
    }
}
