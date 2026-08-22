package com.mrnobody.agent.jobs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.execution.ExecutionLedger;
import com.mrnobody.browser.MrNobodyApp;
import com.mrnobody.browser.TaskNotifier;

/** Reconnects to a persisted external job after backgrounding or process death. */
public final class AsyncJobPollWorker extends Worker {
    public static final String KEY_JOB_ID = "async_job_id";

    public AsyncJobPollWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        String id = getInputData().getString(KEY_JOB_ID);
        if (id == null || id.isEmpty()) return Result.failure();
        AsyncJob job = MrNobodyApp.asyncJobs().find(id);
        if (job == null || job.status.isTerminal()) return Result.success();
        AsyncJobAdapter adapter = MrNobodyApp.asyncJobAdapters().get(job.adapterId);
        if (adapter == null) return Result.retry();
        ExecutionLedger.Entry effect = MrNobodyApp.executionLedger()
                .find(job.idempotencyKey);
        if (effect == null) return Result.failure();
        Cancellation cancellation = this::isStopped;
        AsyncJob updated = MrNobodyApp.asyncJobCoordinator().refresh(
                getApplicationContext(), adapter, job, effect.identity, cancellation);
        if (updated == null) return Result.retry();
        if (!updated.status.isTerminal()) return Result.retry();
        finishTask(updated);
        return Result.success();
    }

    private void finishTask(AsyncJob job) {
        Task task = MrNobodyApp.tasks().get(job.taskId);
        if (task == null || (task.status() != Task.Status.WAITING_EXTERNAL
                && task.status() != Task.Status.RUNNING)) return;
        if (job.status == AsyncJob.Status.SUCCEEDED) {
            task.setResult("Background job completed."
                    + (job.resultRef.isEmpty() ? "" : "\n\n" + job.resultRef));
            task.setError("");
            task.setStatus(Task.Status.COMPLETED);
        } else {
            task.setError(job.error.isEmpty() ? "Background job failed." : job.error);
            task.setStatus(Task.Status.FAILED);
        }
        MrNobodyApp.tasks().update(task);
        TaskNotifier.notifyFinished(getApplicationContext(), task);
    }
}
