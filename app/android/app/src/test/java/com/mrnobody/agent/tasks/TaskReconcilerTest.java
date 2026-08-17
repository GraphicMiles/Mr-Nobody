package com.mrnobody.agent.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Task;

import org.junit.Test;

/**
 * The rule that decides whether a task was abandoned. Pure logic on purpose:
 * getting it wrong either leaves tasks stuck RUNNING forever (the bug) or kills
 * tasks that are merely slow (worse).
 */
public class TaskReconcilerTest {

    private static final long STALE_AFTER = 5 * 60 * 1000L;
    private static final long NOW = 1_000_000_000L;

    @Test
    public void aRunningTaskThatStoppedReportingIsStale() {
        long lastHeartbeat = NOW - STALE_AFTER - 1;
        assertTrue(TaskReconciler.isStale(Task.Status.RUNNING, lastHeartbeat, NOW, STALE_AFTER));
    }

    @Test
    public void aRunningTaskStillHeartbeatingIsNot() {
        long lastHeartbeat = NOW - 20_000L; // one heartbeat interval ago
        assertFalse(TaskReconciler.isStale(Task.Status.RUNNING, lastHeartbeat, NOW, STALE_AFTER));
    }

    @Test
    public void aSlowStepIsNotAnAbandonedTask() {
        // A provider call can take 90s; the worker heartbeats through it.
        long lastHeartbeat = NOW - 90_000L;
        assertFalse(TaskReconciler.isStale(Task.Status.RUNNING, lastHeartbeat, NOW, STALE_AFTER));
    }

    @Test
    public void verifyingCountsAsInFlight() {
        long lastHeartbeat = NOW - STALE_AFTER - 1;
        assertTrue(TaskReconciler.isStale(Task.Status.VERIFYING, lastHeartbeat, NOW, STALE_AFTER));
    }

    @Test
    public void finishedTasksAreNeverReconciled() {
        long ancient = NOW - 10 * STALE_AFTER;
        for (Task.Status status : new Task.Status[]{
                Task.Status.COMPLETED, Task.Status.FAILED, Task.Status.CANCELLED,
                Task.Status.QUEUED, Task.Status.WAITING}) {
            assertFalse(status.name() + " must not be reconciled",
                    TaskReconciler.isStale(status, ancient, NOW, STALE_AFTER));
        }
    }

    @Test
    public void queuedWorkIsLeftAloneNoMatterHowOld() {
        // Queued means "no worker has claimed it", not "a worker died".
        assertFalse(TaskReconciler.isStale(Task.Status.QUEUED, 1L, NOW, STALE_AFTER));
    }

    @Test
    public void aRowThatWasNeverStampedIsStale() {
        assertTrue(TaskReconciler.isStale(Task.Status.RUNNING, 0L, NOW, STALE_AFTER));
    }

    @Test
    public void aClockThatMovedBackwardsDoesNotKillLiveTasks() {
        // Device clock jumps (timezone, NTP) must not look like silence.
        long heartbeatInTheFuture = NOW + 60_000L;
        assertFalse(TaskReconciler.isStale(
                Task.Status.RUNNING, heartbeatInTheFuture, NOW, STALE_AFTER));
    }

    @Test
    public void inFlightIsExactlyRunningAndVerifying() {
        assertTrue(TaskReconciler.isInFlight(Task.Status.RUNNING));
        assertTrue(TaskReconciler.isInFlight(Task.Status.VERIFYING));
        assertFalse(TaskReconciler.isInFlight(Task.Status.QUEUED));
        assertFalse(TaskReconciler.isInFlight(Task.Status.WAITING));
        assertFalse(TaskReconciler.isInFlight(Task.Status.COMPLETED));
    }

    @Test
    public void theUserIsToldTheAppStopped_notThatTheirTaskFailed() {
        assertEquals("Interrupted — the app stopped while this task was running.",
                TaskReconciler.interruptedReason());
    }
}
