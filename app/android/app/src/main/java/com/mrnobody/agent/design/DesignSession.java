package com.mrnobody.agent.design;

/** Durable parent for many bounded design-task runs. */
public final class DesignSession {
    public enum Status {
        DRAFTING,
        AWAITING_CREATIVE_REVIEW,
        READY,
        FINALIZING,
        FINALIZED,
        ABANDONED,
        FAILED
    }

    public long id;
    public long taskId;
    public String platform = "canva-mcp";
    public String artifactRef = "";
    public String revision = "";
    public String candidateRef = "";
    public String candidateOptions = "";
    public String generationJobId = "";
    public String previewRef = "";
    public String exportRef = "";
    public String pendingJobId = "";
    public String designSpec = "";
    public Status status = Status.DRAFTING;
    public ReviewGate safetyGate = ReviewGate.PENDING;
    public ReviewGate creativeGate = ReviewGate.PENDING;
    public ReviewGate finalizationGate = ReviewGate.NOT_REQUIRED;
    public int createCount;
    public int editCount;
    public int exportCount;
    public int pollCount;
    public long createdAt;
    public long updatedAt;

    public static DesignSession create(long taskId, String spec) {
        DesignSession session = new DesignSession();
        session.taskId = taskId;
        session.designSpec = spec == null ? "" : spec;
        session.createdAt = System.currentTimeMillis();
        session.updatedAt = session.createdAt;
        return session;
    }
}
