package com.mrnobody.agent.execution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.Collections;

public class RunScopeTest {

    @Test
    public void slotsAreStableAcrossReplayAndSeparateRepeatedEffects() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        RunScope.bind(3L, "run-a", ledger);
        ExecutionIdentity first;
        ExecutionIdentity second;
        try {
            first = RunScope.next("design", "export",
                    Collections.singletonMap("format", "png"));
            second = RunScope.next("design", "export",
                    Collections.singletonMap("format", "png"));
        } finally {
            RunScope.clear();
        }
        assertEquals(0, first.effectSlot());
        assertEquals(1, second.effectSlot());
        assertNotEquals(first.idempotencyKey(), second.idempotencyKey());

        RunScope.bind(3L, "run-a", ledger);
        try {
            ExecutionIdentity replay = RunScope.next("design", "export",
                    Collections.singletonMap("format", "png"));
            assertEquals(first.idempotencyKey(), replay.idempotencyKey());
        } finally {
            RunScope.clear();
        }
    }

    @Test
    public void explicitLogicalStepParticipatesInTheIdentity() {
        RunScope.bind(4L, "run-b", new InMemoryExecutionLedger());
        try (RunScope.StepBinding ignored = RunScope.enterLogicalStep("draft.finalize")) {
            ExecutionIdentity create = RunScope.next("design", "create",
                    Collections.emptyMap());
            ExecutionIdentity export = RunScope.next("design", "export",
                    Collections.emptyMap());
            assertEquals("draft.finalize", create.logicalStepId());
            assertEquals("draft.finalize", export.logicalStepId());
            assertEquals(0, create.effectSlot());
            assertEquals(1, export.effectSlot());
        } finally {
            RunScope.clear();
        }
    }
}
