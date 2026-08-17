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

    /** Cancel a scheduled task. */
    void cancel(Context context, long taskId);
}
