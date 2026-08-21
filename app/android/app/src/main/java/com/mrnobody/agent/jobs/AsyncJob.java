package com.mrnobody.agent.jobs;

/** Durable normalized state for a submit/poll/retrieve external job. */
public final class AsyncJob {

    public enum Status {
        PREPARED,
        SUBMITTING,
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        UNKNOWN;

        public boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    public final String localJobId;
    public final long taskId;
    public final String runId;
    public final String adapterId;
    public final String idempotencyKey;
    public final String operationFingerprint;
    public final Status status;
    public final String externalJobId;
    public final String resultRef;
    public final String error;
    public final long nextPollAt;
    public final long reservedCostMicros;
    public final long actualCostMicros;
    public final long createdAt;
    public final long updatedAt;

    public AsyncJob(String localJobId, long taskId, String runId, String adapterId,
                    String idempotencyKey, String operationFingerprint, Status status,
                    String externalJobId, String resultRef, String error,
                    long nextPollAt, long reservedCostMicros, long actualCostMicros,
                    long createdAt, long updatedAt) {
        this.localJobId = clean(localJobId);
        this.taskId = taskId;
        this.runId = clean(runId);
        this.adapterId = clean(adapterId);
        this.idempotencyKey = clean(idempotencyKey);
        this.operationFingerprint = clean(operationFingerprint);
        this.status = status == null ? Status.UNKNOWN : status;
        this.externalJobId = clean(externalJobId);
        this.resultRef = clean(resultRef);
        this.error = clean(error);
        this.nextPollAt = Math.max(0L, nextPollAt);
        this.reservedCostMicros = Math.max(0L, reservedCostMicros);
        this.actualCostMicros = Math.max(0L, actualCostMicros);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public AsyncJob with(Status nextStatus, String externalId, String nextResultRef,
                         String nextError, long pollAt, long reserved, long actual) {
        return new AsyncJob(localJobId, taskId, runId, adapterId, idempotencyKey,
                operationFingerprint, nextStatus,
                externalId == null ? externalJobId : externalId,
                nextResultRef == null ? resultRef : nextResultRef,
                nextError == null ? error : nextError,
                pollAt, reserved, actual, createdAt, System.currentTimeMillis());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
