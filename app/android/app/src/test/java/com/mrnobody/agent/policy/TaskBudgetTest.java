package com.mrnobody.agent.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

/** Rule 5: a wall-clock ceiling the engine checks between steps. */
public class TaskBudgetTest {

    @After
    public void tearDown() {
        TaskBudget.clearLimitOverrides();
    }

    @Test
    public void researchAndDownloadLimitsAreTheAgreedOnes() {
        assertEquals(90_000L, TaskBudget.RESEARCH_MS);
        assertEquals(120_000L, TaskBudget.DOWNLOAD_MS);
        assertEquals(TaskBudget.RESEARCH_MS, TaskBudget.research().limitMs());
        assertEquals(TaskBudget.DOWNLOAD_MS, TaskBudget.download().limitMs());
    }

    @Test
    public void expiresExactlyAtTheWall() {
        AtomicLong clock = new AtomicLong(1_000L);
        TaskBudget budget = TaskBudget.of(90_000L, clock::get);
        assertFalse(budget.expired());
        clock.set(90_999L);
        assertFalse(budget.expired());
        assertEquals(1L, budget.remainingMs());
        clock.set(91_000L);
        assertTrue(budget.expired());
        assertEquals(0L, budget.remainingMs());
    }

    @Test
    public void aBackwardsClockNeverExpiresTheBudgetEarly() {
        AtomicLong clock = new AtomicLong(50_000L);
        TaskBudget budget = TaskBudget.of(10_000L, clock::get);
        clock.set(40_000L); // clock adjusted backwards mid-task
        assertEquals(0L, budget.elapsedMs());
        assertFalse(budget.expired());
    }

    @Test
    public void testOverridesShrinkTheLimitsAndClearRestoresThem() {
        TaskBudget.overrideLimitsForTest(5L, 7L);
        assertEquals(5L, TaskBudget.research().limitMs());
        assertEquals(7L, TaskBudget.download().limitMs());
        TaskBudget.clearLimitOverrides();
        assertEquals(TaskBudget.RESEARCH_MS, TaskBudget.research().limitMs());
        assertEquals(TaskBudget.DOWNLOAD_MS, TaskBudget.download().limitMs());
    }
}
