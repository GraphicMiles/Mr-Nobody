package com.mrnobody.agent.execution;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe in-memory ledger for tests and non-Android hosts. */
public final class InMemoryExecutionLedger implements ExecutionLedger {

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Override
    public synchronized Entry prepare(ExecutionIdentity identity, String tool,
                                      String action, Tier tier) {
        if (identity == null || !identity.isDurable()) return null;
        Entry existing = entries.get(identity.idempotencyKey());
        if (existing != null) return existing;
        long now = System.currentTimeMillis();
        Entry created = new Entry(identity, tool, action, tier, State.PREPARED,
                null, "", 0L, 0L, now, now);
        entries.put(identity.idempotencyKey(), created);
        return created;
    }

    @Override
    public synchronized Entry find(String idempotencyKey) {
        return entries.get(idempotencyKey);
    }

    @Override
    public synchronized void markRunning(ExecutionIdentity identity) {
        replace(identity, State.RUNNING, null, null, null, null);
    }

    @Override
    public synchronized void markWaiting(ExecutionIdentity identity, ToolResult result) {
        replace(identity, State.WAITING, result, null, null, null);
    }

    @Override
    public synchronized void complete(ExecutionIdentity identity, ToolResult result) {
        replace(identity, result != null && result.isSuccess() ? State.SUCCEEDED : State.FAILED,
                result, null, null, null);
    }

    @Override
    public synchronized void markUnknown(ExecutionIdentity identity, String reason) {
        replace(identity, State.UNKNOWN, ToolResult.fail(reason), null, null, null);
    }

    @Override
    public synchronized void setExternalRef(ExecutionIdentity identity, String externalRef) {
        replace(identity, null, null, externalRef, null, null);
    }

    @Override
    public synchronized void reserveCost(ExecutionIdentity identity, long micros) {
        replace(identity, null, null, null, Math.max(0L, micros), null);
    }

    @Override
    public synchronized void commitCost(ExecutionIdentity identity, long micros) {
        replace(identity, null, null, null, null, Math.max(0L, micros));
    }

    @Override
    public synchronized List<Entry> entriesForRun(long taskId, String runId) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.identity.taskId() == taskId
                    && entry.identity.runId().equals(runId == null ? "" : runId)) {
                out.add(entry);
            }
        }
        return out;
    }

    @Override
    public synchronized void clearTask(long taskId) {
        entries.values().removeIf(e -> e.identity.taskId() == taskId);
    }

    @Override
    public synchronized void clearAll() {
        entries.clear();
    }

    private void replace(ExecutionIdentity identity, State state, ToolResult result,
                         String externalRef, Long reserved, Long actual) {
        if (identity == null) return;
        Entry old = entries.get(identity.idempotencyKey());
        if (old == null) return;
        entries.put(identity.idempotencyKey(), new Entry(
                old.identity, old.tool, old.action, old.tier,
                state == null ? old.state : state,
                result == null ? old.result : result,
                externalRef == null ? old.externalRef : externalRef,
                reserved == null ? old.reservedCostMicros : reserved,
                actual == null ? old.actualCostMicros : actual,
                old.createdAt, System.currentTimeMillis()));
    }
}
