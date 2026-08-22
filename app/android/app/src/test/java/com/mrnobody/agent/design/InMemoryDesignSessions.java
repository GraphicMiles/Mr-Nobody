package com.mrnobody.agent.design;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class InMemoryDesignSessions implements DesignSessionRepository {
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, DesignSession> byTask = new LinkedHashMap<>();
    private final Map<String, Long> quotaKeys = new LinkedHashMap<>();

    @Override public synchronized DesignSession getOrCreate(long taskId, String spec) {
        DesignSession found = byTask.get(taskId);
        if (found != null) return found;
        DesignSession session = DesignSession.create(taskId, spec);
        session.id = ids.incrementAndGet();
        byTask.put(taskId, session);
        return session;
    }
    @Override public synchronized DesignSession findByTask(long taskId) { return byTask.get(taskId); }
    @Override public synchronized void update(DesignSession session) { byTask.put(session.taskId, session); }
    @Override public synchronized boolean tryConsume(long id, DesignQuota.Operation op,
                                                      String idempotencyKey) {
        Long prior = quotaKeys.get(idempotencyKey);
        if (prior != null) return prior == id;
        for (DesignSession s : byTask.values()) {
            if (s.id != id) continue;
            int current;
            switch (op) {
                case CREATE: current = s.createCount; if (current < DesignQuota.MAX_CREATES) s.createCount++; break;
                case EDIT: current = s.editCount; if (current < DesignQuota.MAX_EDITS) s.editCount++; break;
                case EXPORT: current = s.exportCount; if (current < DesignQuota.MAX_EXPORTS) s.exportCount++; break;
                case POLL: default: current = s.pollCount; if (current < DesignQuota.MAX_POLLS) s.pollCount++; break;
            }
            boolean allowed = current < DesignQuota.limit(op);
            if (allowed) quotaKeys.put(idempotencyKey, id);
            return allowed;
        }
        return false;
    }
    @Override public synchronized void clearTask(long taskId) {
        DesignSession removed = byTask.remove(taskId);
        if (removed != null) quotaKeys.values().removeIf(id -> id == removed.id);
    }
    @Override public synchronized void clearAll() { byTask.clear(); quotaKeys.clear(); }
}
