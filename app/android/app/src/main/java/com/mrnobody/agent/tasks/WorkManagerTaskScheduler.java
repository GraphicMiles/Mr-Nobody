package com.mrnobody.agent.tasks;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * {@link TaskScheduler} backed by Android's WorkManager: one-shot resumable
 * local work, plus recurring schedules via PeriodicWorkRequest.
 *
 * <p>Recurring work is deliberately coarse. WorkManager's floor is 15 minutes
 * and the OS coalesces wakeups to save battery, so a schedule is a request for
 * "about this often", never a guarantee of a moment. {@link Schedule} clamps
 * to that floor rather than pretending finer granularity exists.
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
    public void scheduleRepeating(Context context, long taskId, Schedule schedule) {
        if (schedule == null || !schedule.isRecurring()) {
            schedule(context, taskId);
            return;
        }

        Data input = new Data.Builder()
                .putLong(TaskWorker.KEY_TASK_ID, taskId)
                .build();

        long intervalMs = schedule.effectiveIntervalMs();
        long delayMs = Math.max(0L, schedule.firstRunAt() - System.currentTimeMillis());

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                TaskWorker.class, intervalMs, TimeUnit.MILLISECONDS)
                .setInputData(input)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build();

        // KEEP, not REPLACE: replacing a periodic request resets its interval,
        // so an app that re-registers schedules on every start would push the
        // next run forever into the future and the task would never fire.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                repeatingName(taskId), ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @Override
    public void cancel(Context context, long taskId) {
        WorkManager.getInstance(context).cancelUniqueWork("task-" + taskId);
        WorkManager.getInstance(context).cancelUniqueWork(repeatingName(taskId));
    }

    @Override
    public boolean cancelAndAwait(Context context, long taskId, long timeoutMs) {
        WorkManager manager = WorkManager.getInstance(context);
        androidx.work.Operation oneShot = manager.cancelUniqueWork("task-" + taskId);
        androidx.work.Operation repeating = manager.cancelUniqueWork(repeatingName(taskId));
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
        try {
            oneShot.getResult().get(Math.max(1L, deadline - System.currentTimeMillis()),
                    TimeUnit.MILLISECONDS);
            repeating.getResult().get(Math.max(1L, deadline - System.currentTimeMillis()),
                    TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            com.mrnobody.debug.ErrorLog.record(
                    "could not confirm task schedule cancellation for " + taskId + ": " + e);
            return false;
        }
    }

    private static String repeatingName(long taskId) {
        return "task-repeat-" + taskId;
    }
}
