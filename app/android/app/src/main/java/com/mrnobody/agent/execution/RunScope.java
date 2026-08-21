package com.mrnobody.agent.execution;

import java.util.HashMap;
import java.util.Map;

/**
 * The durable execution cycle currently bound to this worker thread.
 *
 * <p>The allocation counters restart when a killed run is replayed. Because
 * completed calls return their ledgered result, the same control flow assigns
 * the same logical operation and effect slot until the first uncommitted step.
 */
public final class RunScope {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private RunScope() {
    }

    public static void bind(long taskId, String runId, ExecutionLedger ledger) {
        CURRENT.set(new State(taskId, runId,
                ledger == null ? ExecutionLedger.NONE : ledger));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static long currentTaskId() {
        State state = CURRENT.get();
        return state == null ? 0L : state.taskId;
    }

    public static String currentRunId() {
        State state = CURRENT.get();
        return state == null ? "" : state.runId;
    }

    public static ExecutionLedger ledger() {
        State state = CURRENT.get();
        return state == null ? ExecutionLedger.NONE : state.ledger;
    }

    /**
     * Override the derived logical step while a controller executes one named
     * step. Closing restores the previous name.
     */
    public static StepBinding enterLogicalStep(String logicalStepId) {
        State state = CURRENT.get();
        if (state == null) return StepBinding.NONE;
        String previous = state.logicalStepId;
        state.logicalStepId = logicalStepId == null ? "" : logicalStepId.trim();
        return new StepBinding(state, previous);
    }

    /** Allocate the stable identity for the next occurrence of this operation. */
    public static ExecutionIdentity next(String tool, String action,
                                         Map<String, String> params) {
        State state = CURRENT.get();
        if (state == null || state.taskId <= 0 || state.runId.isEmpty()) {
            return ExecutionIdentity.ephemeral(tool, action, params);
        }
        String fingerprint = ExecutionIdentity.fingerprint(tool, action, params);
        String logical = state.logicalStepId;
        if (logical == null || logical.isEmpty()) {
            String prefix = fingerprint.substring(0, Math.min(12, fingerprint.length()));
            logical = ExecutionIdentity.safePart(tool) + "."
                    + ExecutionIdentity.safePart(action) + "." + prefix;
        }
        // Slots count every effect inside the logical step, not per operation
        // fingerprint. One step may create and then export, and both need
        // distinct rows under the ledger's (task,run,step,slot) uniqueness rule.
        String counterKey = logical;
        int slot = state.slots.getOrDefault(counterKey, 0);
        state.slots.put(counterKey, slot + 1);
        return ExecutionIdentity.of(state.taskId, state.runId, logical, slot,
                tool, action, params);
    }

    private static final class State {
        final long taskId;
        final String runId;
        final ExecutionLedger ledger;
        final Map<String, Integer> slots = new HashMap<>();
        String logicalStepId = "";

        State(long taskId, String runId, ExecutionLedger ledger) {
            this.taskId = taskId;
            this.runId = runId == null ? "" : runId.trim();
            this.ledger = ledger;
        }
    }

    public static final class StepBinding implements AutoCloseable {
        static final StepBinding NONE = new StepBinding(null, null);
        private final State state;
        private final String previous;
        private boolean closed;

        StepBinding(State state, String previous) {
            this.state = state;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed || state == null) return;
            closed = true;
            state.logicalStepId = previous == null ? "" : previous;
        }
    }
}
