package com.mrnobody.agent.dispatcher;

import android.content.Context;

import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.core.Task;

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
    public void execute(Context context, Task task) {
        task.setWorker("local");
        task.setStatus(Task.Status.RUNNING);
        engine.run(context, task);
    }
}
