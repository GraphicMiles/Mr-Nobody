package com.mrnobody.agent.dispatcher;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;

/**
 * V2 remote execution (opt-in, encrypted, user-controlled/trusted worker).
 * Declared now so the dispatcher and task model never need a rewrite; the V1
 * implementation refuses to run and marks the task FAILED with a clear reason,
 * because no remote worker exists yet.
 */
public final class RemoteWorker implements Worker {

    @Override
    public String id() {
        return "remote";
    }

    @Override
    public void execute(Context context, Task task, Cancellation cancellation) {
        task.setWorker("remote");
        task.setStatus(Task.Status.FAILED);
        task.setError("Remote worker is not enabled (V2). The task stayed on-device.");
    }
}
