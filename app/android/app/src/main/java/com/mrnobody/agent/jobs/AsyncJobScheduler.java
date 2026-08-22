package com.mrnobody.agent.jobs;

import android.content.Context;

public interface AsyncJobScheduler {
    void schedule(Context context, AsyncJob job);
    void cancel(Context context, String localJobId);

    AsyncJobScheduler NONE = new AsyncJobScheduler() {
        @Override public void schedule(Context context, AsyncJob job) { }
        @Override public void cancel(Context context, String id) { }
    };
}
