package com.mrnobody.agent.jobs;

import java.util.Collections;
import java.util.List;

/** Persistence boundary for external async jobs. */
public interface AsyncJobStore {

    /** Insert only when the idempotency key is absent. */
    boolean create(AsyncJob job);

    AsyncJob findByIdempotencyKey(String idempotencyKey);

    AsyncJob find(String localJobId);

    void update(AsyncJob job);

    List<AsyncJob> pending();
    List<AsyncJob> jobsForTask(long taskId);

    void clearTask(long taskId);

    void clearAll();

    AsyncJobStore NONE = new AsyncJobStore() {
        @Override public boolean create(AsyncJob job) { return false; }
        @Override public AsyncJob findByIdempotencyKey(String key) { return null; }
        @Override public AsyncJob find(String id) { return null; }
        @Override public void update(AsyncJob job) { }
        @Override public List<AsyncJob> pending() { return Collections.emptyList(); }
        @Override public List<AsyncJob> jobsForTask(long taskId) { return Collections.emptyList(); }
        @Override public void clearTask(long taskId) { }
        @Override public void clearAll() { }
    };
}
