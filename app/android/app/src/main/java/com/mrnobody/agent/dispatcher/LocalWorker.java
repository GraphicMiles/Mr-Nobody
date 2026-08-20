package com.mrnobody.agent.dispatcher;

import android.content.Context;

import com.mrnobody.agent.browser.HeadlessSessions;
import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.tasks.EventLogRecorder;
import com.mrnobody.agent.tasks.TaskEventStore;
import com.mrnobody.browser.MrNobodyApp;

/**
 * Runs a task on-device. Delegates to the AgentEngine (deterministic in V1,
 * LLM-backed in V2). Marking it RUNNING and persisting the result here keeps the
 * worker resumable — state lives in the TaskStore, never in this object.
 */
public final class LocalWorker implements Worker {

    private final AgentEngine engine;

    /**
     * The current engine owns mutable planner, guard and tool state. Run one
     * local task at a time until those objects become per-run values. This is
     * deliberate back-pressure, not accidental WorkManager concurrency.
     */
    private final Object executionLock = new Object();

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

        synchronized (executionLock) {
            task.setStatus(Task.Status.RUNNING);

            // TaskScope is propagated by ToolPipeline onto its executor. The
            // worker still owns the outer binding and always clears it, so a
            // reused WorkManager thread cannot inherit the next task's id.
            EventLogRecorder.bind(task.id());
            HeadlessSessions.acquire(context, task.id());
            com.mrnobody.agent.tasks.CompletionStats.beginRun();
            try {
                // A run boundary is part of the event model. It prevents a
                // follow-up or recurring wake from inheriting the previous
                // run's pipeline in the chat renderer.
                append(task, TaskEventStore.TASK_STARTED, "local");
                engine.run(context, task, cancellation);
            } finally {
                String terminal = task.status() == Task.Status.FAILED
                        ? TaskEventStore.TASK_FAILED : TaskEventStore.TASK_FINISHED;
                append(task, terminal, task.status().name());
                com.mrnobody.agent.tasks.CompletionStats.endRun(
                        task.status() == Task.Status.COMPLETED);
                EventLogRecorder.clear();
                HeadlessSessions.release(task.id());
            }
        }
    }

    private static void append(Task task, String type, String detail) {
        try {
            MrNobodyApp.taskEvents().append(task.id(), type, detail);
        } catch (Throwable ignored) {
            // Event recording must never become a dependency of execution.
        }
    }
}
