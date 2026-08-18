package com.mrnobody.agent.dispatcher;

import android.content.Context;

import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.tasks.EventLogRecorder;

/**
 * Runs a task on-device. Delegates to the AgentEngine (deterministic in V1,
 * LLM-backed in V2). Marking it RUNNING and persisting the result here keeps the
 * worker resumable — state lives in the TaskStore, never in this object.
 */
public final class LocalWorker implements Worker {

    private final AgentEngine engine;

    public LocalWorker(AgentEngine engine) {
        this.engine = engine;
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public void execute(Context context, Task task, Cancellation cancellation) {
        task.setWorker("local");
        task.setStatus(Task.Status.RUNNING);

        // The pipeline is shared and deliberately knows nothing about tasks,
        // so the task id travels on the thread. Without this every tool call
        // would be logged against task 0 and the event log could not say which
        // task did what. Cleared in a finally: a leaked binding would file the
        // next task's calls under this one.
        EventLogRecorder.bind(task.id());
        try {
            engine.run(context, task, cancellation);
        } finally {
            EventLogRecorder.clear();
        }
    }
}
