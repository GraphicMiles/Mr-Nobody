package com.mrnobody.agent.core;

/**
 * A durable unit of work. State must survive process death (persisted via
 * TaskStore). V1 keeps the plan simple/deterministic; V2 adds multi-step
 * LLM planning on the same model without a schema rewrite.
 */
public final class Task {

    public enum Status {
        QUEUED, RUNNING, WAITING, VERIFYING, COMPLETED, FAILED, CANCELLED
    }

    private final long id;
    private final String instruction;
    private Status status;
    private String currentStep;
    private String result;
    private String error;
    private final long createdAt;
    private long updatedAt;
    private int retryCount;
    private String worker; // "local" | "remote" | "user" — which worker runs it

    public Task(long id, String instruction) {
        this.id = id;
        this.instruction = instruction == null ? "" : instruction;
        this.status = Status.QUEUED;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = createdAt;
        this.retryCount = 0;
        this.worker = "local";
    }

    public long id() { return id; }
    public String instruction() { return instruction; }
    public Status status() { return status; }
    public void setStatus(Status s) { this.status = s; this.updatedAt = System.currentTimeMillis(); }
    public String currentStep() { return currentStep; }
    public void setCurrentStep(String s) { this.currentStep = s; this.updatedAt = System.currentTimeMillis(); }
    public String result() { return result; }
    public void setResult(String r) { this.result = r; this.updatedAt = System.currentTimeMillis(); }
    public String error() { return error; }
    public void setError(String e) { this.error = e; this.updatedAt = System.currentTimeMillis(); }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }
    public int retryCount() { return retryCount; }
    public void bumpRetry() { this.retryCount++; this.updatedAt = System.currentTimeMillis(); }
    public String worker() { return worker; }
    public void setWorker(String w) { this.worker = w; }

    /** Progress 0..100, derived from the step index when possible (V1 heuristic). */
    public int progress() {
        switch (status) {
            case COMPLETED: return 100;
            case FAILED: case CANCELLED: return 0;
            default: return 0;
        }
    }
}
