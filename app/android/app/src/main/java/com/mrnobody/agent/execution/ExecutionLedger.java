package com.mrnobody.agent.execution;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolResult;

import java.util.Collections;
import java.util.List;

/**
 * Authoritative, replayable execution state.
 *
 * <p>This is intentionally separate from the task event log. Events are a
 * bounded user-facing audit trail; this ledger owns durable run/step identity,
 * idempotency, replay results, external references, and cost accounting.
 */
public interface ExecutionLedger {

    enum State {
        PREPARED,
        RUNNING,
        WAITING,
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    final class Entry {
        public final ExecutionIdentity identity;
        public final String tool;
        public final String action;
        public final Tier tier;
        public final State state;
        public final ToolResult result;
        public final String externalRef;
        public final long reservedCostMicros;
        public final long actualCostMicros;
        public final long createdAt;
        public final long updatedAt;

        public Entry(ExecutionIdentity identity, String tool, String action, Tier tier,
                     State state, ToolResult result, String externalRef,
                     long reservedCostMicros, long actualCostMicros,
                     long createdAt, long updatedAt) {
            this.identity = identity;
            this.tool = tool == null ? "" : tool;
            this.action = action == null ? "" : action;
            this.tier = tier == null ? Tier.READ : tier;
            this.state = state == null ? State.UNKNOWN : state;
            this.result = result;
            this.externalRef = externalRef == null ? "" : externalRef;
            this.reservedCostMicros = Math.max(0L, reservedCostMicros);
            this.actualCostMicros = Math.max(0L, actualCostMicros);
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public boolean hasReplayableResult() {
            return result != null && (state == State.SUCCEEDED || state == State.FAILED);
        }
    }

    /** Insert PREPARED if absent and return the authoritative current entry. */
    Entry prepare(ExecutionIdentity identity, String tool, String action, Tier tier);

    Entry find(String idempotencyKey);

    void markRunning(ExecutionIdentity identity);

    void markWaiting(ExecutionIdentity identity, ToolResult result);

    void complete(ExecutionIdentity identity, ToolResult result);

    void markUnknown(ExecutionIdentity identity, String reason);

    void setExternalRef(ExecutionIdentity identity, String externalRef);

    void reserveCost(ExecutionIdentity identity, long micros);

    void commitCost(ExecutionIdentity identity, long micros);

    List<Entry> entriesForRun(long taskId, String runId);

    void clearTask(long taskId);

    void clearAll();

    /** No persistence, used outside a task and by small isolated tests. */
    ExecutionLedger NONE = new ExecutionLedger() {
        @Override public Entry prepare(ExecutionIdentity i, String t, String a, Tier tier) { return null; }
        @Override public Entry find(String key) { return null; }
        @Override public void markRunning(ExecutionIdentity i) { }
        @Override public void markWaiting(ExecutionIdentity i, ToolResult r) { }
        @Override public void complete(ExecutionIdentity i, ToolResult r) { }
        @Override public void markUnknown(ExecutionIdentity i, String reason) { }
        @Override public void setExternalRef(ExecutionIdentity i, String ref) { }
        @Override public void reserveCost(ExecutionIdentity i, long micros) { }
        @Override public void commitCost(ExecutionIdentity i, long micros) { }
        @Override public List<Entry> entriesForRun(long taskId, String runId) {
            return Collections.emptyList();
        }
        @Override public void clearTask(long taskId) { }
        @Override public void clearAll() { }
    };
}
