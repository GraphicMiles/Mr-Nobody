package com.mrnobody.agent.dispatcher;

import android.content.Context;

import com.mrnobody.agent.browser.HeadlessSessions;
import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.core.AgentRunContext;
import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.execution.ExecutionLedger;
import com.mrnobody.agent.execution.RunScope;
import com.mrnobody.agent.tasks.EventLogRecorder;
import com.mrnobody.agent.tasks.TaskEventStore;
import com.mrnobody.browser.MrNobodyApp;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Runs a bounded number of isolated on-device task cycles. */
public final class LocalWorker implements Worker {

    public static final int DEFAULT_LANES = 2;

    private final AgentEngine engine;
    private final ExecutionLedger ledger;
    private final Semaphore lanes;

    public LocalWorker(AgentEngine engine) {
        this(engine, ExecutionLedger.NONE, DEFAULT_LANES);
    }

    public LocalWorker(AgentEngine engine, ExecutionLedger ledger) {
        this(engine, ledger, DEFAULT_LANES);
    }

    public LocalWorker(AgentEngine engine, ExecutionLedger ledger, int laneCount) {
        this.engine = engine;
        this.ledger = ledger == null ? ExecutionLedger.NONE : ledger;
        this.lanes = new Semaphore(Math.max(1, laneCount), true);
    }

    @Override public String id() { return "local"; }

    @Override
    public void execute(Context context, Task task, Cancellation cancellation) {
        task.setWorker("local");
        if (!acquire(task, cancellation)) return;

        boolean stats = false;
        boolean session = false;
        try {
            AgentRunContext run = MrNobodyApp.createRunContext(task);
            AgentRunContext.bind(run);
            EventLogRecorder.bind(task.id());
            RunScope.bind(task.id(), task.runId(), ledger);

            task.setStatus(Task.Status.RUNNING);
            // Commit run/provider/platform identity before the first effect.
            try { MrNobodyApp.tasks().update(task); } catch (Throwable ignored) { }

            HeadlessSessions.acquire(context, task.id());
            session = true;
            com.mrnobody.agent.tasks.CompletionStats.beginRun();
            stats = true;
            append(task, TaskEventStore.TASK_STARTED, "local");
            engine.run(context, task, cancellation);
        } catch (Throwable t) {
            task.setError("Local worker failed: "
                    + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            task.setStatus(Task.Status.FAILED);
        } finally {
            String terminal = task.status() == Task.Status.FAILED
                    ? TaskEventStore.TASK_FAILED : TaskEventStore.TASK_FINISHED;
            append(task, terminal, task.status().name());
            if (stats) {
                com.mrnobody.agent.tasks.CompletionStats.endRun(
                        task.status() == Task.Status.COMPLETED);
            }
            RunScope.clear();
            AgentRunContext.clear();
            EventLogRecorder.clear();
            if (session) HeadlessSessions.release(task.id());
            lanes.release();
        }
    }

    private boolean acquire(Task task, Cancellation cancellation) {
        try {
            while (!lanes.tryAcquire(250L, TimeUnit.MILLISECONDS)) {
                if (cancellation != null && cancellation.isCancelled()) {
                    task.setStatus(Task.Status.CANCELLED);
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.setStatus(Task.Status.CANCELLED);
            return false;
        }
    }

    int availableLanes() { return lanes.availablePermits(); }

    private static void append(Task task, String type, String detail) {
        try {
            MrNobodyApp.taskEvents().append(task.id(), type, detail);
        } catch (Throwable ignored) {
            // Event recording must never become a dependency of execution.
        }
    }
}
