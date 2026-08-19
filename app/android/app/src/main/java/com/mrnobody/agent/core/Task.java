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
    /** Latest follow-up in this thread, or empty. Does not replace instruction. */
    private String followUp = "";
    /** JSON shortlist the next turn can address by index. */
    private String artifacts = "";
    /** Last plan snapshot so a kill mid-run is reconstructable. */
    private String planJson = "";

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
    /** A fresh execution cycle gets a fresh retry budget (recurring re-runs). */
    public void resetRetry() { this.retryCount = 0; this.updatedAt = System.currentTimeMillis(); }
    /** Restore the persisted value when a task is reloaded from storage. */
    public void setRetryCount(int n) { this.retryCount = Math.max(0, n); }
    public String worker() { return worker; }
    public void setWorker(String w) { this.worker = w; }
    public String followUp() { return followUp == null ? "" : followUp; }
    public void setFollowUp(String text) {
        this.followUp = text == null ? "" : text;
        this.updatedAt = System.currentTimeMillis();
    }

    public String artifacts() { return artifacts == null ? "" : artifacts; }
    public void setArtifacts(String json) {
        this.artifacts = json == null ? "" : json;
        this.updatedAt = System.currentTimeMillis();
    }

    public String planJson() { return planJson == null ? "" : planJson; }
    public void setPlanJson(String json) {
        this.planJson = json == null ? "" : json;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * What this run should honour: the follow-up if the user just replied,
     * otherwise the original ask. Recurring wakes use the original only
     * (the worker clears follow-up first).
     */
    public String activeInstruction() {
        String extra = followUp();
        return extra.isEmpty() ? instruction : extra;
    }

    /**
     * Original plus follow-up, so "download it" still sees a site named in
     * the first message, and a monitor follow-up still contains "keep up".
     */
    public String conversation() {
        String extra = followUp();
        if (extra.isEmpty()) return instruction;
        return instruction + "\n\nFollow-up from the user:\n" + extra;
    }

    public static final String STEP_SEARCH = "Search";
    public static final String STEP_READ = "Read sources";
    public static final String STEP_RESOLVE_DOWNLOAD = "Resolve download";
    public static final String STEP_ANSWER = "Answer";
    public static final String STEP_VERIFY = "Verify";

    /** A routed tool call, which is a whole plan on its own. */
    public static final String STEP_ACT = "Act";

    /**
     * The V1 plan, in order — and the actual one. Progress is derived from how
     * far down this list the task has got, which is why the step label is
     * persisted rather than a number: it survives process death with the rest
     * of the task.
     */
    public static final String[] PLAN = {STEP_SEARCH, STEP_READ, STEP_ANSWER, STEP_VERIFY};

    /**
     * The plan for a routed action: one step, done or not.
     *
     * <p>Two plans rather than one variable-length list because progress is
     * derived from a step's position, and a router that picks a single tool
     * has no meaningful "25%". Reporting a one-step task against the
     * four-step plan would have it sitting at 25% and then finishing.
     */
    public static final String[] ACTION_PLAN = {STEP_ACT};

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

        // A routed action is one step, so it has no meaningful fraction: it is
        // underway until it is done. Reporting it against the research plan
        // would park it at 25% and then jump to 100.
        if (STEP_ACT.equalsIgnoreCase(currentStep)) return 50;

        for (int i = 0; i < PLAN.length; i++) {
            if (PLAN[i].equalsIgnoreCase(currentStep)) {
                // Never report 100 while running: the last step is still work.
                return (int) Math.round((i + 1) * 100.0 / (PLAN.length + 1));
            }
        }
        return 10;
    }
}
