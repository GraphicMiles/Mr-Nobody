package com.mrnobody.agent.design;

public interface DesignSessionRepository {
    DesignSession getOrCreate(long taskId, String spec);
    DesignSession findByTask(long taskId);
    void update(DesignSession session);
    boolean tryConsume(long sessionId, DesignQuota.Operation operation,
                       String idempotencyKey);
    void clearTask(long taskId);
    void clearAll();
}
