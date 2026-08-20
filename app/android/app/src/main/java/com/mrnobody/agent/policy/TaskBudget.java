package com.mrnobody.agent.policy;

import java.util.function.LongSupplier;

/**
 * A wall-clock ceiling on one task run.
 *
 * <p>The step budget ({@link BudgetGuard}) counts work; this counts time.
 * Device evidence showed why both are needed: "whats the time" ground through
 * five pages for 53 seconds and was stopped by the user, not by the engine.
 * The rule is the owner's: on expiry the engine composes an answer from the
 * evidence already in hand — a late answer beats a spinner death.
 *
 * <p>Checked between steps, never mid-step, so the task always stops in a
 * state it can describe.
 */
public final class TaskBudget {

    /** Research runs answer from evidence; ninety seconds is already generous. */
    public static final long RESEARCH_MS = 90_000L;

    /** Download runs pay for link resolution on top; two minutes. */
    public static final long DOWNLOAD_MS = 120_000L;

    /** Test-only overrides — zero means "use the real limits";
     *  negative means "already expired", for deterministic expiry tests. */
    private static volatile long researchOverrideMs = 0L;
    private static volatile long downloadOverrideMs = 0L;

    private final long startMs;
    private final long limitMs;
    private final LongSupplier now;

    private TaskBudget(long limitMs, LongSupplier now) {
        this.limitMs = limitMs;
        this.now = now;
        this.startMs = now.getAsLong();
    }

    /** The budget for a research (answer) task, on the real clock. */
    public static TaskBudget research() {
        long limit = researchOverrideMs != 0 ? researchOverrideMs : RESEARCH_MS;
        return new TaskBudget(limit, System::currentTimeMillis);
    }

    /** The budget for a download task, on the real clock. */
    public static TaskBudget download() {
        long limit = downloadOverrideMs != 0 ? downloadOverrideMs : DOWNLOAD_MS;
        return new TaskBudget(limit, System::currentTimeMillis);
    }

    /** A budget on an injected clock — how the expiry logic is unit-tested. */
    public static TaskBudget of(long limitMs, LongSupplier now) {
        return new TaskBudget(limitMs, now);
    }

    /**
     * Test-only: shrink the limits so an engine test can observe expiry
     * without waiting ninety real seconds. Always paired with
     * {@link #clearLimitOverrides()} in a finally block.
     */
    public static void overrideLimitsForTest(long researchMs, long downloadMs) {
        researchOverrideMs = researchMs;
        downloadOverrideMs = downloadMs;
    }

    /** Test-only: restore the real limits. */
    public static void clearLimitOverrides() {
        researchOverrideMs = 0L;
        downloadOverrideMs = 0L;
    }

    /** Milliseconds spent so far. */
    public long elapsedMs() {
        return Math.max(0L, now.getAsLong() - startMs);
    }

    /** Milliseconds left, never negative. */
    public long remainingMs() {
        return Math.max(0L, limitMs - elapsedMs());
    }

    /** True once the wall has been hit; the caller answers from what it has. */
    public boolean expired() {
        return elapsedMs() >= limitMs;
    }

    /** The configured limit — for tests and log lines, not for decisions. */
    public long limitMs() {
        return limitMs;
    }
}
