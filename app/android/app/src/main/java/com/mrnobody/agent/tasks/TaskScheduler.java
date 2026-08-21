package com.mrnobody.agent.tasks;

import android.content.Context;

/**
 * Scheduling abstraction. V1 uses Android's basic background execution for
 * immediate/resumable local tasks; V2 adds recurring schedules and monitoring.
 * Declared as an interface so the Android implementation can evolve without
 * touching the agent.
 */
public interface TaskScheduler {

    /** Schedule a task to run in the background (resumable, local-first). */
    void schedule(Context context, long taskId);

    /**
     * Schedule a task to repeat.
     *
     * <p>Separate from {@link #schedule} because a recurring request is a
     * different object with different replace semantics, and conflating them
     * would let a one-shot silently cancel a repeating schedule.
     */
    void scheduleRepeating(Context context, long taskId, Schedule schedule);

    /** Cancel a scheduled task, one-shot or recurring. */
    void cancel(Context context, long taskId);

    /**
     * Cancel and wait for the scheduler to acknowledge it. Stores that have no
     * asynchronous operation can inherit this immediate implementation.
     */
    default boolean cancelAndAwait(Context context, long taskId, long timeoutMs) {
        cancel(context, taskId);
        return true;
    }
}
