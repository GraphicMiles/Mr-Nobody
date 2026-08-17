package com.mrnobody.agent.dispatcher;

import android.content.Context;

import com.mrnobody.agent.core.Task;

/**
 * A place where a task can execute. V1 ships {@link LocalWorker}; V2 adds a
 * {@link RemoteWorker} behind the same interface so nothing is rewritten.
 */
public interface Worker {

    /** Which worker this is ("local", "remote", "user"). */
    String id();

    /**
     * Execute a task. Must update the task's status/step as it progresses and
     * must be resumable (state lives in the TaskStore, not in memory).
     */
    void execute(Context context, Task task);
}
