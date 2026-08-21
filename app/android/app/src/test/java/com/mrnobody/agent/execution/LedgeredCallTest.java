package com.mrnobody.agent.execution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolResult;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

public class LedgeredCallTest {

    @Test
    public void completedProviderCallIsReplayedWithoutAnotherBillableCall() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        AtomicInteger calls = new AtomicInteger();

        ToolResult first = runOnce(ledger, calls);
        ToolResult replay = runOnce(ledger, calls);

        assertTrue(first.isSuccess());
        assertTrue(replay.isSuccess());
        assertEquals("answer", replay.result());
        assertEquals(1, calls.get());
    }

    @Test
    public void inFlightProviderCallIsNotRepeated() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        RunScope.bind(5L, "run-a", ledger);
        try {
            ExecutionIdentity identity = RunScope.next("ai", "complete",
                    Collections.singletonMap("prompt", "hello"));
            ledger.prepare(identity, "ai", "complete", Tier.EXEC);
            ledger.markRunning(identity);
        } finally {
            RunScope.clear();
        }

        AtomicInteger calls = new AtomicInteger();
        ToolResult result = runOnce(ledger, calls);
        assertTrue(result.isError());
        assertTrue(result.error().contains("unknown outcome"));
        assertEquals(0, calls.get());
    }

    private static ToolResult runOnce(InMemoryExecutionLedger ledger, AtomicInteger calls) {
        RunScope.bind(5L, "run-a", ledger);
        try {
            return LedgeredCall.run("ai", "complete",
                    Collections.singletonMap("prompt", "hello"), () -> {
                        calls.incrementAndGet();
                        return ToolResult.okText("answer");
                    });
        } finally {
            RunScope.clear();
        }
    }
}
