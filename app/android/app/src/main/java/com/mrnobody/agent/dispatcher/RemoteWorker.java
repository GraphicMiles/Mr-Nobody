package com.mrnobody.agent.dispatcher;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.tasks.TaskStreamHub;
import com.mrnobody.browser.net.NetworkGate;
import com.mrnobody.identity.AndroidKeyStoreIdentity;
import com.mrnobody.identity.DeviceIdentity;
import com.mrnobody.remote.RemoteClient;

import java.util.UUID;

/**
 * Executes a task on the remote worker: sign with the device identity, submit,
 * stream the result back through {@link TaskStreamHub} so the same task chat
 * that renders a local stream renders a remote one.
 *
 * <p>The remote worker <em>executes</em> tasks, so it sees URLs and page
 * content in plaintext. Transport encryption and anonymous identity are real
 * and claimable; "the server cannot see what your task does" is false and must
 * never be printed. Local stays the default: a task only reaches this worker
 * when something has explicitly marked it remote, and this worker fails
 * honestly when no server is configured rather than pretending it ran.
 */
public final class RemoteWorker implements Worker {

    private final String serverUrl;

    public RemoteWorker(String serverUrl) {
        this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
    }

    @Override
    public String id() {
        return "remote";
    }

    @Override
    public void execute(Context context, Task task, Cancellation cancellation) {
        task.setWorker("remote");
        task.setStatus(Task.Status.RUNNING);

        if (serverUrl.isEmpty()) {
            task.setError("Remote worker is not configured. The task stayed on-device.");
            task.setStatus(Task.Status.FAILED);
            return;
        }

        final long taskId = task.id();
        RemoteClient client = new RemoteClient(serverUrl, NetworkGate::openHttp);
        try {
            DeviceIdentity identity = AndroidKeyStoreIdentity.loadOrCreate();
            long remoteId = client.submit(identity, UUID.randomUUID().toString(), task.instruction());

            // Forward the result stream to the same hub the local path uses,
            // and persist the finished answer onto the task row.
            client.stream(remoteId, (type, text) -> {
                switch (type) {
                    case "token":
                        TaskStreamHub.instance().emitToken(taskId, text);
                        break;
                    case "done":
                        task.setResult(text);
                        task.setStatus(Task.Status.COMPLETED);
                        TaskStreamHub.instance().emitDone(taskId, text);
                        break;
                    case "error":
                        task.setError(text);
                        task.setStatus(Task.Status.FAILED);
                        TaskStreamHub.instance().emitError(taskId, text);
                        break;
                    default:
                        break;
                }
            }, cancellation);
        } catch (Exception e) {
            task.setError("Remote worker failed: " + e.getMessage());
            task.setStatus(Task.Status.FAILED);
            TaskStreamHub.instance().emitError(taskId, task.error());
        }
    }
}
