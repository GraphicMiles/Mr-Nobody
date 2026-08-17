package com.mrnobody.agent.tasks;

import com.mrnobody.agent.core.Task;

/**
 * Decides which persisted tasks were abandoned by a process that died.
 *
 * <p>A task row says {@code RUNNING} because a worker said so before it was
 * killed — Android can stop the process at any moment, and nothing writes the
 * ending. Left alone the row stays {@code RUNNING} forever and the UI shows an
 * active task that no longer exists.
 *
 * <p>The rule is pure and lives here so it can be unit-tested on a plain JVM;
 * {@link TaskStore} only applies it.
 */
public final class TaskReconciler {

    /**
     * How long a running task may go without a heartbeat before we treat it as
     * abandoned. Generous on purpose: a single step (a provider call) can take
     * a minute, and the worker heartbeats while it runs, so exceeding this
     * means the process is gone rather than busy.
     */
    public static final long DEFAULT_STALE_AFTER_MS = 5 * 60 * 1000L;

    private TaskReconciler() {
    }

    /** True if this task claims to be in flight but has stopped reporting. */
    public static boolean isStale(Task.Status status, long updatedAt, long now, long staleAfterMs) {
        if (!isInFlight(status)) return false;
        if (updatedAt <= 0) return true;          // never stamped: nothing owns it
        long silentFor = now - updatedAt;
        if (silentFor < 0) return false;          // clock moved backwards; give it time
        return silentFor > staleAfterMs;
    }

    /** Statuses that assert a worker is currently on the task. */
    public static boolean isInFlight(Task.Status status) {
        return status == Task.Status.RUNNING || status == Task.Status.VERIFYING;
    }

    /**
     * What the user is told about a task whose process disappeared. Phrased as
     * an interruption rather than a failure: nothing went wrong with the task
     * itself, the app was stopped.
     */
    public static String interruptedReason() {
        return "Interrupted — the app stopped while this task was running.";
    }
}
