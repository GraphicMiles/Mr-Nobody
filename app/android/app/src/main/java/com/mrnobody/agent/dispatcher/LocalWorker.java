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

    /** Tasks running right now, so the shared headless WebView is closed only
     * when the last one finishes — never while another task is driving it. */
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE =
            new java.util.concurrent.atomic.AtomicInteger();

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
        ACTIVE.incrementAndGet();

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
            // A task's browser work drives the headless WebView, whose renderer
            // holds tens of megabytes. Leaving it alive across tasks accumulates
            // memory until the OS kills the app — the repeated crash on "watch
            // the price of bitcoin". Close it after the LAST concurrent task;
            // it is recreated lazily on the next run.
            if (ACTIVE.decrementAndGet() == 0) {
                try {
                    com.mrnobody.browser.MrNobodyApp.headlessEngine().close();
                } catch (Throwable ignored) {
                    // Tearing down a WebView must never take the worker with it.
                }
            }
        }
    }
}
