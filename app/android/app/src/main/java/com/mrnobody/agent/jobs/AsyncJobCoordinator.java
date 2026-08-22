package com.mrnobody.agent.jobs;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.execution.ExecutionIdentity;
import com.mrnobody.agent.execution.ExecutionLedger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic submit/poll/reconcile coordinator.
 *
 * <p>It never waits for completion. The durable job can be refreshed by a
 * later WorkManager wake, after process death, or from a remote webhook bridge.
 */
public final class AsyncJobCoordinator {

    private final AsyncJobStore jobs;
    private final ExecutionLedger ledger;
    private final AsyncJobScheduler scheduler;

    public AsyncJobCoordinator(AsyncJobStore jobs, ExecutionLedger ledger) {
        this(jobs, ledger, AsyncJobScheduler.NONE);
    }

    public AsyncJobCoordinator(AsyncJobStore jobs, ExecutionLedger ledger,
                               AsyncJobScheduler scheduler) {
        this.jobs = jobs == null ? AsyncJobStore.NONE : jobs;
        this.ledger = ledger == null ? ExecutionLedger.NONE : ledger;
        this.scheduler = scheduler == null ? AsyncJobScheduler.NONE : scheduler;
    }

    public AsyncJob submit(Context context, AsyncJobAdapter adapter,
                           Map<String, String> request, ExecutionIdentity execution,
                           long reservedCostMicros, Cancellation cancellation) {
        if (adapter == null || execution == null || !execution.isDurable()) {
            return null;
        }
        AsyncJob existing = jobs.findByIdempotencyKey(execution.idempotencyKey());
        if (existing != null && existing.status != AsyncJob.Status.PREPARED) return existing;
        ExecutionLedger.Entry effect = ledger.prepare(execution, adapter.id(),
                "async.submit", Tier.EXEC);
        if (effect == null) return null;

        AsyncJob prepared = existing;
        if (prepared == null) {
            long now = System.currentTimeMillis();
            String localId = "job-" + execution.idempotencyKey().substring(0, 24);
            prepared = new AsyncJob(localId, execution.taskId(), execution.runId(),
                    adapter.id(), execution.idempotencyKey(), execution.operationFingerprint(),
                    AsyncJob.Status.PREPARED, "", "", "", 0L,
                    reservedCostMicros, 0L, now, now);
            if (!jobs.create(prepared)) {
                return jobs.findByIdempotencyKey(execution.idempotencyKey());
            }
        }
        ledger.reserveCost(execution, reservedCostMicros);

        AsyncJob submitting = prepared.with(AsyncJob.Status.SUBMITTING,
                null, null, "", 0L, reservedCostMicros, 0L);
        jobs.update(submitting);
        ledger.markRunning(execution);
        try {
            AsyncJobAdapter.Snapshot snapshot = adapter.submit(context,
                    request == null ? Collections.emptyMap() : request,
                    execution.idempotencyKey(), safe(cancellation));
            if (snapshot == null) throw new IllegalStateException("adapter returned no job state");
            AsyncJob updated = apply(submitting, snapshot, execution);
            if (updated != null && !updated.status.isTerminal()) scheduler.schedule(context, updated);
            return updated;
        } catch (Exception e) {
            AsyncJob unknown = submitting.with(AsyncJob.Status.UNKNOWN,
                    null, null, message(e), 0L, reservedCostMicros, 0L);
            jobs.update(unknown);
            ledger.markUnknown(execution,
                    "Async submission outcome unknown: " + message(e));
            scheduler.schedule(context, unknown);
            return unknown;
        }
    }

    public AsyncJob refresh(Context context, AsyncJobAdapter adapter, AsyncJob job,
                            ExecutionIdentity execution, Cancellation cancellation) {
        if (adapter == null || job == null || job.status.isTerminal()) return job;
        try {
            AsyncJobAdapter.Snapshot snapshot;
            if ((job.status == AsyncJob.Status.UNKNOWN
                    || job.status == AsyncJob.Status.SUBMITTING)
                    && job.externalJobId.isEmpty()) {
                snapshot = adapter.reconcile(context, job.idempotencyKey, safe(cancellation));
                if (snapshot == null) return job;
            } else {
                snapshot = adapter.poll(context, job.externalJobId, safe(cancellation));
            }
            return apply(job, snapshot, execution);
        } catch (Exception e) {
            AsyncJob unknown = job.with(AsyncJob.Status.UNKNOWN,
                    null, null, message(e), job.nextPollAt,
                    job.reservedCostMicros, job.actualCostMicros);
            jobs.update(unknown);
            return unknown;
        }
    }

    public AsyncJob cancel(Context context, AsyncJobAdapter adapter, AsyncJob job,
                           Cancellation cancellation) {
        if (job == null || job.status.isTerminal()) return job;
        try {
            if (adapter == null) {
                AsyncJob unknown = job.with(AsyncJob.Status.UNKNOWN, null, null,
                        "Cancellation could not reach the external adapter", job.nextPollAt,
                        job.reservedCostMicros, job.actualCostMicros);
                jobs.update(unknown);
                return unknown;
            }
            AsyncJobAdapter.Snapshot snapshot =
                    adapter.cancel(context, job.externalJobId, safe(cancellation));
            AsyncJob cancelled = snapshot == null
                    ? job.with(AsyncJob.Status.CANCELLED, null, null,
                            "Cancelled", 0L, job.reservedCostMicros, job.actualCostMicros)
                    : apply(job, snapshot, null);
            jobs.update(cancelled);
            scheduler.cancel(context, job.localJobId);
            return cancelled;
        } catch (Exception e) {
            AsyncJob unknown = job.with(AsyncJob.Status.UNKNOWN, null, null,
                    "Cancellation outcome unknown: " + message(e), job.nextPollAt,
                    job.reservedCostMicros, job.actualCostMicros);
            jobs.update(unknown);
            return unknown;
        }
    }

    private AsyncJob apply(AsyncJob job, AsyncJobAdapter.Snapshot snapshot,
                           ExecutionIdentity execution) {
        if (snapshot == null) return job;
        long nextPoll = snapshot.status.isTerminal() ? 0L
                : System.currentTimeMillis() + snapshot.retryAfterMs;
        AsyncJob updated = job.with(snapshot.status,
                emptyToNull(snapshot.externalJobId),
                emptyToNull(snapshot.resultRef), snapshot.error,
                nextPoll, job.reservedCostMicros, snapshot.actualCostMicros);
        jobs.update(updated);
        if (execution != null) {
            if (!updated.externalJobId.isEmpty()) {
                ledger.setExternalRef(execution, updated.externalJobId);
            }
            if (updated.actualCostMicros > 0) {
                ledger.commitCost(execution, updated.actualCostMicros);
            }
            if (updated.status == AsyncJob.Status.SUCCEEDED) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("jobId", updated.externalJobId);
                value.put("resultRef", updated.resultRef);
                ledger.complete(execution, ToolResult.ok(value));
            } else if (updated.status == AsyncJob.Status.FAILED
                    || updated.status == AsyncJob.Status.CANCELLED) {
                String why = updated.error.isEmpty()
                        ? updated.status.name().toLowerCase() : updated.error;
                ledger.complete(execution, ToolResult.fail(why));
            }
        }
        return updated;
    }

    private static Cancellation safe(Cancellation cancellation) {
        return cancellation == null ? Cancellation.NONE : cancellation;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
