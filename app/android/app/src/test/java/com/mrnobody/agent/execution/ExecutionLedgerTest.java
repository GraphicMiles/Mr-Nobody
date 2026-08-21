package com.mrnobody.agent.execution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolResult;

import org.junit.Test;

import java.util.Collections;

public class ExecutionLedgerTest {

    private static ExecutionIdentity identity(String run) {
        return ExecutionIdentity.of(11L, run, "design.export", 0,
                "design", "export", Collections.singletonMap("format", "png"));
    }

    @Test
    public void prepareIsIdempotentAndCompletedResultIsReplayable() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        ExecutionIdentity identity = identity("run-1");

        ExecutionLedger.Entry first = ledger.prepare(identity, "design", "export", Tier.EXEC);
        ledger.markRunning(identity);
        ledger.complete(identity, ToolResult.okText("artifact-7"));
        ExecutionLedger.Entry second = ledger.prepare(identity, "design", "export", Tier.EXEC);

        assertEquals(ExecutionLedger.State.PREPARED, first.state);
        assertEquals(ExecutionLedger.State.SUCCEEDED, second.state);
        assertNotNull(second.result);
        assertEquals("artifact-7", second.result.result());
        assertEquals(1, ledger.entriesForRun(11L, "run-1").size());
    }

    @Test
    public void costsAndExternalReferenceSurviveStateChanges() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        ExecutionIdentity identity = identity("run-2");
        ledger.prepare(identity, "design", "export", Tier.EXEC);
        ledger.reserveCost(identity, 50_000L);
        ledger.setExternalRef(identity, "job-99");
        ledger.commitCost(identity, 42_000L);
        ledger.complete(identity, ToolResult.okText("done"));

        ExecutionLedger.Entry saved = ledger.find(identity.idempotencyKey());
        assertEquals("job-99", saved.externalRef);
        assertEquals(50_000L, saved.reservedCostMicros);
        assertEquals(42_000L, saved.actualCostMicros);
        assertTrue(saved.hasReplayableResult());
    }

    @Test
    public void aNewRunDoesNotReplayThePreviousRun() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        ExecutionIdentity first = identity("run-1");
        ExecutionIdentity second = identity("run-2");
        ledger.prepare(first, "design", "export", Tier.EXEC);
        ledger.complete(first, ToolResult.okText("first"));

        assertEquals(ExecutionLedger.State.PREPARED,
                ledger.prepare(second, "design", "export", Tier.EXEC).state);
        assertEquals(2, ledger.entriesForRun(11L, "run-1").size()
                + ledger.entriesForRun(11L, "run-2").size());
    }
}
