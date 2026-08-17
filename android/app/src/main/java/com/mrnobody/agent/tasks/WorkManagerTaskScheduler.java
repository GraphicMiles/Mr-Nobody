package com.mrnobody.agent.tasks;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * {@link TaskScheduler} backed by Android's WorkManager. V1 runs one-shot,
 * resumable local work; V2 will add recurring schedules (PeriodicWorkRequest)
 * and monitoring behind this same interface.
 */
public final class WorkManagerTaskScheduler implements TaskScheduler {

    @Override
    public void schedule(Context context, long taskId) {
        Data input = new Data.Builder()
                .putLong(TaskWorker.KEY_TASK_ID, taskId)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TaskWorker.class)
                .setInputData(input)
                // Unique name so re-scheduling the same task replaces the prior
                // request instead of stacking duplicates.
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                "task-" + taskId, ExistingWorkPolicy.REPLACE, request);
    }

    @Override
    public void cancel(Context context, long taskId) {
        WorkManager.getInstance(context).cancelUniqueWork("task-" + taskId);
    }
}
