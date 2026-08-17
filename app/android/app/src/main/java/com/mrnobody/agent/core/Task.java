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

    /**
     * The V1 plan, in order. Progress is derived from how far down this list
     * the task has got, which is why the step label is persisted rather than a
     * number: it survives process death with the rest of the task.
     */
    public static final String[] PLAN = {"Search", "Open page", "Summarize"};

    /** Progress 0..100, derived from the current step's position in {@link #PLAN}. */
    public int progress() {
        switch (status) {
            case COMPLETED:
                return 100;
            case FAILED:
            case CANCELLED:
                return 0;
            default:
                break;
        }
        if (currentStep == null || currentStep.isEmpty()) return 5; // queued/starting
        for (int i = 0; i < PLAN.length; i++) {
            if (PLAN[i].equalsIgnoreCase(currentStep)) {
                // Never report 100 while running: the last step is still work.
                return (int) Math.round((i + 1) * 100.0 / (PLAN.length + 1));
            }
        }
        return 10;
    }
}
