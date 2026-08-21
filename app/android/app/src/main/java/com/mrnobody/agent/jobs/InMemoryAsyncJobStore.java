package com.mrnobody.agent.jobs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe async-job store for tests. */
public final class InMemoryAsyncJobStore implements AsyncJobStore {

    private final Map<String, AsyncJob> byId = new LinkedHashMap<>();
    private final Map<String, String> idByKey = new LinkedHashMap<>();

    @Override
    public synchronized boolean create(AsyncJob job) {
        if (job == null || job.idempotencyKey.isEmpty() || job.localJobId.isEmpty()) return false;
        if (idByKey.containsKey(job.idempotencyKey)) return false;
        byId.put(job.localJobId, job);
        idByKey.put(job.idempotencyKey, job.localJobId);
        return true;
    }

    @Override
    public synchronized AsyncJob findByIdempotencyKey(String key) {
        return byId.get(idByKey.get(key));
    }

    @Override
    public synchronized AsyncJob find(String localJobId) {
        return byId.get(localJobId);
    }

    @Override
    public synchronized void update(AsyncJob job) {
        if (job != null && byId.containsKey(job.localJobId)) byId.put(job.localJobId, job);
    }

    @Override
    public synchronized List<AsyncJob> pending() {
        List<AsyncJob> out = new ArrayList<>();
        for (AsyncJob job : byId.values()) if (!job.status.isTerminal()) out.add(job);
        return out;
    }

    @Override
    public synchronized void clearTask(long taskId) {
        List<String> remove = new ArrayList<>();
        for (AsyncJob job : byId.values()) if (job.taskId == taskId) remove.add(job.localJobId);
        for (String id : remove) {
            AsyncJob job = byId.remove(id);
            if (job != null) idByKey.remove(job.idempotencyKey);
        }
    }

    @Override
    public synchronized void clearAll() {
        byId.clear();
        idByKey.clear();
    }
}
