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
    /**
     * How often a running task reports in. Sourced from {@link Heartbeat} so
     * the writer and the reader of a beat can never disagree about the
     * interval -- a liveness check calibrated to a different period than the
     * beat itself either kills live tasks or never fires.
     */
    private static final long HEARTBEAT_MS = Heartbeat.INTERVAL_MS;

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
        // Faster sweep for tasks that were beating and stopped. Silence from a
        // task that had been reporting is a dead worker, not a slow one, so it
        // can be recovered in seconds instead of waiting out the stale window.
        MrNobodyApp.tasks().reconcileDead(Heartbeat.DEAD_AFTER_MS);

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

        // Idempotent guard: never re-run finished work — except a recurring
        // task, whose whole point is to run again. A recurring COMPLETED task
        // is reset and re-executed, keeping its last answer for the change
        // note; a cancelled task stays cancelled forever.
        boolean recurring = MrNobodyApp.tasks().scheduleOf(taskId).isRecurring();
        switch (task.status()) {
            case CANCELLED:
                return Result.success();
            case COMPLETED:
                if (!recurring) {
                    return Result.success();
                }
                MrNobodyApp.tasks().setPreviousResult(taskId, task.result());
                task.setStatus(Task.Status.QUEUED);
                task.setCurrentStep("");
                task.setResult("");
                task.setError("");
                task.setFollowUp(""); // a timer wake is the original ask, not a reply
                task.resetRetry(); // a fresh cycle gets a fresh retry budget
                break;
            case FAILED:
                if (recurring) {
                    // A transient failure (DNS hiccup, one blocked fetch) must
                    // not kill a monitor. The periodic schedule fires again
                    // next interval, so give up this cycle without consuming a
                    // budget that would eventually silence it forever.
                    return Result.success();
                }
                // One-shot: bounded retry, then give up.
                if (task.retryCount() > 0) return Result.success();
                task.bumpRetry();
                break;
            default:
                break;
        }

        // Web search and rendered extraction can outlive the visible activity.
        // Promote this WorkManager run before starting them so Android and OEM
        // battery policies do not suspend the task merely because the user
        // switched apps. Failure is logged but does not erase the task.
        try {
            setForegroundAsync(TaskForeground.info(getApplicationContext(), task))
                    .get(10, TimeUnit.SECONDS);
        } catch (Throwable e) {
            ErrorLog.record("TaskWorker: could not enter foreground: " + e);
        }

        // Stamped before dispatch: a recurring schedule measures from when a
        // run began, so a crash mid-run still counts as an attempt and cannot
        // spin into a retry loop that fires continuously.
        MrNobodyApp.tasks().markRun(taskId);

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

        if (task.status() == Task.Status.WAITING) {
            MrNobodyApp.tasks().update(task);
            TaskNotifier.notifyWaiting(getApplicationContext(), task);
            return Result.success();
        }

        MrNobodyApp.tasks().update(task);

        if (task.status() == Task.Status.COMPLETED) {
            // The app may well be closed by now — this is the only way the user
            // finds out (V1 §13). But a recurring run that reports "no change"
            // is not news: an hourly monitor must not buzz every hour forever.
            // Notify only when the answer actually moved, or for one-shots.
            if (!recurring || !isNoChange(task)) {
                TaskNotifier.notifyFinished(getApplicationContext(), task);
            }
            return Result.success();
        }
        if (task.status() == Task.Status.FAILED) {
            ErrorLog.record("TaskWorker: task " + taskId + " failed: " + task.error());
            if (task.retryCount() > 0) {
                TaskNotifier.notifyFinished(getApplicationContext(), task);
            }
            // Let WorkManager apply its backoff; the FAILED guard above bounds
            // how many times we actually re-execute. A recurring task never
            // reaches here — it returns before dispatch when failed.
            return Result.retry();
        }
        return Result.success();
    }

    /** True when a recurring answer reports that nothing changed since last time. */
    private static boolean isNoChange(Task task) {
        String result = task.result();
        return result != null && result.contains(ChangeDetector.NO_CHANGE);
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
                () -> {
                    // touch() advances the row's age so the stale sweep leaves
                    // a live task alone; beat() records liveness proper. Both,
                    // because the two reconcilers read different columns.
                    MrNobodyApp.tasks().touch(taskId);
                    MrNobodyApp.tasks().beat(taskId);
                },
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
