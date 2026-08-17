package com.mrnobody.agent.dispatcher;

import android.content.Context;

import com.mrnobody.agent.core.Task;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes a task to a worker. V1 has only the LocalWorker; the registry is a map
 * so a RemoteWorker (V2) or UserControlledWorker (future) can register without
 * touching this class.
 */
public final class TaskDispatcher {

    private final Map<String, Worker> workers = new LinkedHashMap<>();
    private final String defaultWorkerId;

    public TaskDispatcher(String defaultWorkerId) {
        this.defaultWorkerId = defaultWorkerId;
    }

    public void register(Worker worker) {
        workers.put(worker.id(), worker);
    }

    public void dispatch(Context context, Task task) {
        Worker w = workers.get(task.worker());
        if (w == null) w = workers.get(defaultWorkerId);
        if (w == null) {
            task.setError("no worker available");
            task.setStatus(Task.Status.FAILED);
            return;
        }
        w.execute(context, task);
    }

    /** A task that runs on the device (privacy default). */
    public boolean isLocal(Task task) {
        return defaultWorkerId.equals(task.worker());
    }
}
