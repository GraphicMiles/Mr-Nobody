package com.mrnobody.agent.execution;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolResult;

import java.util.Map;

/**
 * Idempotency boundary for consequential external calls that are not tools,
 * such as billed AI-provider requests.
 */
public final class LedgeredCall {

    public interface Work {
        ToolResult run() throws Exception;
    }

    private LedgeredCall() {
    }

    /**
     * Execute once for this run. A completed result is replayed; an in-flight
     * or unknown prior attempt is not repeated because the remote side may
     * already have accepted or billed it.
     */
    public static ToolResult run(String owner, String action, Map<String, String> params,
                                 Work work) {
        ExecutionIdentity identity = RunScope.next(owner, action, params);
        ExecutionLedger ledger = RunScope.ledger();
        if (!identity.isDurable() || ledger == ExecutionLedger.NONE) {
            return invoke(work);
        }

        ExecutionLedger.Entry entry = ledger.prepare(identity, owner, action, Tier.EXEC);
        if (entry == null) {
            return ToolResult.fail("Execution ledger unavailable; the external call was not made.");
        }
        if (entry.state == ExecutionLedger.State.SUCCEEDED && entry.result != null) {
            return entry.result;
        }
        if (entry.state == ExecutionLedger.State.FAILED && entry.result != null) {
            return entry.result;
        }
        if (entry.state == ExecutionLedger.State.RUNNING
                || entry.state == ExecutionLedger.State.UNKNOWN) {
            String message = "A previous " + owner + "." + action
                    + " attempt has an unknown outcome; it was not repeated.";
            ledger.markUnknown(identity, message);
            return ToolResult.fail(message);
        }

        ledger.markRunning(identity);
        try {
            ToolResult result = work.run();
            if (result == null) result = ToolResult.fail("external call returned nothing");
            ledger.complete(identity, result);
            return result;
        } catch (Throwable t) {
            String message = "The " + owner + "." + action
                    + " outcome is unknown: " + describe(t);
            ledger.markUnknown(identity, message);
            return ToolResult.fail(message);
        }
    }

    private static ToolResult invoke(Work work) {
        try {
            ToolResult result = work.run();
            return result == null ? ToolResult.fail("external call returned nothing") : result;
        } catch (Throwable t) {
            return ToolResult.fail(describe(t));
        }
    }

    private static String describe(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return message == null || message.isEmpty()
                ? (t == null ? "unknown failure" : t.getClass().getSimpleName())
                : message;
    }
}
