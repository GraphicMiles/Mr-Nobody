package com.mrnobody.agent.jobs;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** One reconnectable WorkManager chain per external job. */
public final class WorkManagerAsyncJobScheduler implements AsyncJobScheduler {
    @Override
    public void schedule(Context context, AsyncJob job) {
        if (context == null || job == null || job.status.isTerminal()) return;
        long delay = Math.max(0L, job.nextPollAt - System.currentTimeMillis());
        Data data = new Data.Builder()
                .putString(AsyncJobPollWorker.KEY_JOB_ID, job.localJobId).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AsyncJobPollWorker.class)
                .setInputData(data)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                name(job.localJobId), ExistingWorkPolicy.REPLACE, request);
    }

    @Override public void cancel(Context context, String localJobId) {
        if (context != null && localJobId != null) {
            WorkManager.getInstance(context).cancelUniqueWork(name(localJobId));
        }
    }

    private static String name(String id) { return "async-job-" + id; }
}
