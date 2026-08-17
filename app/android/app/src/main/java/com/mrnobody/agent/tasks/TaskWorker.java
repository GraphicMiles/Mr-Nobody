package com.mrnobody.agent.tasks;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mrnobody.agent.core.Task;
import com.mrnobody.browser.MrNobodyApp;
import com.mrnobody.browser.TaskNotifier;
import com.mrnobody.debug.ErrorLog;

/**
 * Background worker that executes a single persisted task. This is what makes
 * tasks resumable: the task's state lives in the TaskStore (not memory), and
 * WorkManager wakes this worker even after the process is killed.
 *
 * The worker is intentionally thin — it re-loads the task, dispatches it through
 * the same TaskDispatcher the foreground path uses, checkpoints the result, and
 * exits. It never depends on the UI or on a process staying alive.
 */
public final class TaskWorker extends Worker {

    public static final String KEY_TASK_ID = "task_id";

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

        Task task = MrNobodyApp.tasks().get(taskId);
        if (task == null) {
            ErrorLog.record("TaskWorker: task " + taskId + " not found");
            return Result.failure();
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

        // Execute via the same dispatcher the foreground path uses (local worker).
        MrNobodyApp.dispatcher().dispatch(getApplicationContext(), task);
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
}
