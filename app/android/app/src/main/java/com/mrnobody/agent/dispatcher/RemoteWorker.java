package com.mrnobody.agent.dispatcher;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.execution.ExecutionIdentity;
import com.mrnobody.agent.execution.ExecutionLedger;
import com.mrnobody.agent.tasks.TaskEventDetail;
import com.mrnobody.agent.tasks.TaskEventStore;
import com.mrnobody.agent.tasks.TaskStreamHub;
import com.mrnobody.agent.util.EndpointPolicy;
import com.mrnobody.browser.MrNobodyApp;
import com.mrnobody.browser.net.NetworkGate;
import com.mrnobody.identity.AndroidKeyStoreIdentity;
import com.mrnobody.identity.DeviceIdentity;
import com.mrnobody.remote.RemoteClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

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

    private final Supplier<String> serverUrl;

    public RemoteWorker(String serverUrl) {
        this(() -> serverUrl);
    }

    /** Resolve settings at dispatch time so changing the endpoint needs no restart. */
    public RemoteWorker(Supplier<String> serverUrl) {
        this.serverUrl = serverUrl == null ? () -> "" : serverUrl;
    }

    @Override
    public String id() {
        return "remote";
    }

    @Override
    public void execute(Context context, Task task, Cancellation cancellation) {
        task.setWorker("remote");
        task.setStatus(Task.Status.RUNNING);
        try { MrNobodyApp.tasks().update(task); } catch (Throwable ignored) { }
        append(task, TaskEventStore.TASK_STARTED, "remote");
        append(task, TaskEventStore.STEP_CHANGED, TaskEventDetail.activity(
                "Running on the remote worker", "remote",
                "Use the explicitly configured worker for this task."));

        String endpoint = serverUrl.get();
        endpoint = endpoint == null ? "" : endpoint.trim();
        if (endpoint.isEmpty()) {
            task.setError("Remote worker is not configured. No task data was sent.");
            task.setStatus(Task.Status.FAILED);
            append(task, TaskEventStore.TASK_FAILED, task.error());
            return;
        }

        String endpointProblem = EndpointPolicy.secureBaseReason(endpoint);
        if (endpointProblem != null) {
            String message = "Remote worker is not configured safely: " + endpointProblem + ". No task data was sent.";
            task.failIf(Task.Status.RUNNING, message);
            append(task, TaskEventStore.TASK_FAILED, message);
            return;
        }

        final long taskId = task.id();
        final java.util.concurrent.atomic.AtomicBoolean terminal =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final StringBuilder streamed = new StringBuilder();
        RemoteClient client = new RemoteClient(endpoint, NetworkGate::openHttp);
        try {
            DeviceIdentity identity = AndroidKeyStoreIdentity.loadOrCreate();
            long remoteId = submitOnce(client, identity, task);
            if (remoteId < 0) throw new java.io.IOException(
                    "remote submission outcome could not be recovered");

            // Forward the result stream to the same hub the local path uses.
            // A terminal transition is compare-and-set: transport errors that
            // race after done/error cannot rewrite the durable outcome.
            client.stream(remoteId, (type, text) -> {
                if (terminal.get()) return;
                switch (type) {
                    case "token":
                        streamed.append(text == null ? "" : text);
                        TaskStreamHub.instance().emitToken(taskId, text);
                        break;
                    case "done": {
                        String answer = text == null || text.isEmpty()
                                ? streamed.toString() : text;
                        if (!task.completeIf(Task.Status.RUNNING, answer)) {
                            terminal.set(true);
                            return;
                        }
                        terminal.set(true);
                        append(task, TaskEventStore.AGENT_ANSWER, answer);
                        append(task, TaskEventStore.TURN_PRESENTATION,
                                TaskEventDetail.presentation(task.artifacts()));
                        append(task, TaskEventStore.TASK_FINISHED, "COMPLETED");
                        TaskStreamHub.instance().emitDone(taskId, answer);
                        break;
                    }
                    case "error": {
                        String message = text == null || text.isEmpty()
                                ? "Remote worker reported an error" : text;
                        if (!task.failIf(Task.Status.RUNNING, message)) {
                            terminal.set(true);
                            return;
                        }
                        terminal.set(true);
                        append(task, TaskEventStore.TASK_FAILED, message);
                        TaskStreamHub.instance().emitError(taskId, message);
                        break;
                    }
                    default:
                        break;
                }
            }, cancellation);

            if (!terminal.get()) {
                throw new java.io.IOException("result stream ended before a terminal event");
            }
        } catch (Exception e) {
            if (terminal.get()) return;
            if (cancellation != null && cancellation.isCancelled()) {
                if (task.transitionStatus(Task.Status.RUNNING, Task.Status.CANCELLED)) {
                    append(task, TaskEventStore.TASK_FINISHED, "CANCELLED");
                }
                return;
            }
            String message = "Remote worker failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (task.failIf(Task.Status.RUNNING, message)) {
                append(task, TaskEventStore.TASK_FAILED, message);
                TaskStreamHub.instance().emitError(taskId, message);
            }
        }
    }

    /** Submit once per run and reconnect to the committed remote id on retry. */
    private static long submitOnce(RemoteClient client, DeviceIdentity identity, Task task)
            throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("instruction", task.instruction());
        params.put("worker", "remote");
        ExecutionIdentity execution = ExecutionIdentity.of(task.id(), task.runId(),
                "remote.submit", 0, "remote-worker", "submit", params);
        ExecutionLedger ledger = MrNobodyApp.executionLedger();
        ExecutionLedger.Entry entry = ledger == null ? null
                : ledger.prepare(execution, "remote-worker", "submit", Tier.EXEC);
        if (entry == null) {
            throw new java.io.IOException("execution ledger unavailable; remote task was not submitted");
        }
        if (entry.state == ExecutionLedger.State.SUCCEEDED && entry.result != null) {
            Object prior = entry.result.value().get("remoteTaskId");
            if (prior instanceof Number) return ((Number) prior).longValue();
            try { return Long.parseLong(String.valueOf(prior)); }
            catch (Exception ignored) { }
        }

        // The remote contract receives the same key on every recovery attempt;
        // a compliant server returns the original task instead of creating one.
        ledger.markRunning(execution);
        final long remoteId;
        try {
            // Use the stable key as the signed nonce as well as the explicit
            // request key, so a server cannot accept an altered dedup identity.
            remoteId = client.submit(identity, execution.idempotencyKey(),
                    task.instruction(), execution.idempotencyKey());
        } catch (Exception e) {
            ledger.markUnknown(execution, "Remote submission outcome unknown: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            throw e;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("remoteTaskId", remoteId);
        ToolResult committed = ToolResult.ok(value);
        ledger.setExternalRef(execution, String.valueOf(remoteId));
        ledger.complete(execution, committed);
        return remoteId;
    }

    private static void append(Task task, String type, String detail) {
        try {
            MrNobodyApp.taskEvents().append(task.id(), type, detail);
        } catch (Throwable ignored) {
            // The task outcome is authoritative even if its trace cannot be written.
        }
    }
}
