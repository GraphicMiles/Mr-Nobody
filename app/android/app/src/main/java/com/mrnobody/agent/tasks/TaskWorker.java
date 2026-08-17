package com.mrnobody.agent.tasks;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.browser.MrNobodyApp;
import com.mrnobody.browser.TaskNotifier;
import com.mrnobody.debug.ErrorLog;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background worker that executes a single persisted task. This is what makes
 * tasks resumable: the task's state lives in the TaskStore (not memory), and
 * WorkManager wakes this worker even after the process is killed.
 *
 * <p>The worker is intentionally thin — it re-loads the task, dispatches it
 * through the same TaskDispatcher the foreground path uses, checkpoints the
 * result, and exits. While it runs it does two extra things that only it can:
 * it <em>heartbeats</em> the row so a killed process can be told apart from a
 * slow step, and it <em>observes cancellation</em> so a user's stop request is
 * honoured at the next safe boundary.
 */
public final class TaskWorker extends Worker {

    public static final String KEY_TASK_ID = "task_id";

    /** How often the row is stamped while work is in flight. */
    private static final long HEARTBEAT_MS = 20_000L;

    /** A cancel flag is re-read from storage at most this often. */
    private static final long CANCEL_POLL_MS = 500L;

    public TaskWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        long taskId = getInputData().getLong(KEY_TASK_ID, -1);
        if (taskId < 0) {
            ErrorLog.record("TaskWorker: missing task_id");
            return Result.failure();
        }

        // Anything left RUNNING by a process that died is closed out before we
        // add more work; see TaskReconciler.
        MrNobodyApp.tasks().reconcileStale(TaskReconciler.DEFAULT_STALE_AFTER_MS);

        Task task = MrNobodyApp.tasks().get(taskId);
        if (task == null) {
            ErrorLog.record("TaskWorker: task " + taskId + " not found");
            return Result.failure();
        }

        // A task cancelled before it ever started never runs.
        if (MrNobodyApp.tasks().isCancelRequested(taskId)) {
            finishCancelled(task);
            return Result.success();
        }

        // Idempotent guard: never re-run finished work.
        switch (task.status()) {
            case COMPLETED:
            case CANCELLED:
                return Result.success();
            case FAILED:
                // Bounded retry: only re-run a failed task once.
                if (task.retryCount() > 0) return Result.success();
                task.bumpRetry();
                break;
            default:
                break;
        }

        ScheduledExecutorService heartbeat = startHeartbeat(taskId);
        try {
            MrNobodyApp.dispatcher().dispatch(getApplicationContext(), task, cancellationFor(taskId));
        } finally {
            heartbeat.shutdownNow();
        }

        if (task.status() == Task.Status.CANCELLED) {
            finishCancelled(task);
            return Result.success();
        }

        MrNobodyApp.tasks().update(task);

        if (task.status() == Task.Status.COMPLETED) {
            // The app may well be closed by now — this is the only way the user
            // finds out (V1 §13).
            TaskNotifier.notifyFinished(getApplicationContext(), task);
            return Result.success();
        }
        if (task.status() == Task.Status.FAILED) {
            ErrorLog.record("TaskWorker: task " + taskId + " failed: " + task.error());
            if (task.retryCount() > 0) {
                TaskNotifier.notifyFinished(getApplicationContext(), task);
            }
            // Let WorkManager apply its backoff; the FAILED guard above bounds
            // how many times we actually re-execute.
            return Result.retry();
        }
        return Result.success();
    }

    /**
     * Cancellation seen by the engine: either the user asked (a durable flag),
     * or WorkManager is stopping us. Storage is polled, not hammered.
     */
    private Cancellation cancellationFor(long taskId) {
        return new Cancellation() {
            private long checkedAt;
            private boolean cancelled;

            @Override
            public boolean isCancelled() {
                if (cancelled) return true;
                if (isStopped()) {
                    cancelled = true;
                    return true;
                }
                long now = System.currentTimeMillis();
                if (now - checkedAt < CANCEL_POLL_MS) return false;
                checkedAt = now;
                cancelled = MrNobodyApp.tasks().isCancelRequested(taskId);
                return cancelled;
            }
        };
    }

    private ScheduledExecutorService startHeartbeat(long taskId) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-heartbeat-" + taskId);
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(
                () -> MrNobodyApp.tasks().touch(taskId),
                HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        return executor;
    }

    /** Persist the cancelled outcome and retire the request that caused it. */
    private void finishCancelled(Task task) {
        task.setStatus(Task.Status.CANCELLED);
        task.setCurrentStep("");
        MrNobodyApp.tasks().update(task);
        MrNobodyApp.tasks().clearCancelRequest(task.id());
        // No notification: the user just asked for this, they know.
    }
}
