package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;

import com.mrnobody.agent.tasks.ChangeDetector;

import org.junit.Test;

/**
 * The retry-budget semantics behind the worker's FAILED guard. A recurring
 * task must never be permanently silenced by a transient failure — the fix to
 * "watch bitcoin died after one DNS hiccup" — and the budget must survive a
 * reload from storage.
 */
public class TaskRetryTest {

    @Test
    public void bumpAndResetAreDistinctOperations() {
        Task t = new Task(1, "watch bitcoin");
        t.bumpRetry();
        assertEquals(1, t.retryCount());
        t.resetRetry();
        assertEquals(0, t.retryCount());
    }

    @Test
    public void aFreshCycleGetsAFreshBudget() {
        // One-shot: bump once, then a re-run resets — so a "check again" on a
        // previously-failed task is not born one strike from death.
        Task t = new Task(2, "find laptops");
        t.bumpRetry();
        assertEquals(1, t.retryCount());
        t.resetRetry();
        assertEquals(0, t.retryCount());
    }

    @Test
    public void setRetryCountRestoresAPersistedValue() {
        Task t = new Task(3, "x");
        t.setRetryCount(2);
        assertEquals(2, t.retryCount());
        // Negative values clamp to zero rather than corrupting the guard.
        t.setRetryCount(-5);
        assertEquals(0, t.retryCount());
    }

    @Test
    public void persistedTimestampsSurviveRestoration() {
        Task t = new Task(4, "old task", 1_000L, 4_000L);
        assertEquals(1_000L, t.createdAt());
        assertEquals(4_000L, t.updatedAt());

        // Field setters are used while a cursor is decoded, then the durable
        // update time is re-applied once restoration is complete.
        t.setResult("restored");
        t.restoreUpdatedAt(4_000L);
        assertEquals(1_000L, t.createdAt());
        assertEquals(4_000L, t.updatedAt());
    }

    @Test
    public void invalidPersistedTimestampsFailToSaneValues() {
        Task t = new Task(5, "x", 0L, 0L);
        assertEquals(true, t.createdAt() > 0L);
        assertEquals(t.createdAt(), t.updatedAt());
    }

    @Test
    public void theChangeMarkersAreTheExactStringsTheWorkerLooksFor() {
        // The worker's isNoChange() checks result.contains(NO_CHANGE); the
        // engine appends the same constant. Pinning the literal guards the two
        // from drifting apart.
        assertEquals("No change since your last check.", ChangeDetector.NO_CHANGE);
        assertEquals("Changed since your last check.", ChangeDetector.CHANGED);
        assertEquals(true, ("Bitcoin is 64282.\n\n" + ChangeDetector.NO_CHANGE)
                .contains(ChangeDetector.NO_CHANGE));
    }
}
