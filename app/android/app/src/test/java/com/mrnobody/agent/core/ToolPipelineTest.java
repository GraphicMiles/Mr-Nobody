package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.mrnobody.agent.execution.ExecutionIdentity;
import com.mrnobody.agent.execution.InMemoryExecutionLedger;
import com.mrnobody.agent.execution.RunScope;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The pipeline is where every promise the agent makes is actually kept, so
 * each promise gets a test: nothing runs unvalidated, nothing runs unpermitted,
 * a guard can only take permission away, an unanswerable confirmation does not
 * proceed, a hanging tool does not hang the agent, a throwing tool does not
 * crash it, and a tool cannot return something it did not declare.
 */
public class ToolPipelineTest {

    private static final Context NO_CONTEXT = null; // no tool here touches Android

    // ---------------------------------------------------------------- fakes

    /** A tool that records what it was given and returns whatever it was told to. */
    static class FakeTool implements Tool {
        final ToolSpec spec;
        final AtomicInteger runs = new AtomicInteger();
        ToolResult next = ToolResult.okText("done");
        RuntimeException throwOnRun;
        long sleepMs;

        FakeTool(ToolSpec spec) {
            this.spec = spec;
        }

        @Override public ToolSpec spec() { return spec; }

        @Override
        public ToolResult execute(Context context, ToolRequest request) {
            runs.incrementAndGet();
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.fail("interrupted");
                }
            }
            if (throwOnRun != null) throw throwOnRun;
            return next;
        }
    }

    private static ToolSpec readSpec() {
        return ToolSpec.named("reader")
                .describedAs("Reads things.")
                .tier(Tier.READ)
                .param(ParamSpec.url("url", true, "Where to read from."))
                .returns(OutputSpec.of(v -> String.valueOf(v.get("text")), "text"))
                .timeout(1_000)
                .build();
    }

    private static ToolRequest goodRequest() {
        return ToolRequest.of("read", "url", "https://example.com");
    }

    // ----------------------------------------------------------- validation

    @Test
    public void aCallWithBadParametersNeverReachesTheTool() {
        FakeTool tool = new FakeTool(readSpec());
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        ToolResult result = pipeline.run(NO_CONTEXT, tool,
                ToolRequest.of("read", "url", "javascript:alert(1)"));

        assertTrue(result.isError());
        assertTrue(result.error(), result.error().contains("http(s) URL"));
        assertEquals("the tool must not have run", 0, tool.runs.get());
    }

    @Test
    public void aMissingRequiredParameterIsReportedNotGuessed() {
        FakeTool tool = new FakeTool(readSpec());
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        ToolResult result = pipeline.run(NO_CONTEXT, tool, ToolRequest.of("read"));

        assertTrue(result.isError());
        assertTrue(result.error(), result.error().contains("url is required"));
        assertEquals(0, tool.runs.get());
    }

    @Test
    public void anUndeclaredParameterIsRejected() {
        FakeTool tool = new FakeTool(readSpec());
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("url", "https://example.com");
        params.put("shell", "rm -rf /");
        ToolResult result = pipeline.run(NO_CONTEXT, tool, new ToolRequest("read", params));

        assertTrue(result.isError());
        assertTrue(result.error(), result.error().contains("unknown parameter"));
        assertEquals(0, tool.runs.get());
    }

    // -------------------------------------------------------------- policy

    @Test
    public void execWithNoOneToAskDoesNotRun() {
        // A task woken in the background has no UI. "Nobody answered" must mean
        // "did not happen".
        FakeTool tool = new FakeTool(ToolSpec.named("shell")
                .tier(Tier.EXEC)
                .param(ParamSpec.string("cmd", true, ""))
                .returns(OutputSpec.of(v -> "ran", "text"))
                .build());
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        ToolResult result = pipeline.run(NO_CONTEXT, tool, ToolRequest.of("run", "cmd", "sha256 x"));

        assertTrue(result.isError());
        assertTrue(result.needsApproval());
        assertTrue(result.error(), result.error().contains("Needs your approval"));
        assertEquals(0, tool.runs.get());
    }

    @Test
    public void unavailableConfirmationParksRatherThanDeclining() {
        FakeTool tool = new FakeTool(ToolSpec.named("shell")
                .tier(Tier.EXEC)
                .param(ParamSpec.string("cmd", true, ""))
                .returns(OutputSpec.of(v -> "ran", "text"))
                .build());
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());
        pipeline.setConfirmer((call, reason) -> Confirmation.UNAVAILABLE);

        ToolResult result = pipeline.run(NO_CONTEXT, tool, ToolRequest.of("run", "cmd", "sha256 x"));

        assertTrue(result.needsApproval());
        assertEquals("shell", result.pendingTool());
        assertEquals(0, tool.runs.get());
        assertFalse(result.error(), result.error().startsWith("Declined"));
    }

    @Test
    public void execRunsOnceSomeoneApproves() {
        FakeTool tool = new FakeTool(ToolSpec.named("shell")
                .tier(Tier.EXEC)
                .param(ParamSpec.string("cmd", true, ""))
                .returns(OutputSpec.of(v -> String.valueOf(v.get("text")), "text"))
                .build());
        tool.next = ToolResult.okText("ok");
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());
        AtomicBoolean asked = new AtomicBoolean();
        pipeline.setConfirmer((call, reason) -> {
            asked.set(true);
            return Confirmation.ALLOWED;
        });

        ToolResult result = pipeline.run(NO_CONTEXT, tool, ToolRequest.of("run", "cmd", "sha256 x"));

        assertTrue(asked.get());
        assertTrue(result.isSuccess());
        assertEquals(1, tool.runs.get());
    }

    @Test
    public void aDeclinedConfirmationStopsTheCall() {
        FakeTool tool = new FakeTool(ToolSpec.named("shell")
                .tier(Tier.EXEC)
                .param(ParamSpec.string("cmd", true, ""))
                .returns(OutputSpec.of(v -> "ran", "text"))
                .build());
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());
        pipeline.setConfirmer((call, reason) -> Confirmation.DENIED);

        ToolResult result = pipeline.run(NO_CONTEXT, tool, ToolRequest.of("run", "cmd", "sha256 x"));

        assertTrue(result.isError());
        assertTrue(result.error(), result.error().startsWith("Declined"));
        assertEquals(0, tool.runs.get());
    }

    // -------------------------------------------------------------- guards

    @Test
    public void aGuardCanTakePermissionAway() {
        FakeTool tool = new FakeTool(readSpec());
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval())
                .addGuard(call -> call.params().get("url").contains("example.com")
                        ? "example.com is blocked" : null);

        ToolResult result = pipeline.run(NO_CONTEXT, tool, goodRequest());

        assertTrue(result.isError());
        assertTrue(result.error(), result.error().contains("example.com is blocked"));
        assertEquals(0, tool.runs.get());
    }

    @Test
    public void aGuardCannotGrantPermission() {
        // The Guard interface only expresses "deny" or "say nothing" — there is
        // no way to write a guard that allows what the policy refused. This
        // test documents that as a property, so a future signature change that
        // adds ALLOW has to break it deliberately.
        FakeTool tool = new FakeTool(ToolSpec.named("shell")
                .tier(Tier.EXEC)
                .param(ParamSpec.string("cmd", true, ""))
                .returns(OutputSpec.of(v -> "ran", "text"))
                .build());
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval())
                .addGuard(call -> null); // abstain — the strongest a guard can be

        ToolResult result = pipeline.run(NO_CONTEXT, tool, ToolRequest.of("run", "cmd", "x"));

        assertTrue("abstaining must not turn CONFIRM into ALLOW", result.isError());
        assertEquals(0, tool.runs.get());
    }

    // ------------------------------------------------------------ execution

    @Test
    public void aToolThatThrowsBecomesAFailedResult() {
        FakeTool tool = new FakeTool(readSpec());
        tool.throwOnRun = new IllegalStateException("engine exploded");
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        ToolResult result = pipeline.run(NO_CONTEXT, tool, goodRequest());

        assertTrue(result.isError());
        assertTrue(result.error(), result.error().contains("engine exploded"));
    }

    @Test
    public void aToolThatHangsIsAbandonedAtItsDeadline() {
        FakeTool tool = new FakeTool(readSpec()); // 1s timeout
        tool.sleepMs = 5_000;
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        long started = System.currentTimeMillis();
        ToolResult result = pipeline.run(NO_CONTEXT, tool, goodRequest());
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(result.isError());
        assertTrue(result.error(), result.error().contains("timed out"));
        assertTrue("should give up near the deadline, took " + elapsed + "ms", elapsed < 3_000);
    }

    @Test
    public void cancellationAbandonsASlowToolWithoutWaitingForTheTimeout() {
        FakeTool tool = new FakeTool(ToolSpec.named("slow")
                .tier(Tier.READ)
                .returns(OutputSpec.of(v -> "x", "text"))
                .timeout(30_000)
                .build());
        tool.sleepMs = 30_000;
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        long started = System.currentTimeMillis();
        ToolResult result = pipeline.run(NO_CONTEXT, tool, ToolRequest.of("go"), () -> true);
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(result.isError());
        assertTrue(result.error(), result.error().contains("cancelled"));
        assertTrue("cancel must not wait for the 30s budget, took " + elapsed + "ms",
                elapsed < 3_000);
    }

    // --------------------------------------------------------------- output

    @Test
    public void aToolCannotReturnAShapeItDidNotDeclare() {
        FakeTool tool = new FakeTool(readSpec());
        Map<String, Object> wrong = new LinkedHashMap<>();
        wrong.put("body", "the text is under the wrong key");
        tool.next = ToolResult.ok(wrong);
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        ToolResult result = pipeline.run(NO_CONTEXT, tool, goodRequest());

        assertTrue(result.isError());
        assertTrue(result.error(), result.error().contains("missing \"text\""));
    }

    @Test
    public void aToolCannotSmuggleARawPageThroughItsResult() {
        // The whole reason the contract exists.
        FakeTool tool = new FakeTool(readSpec());
        tool.next = ToolResult.okText("<!doctype html><html><head><title>x</title></head>"
                + "<body><div>ads</div></body></html>");
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        ToolResult result = pipeline.run(NO_CONTEXT, tool, goodRequest());

        assertTrue(result.isError());
        assertTrue(result.error(), result.error().contains("raw markup"));
    }

    @Test
    public void theModelSeesTheRenderedProjectionOfTheValue() {
        ToolSpec spec = ToolSpec.named("reader")
                .tier(Tier.READ)
                .returns(OutputSpec.of(v -> "rendered:" + v.get("text"), "text"))
                .build();
        FakeTool tool = new FakeTool(spec);
        tool.next = ToolResult.okText("hello");
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        ToolResult result = pipeline.run(NO_CONTEXT, tool, ToolRequest.of("read"));

        assertTrue(result.isSuccess());
        assertEquals("rendered:hello", result.result());
        assertEquals("hello", result.value().get("text"));
    }

    // --------------------------------------------------------------- record

    @Test
    public void theCallIsRecordedBeforeItRunsAndTheResultAfter() {
        FakeTool tool = new FakeTool(readSpec());
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());
        List<String> events = new ArrayList<>();
        pipeline.setRecorder(new ToolPipeline.Recorder() {
            @Override
            public void onCall(ToolCall call) {
                events.add("call:" + call.tool() + ":" + tool.runs.get());
            }

            @Override
            public void onResult(ToolCall call, ToolResult result, ApprovalDecision d, long ms) {
                events.add("result:" + (result.isSuccess() ? "ok" : "error") + ":" + d.outcome());
            }
        });

        pipeline.run(NO_CONTEXT, tool, goodRequest());

        assertEquals(2, events.size());
        assertEquals("the call is recorded before the tool runs", "call:reader:0", events.get(0));
        assertTrue(events.get(1), events.get(1).startsWith("result:ok:"));
    }

    @Test
    public void aRefusedCallIsStillRecorded() {
        FakeTool tool = new FakeTool(readSpec());
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval())
                .addGuard(call -> "nope");
        List<ApprovalDecision> decisions = new ArrayList<>();
        pipeline.setRecorder(new ToolPipeline.Recorder() {
            @Override public void onCall(ToolCall call) { }

            @Override
            public void onResult(ToolCall call, ToolResult result, ApprovalDecision d, long ms) {
                decisions.add(d);
            }
        });

        pipeline.run(NO_CONTEXT, tool, goodRequest());

        assertEquals(1, decisions.size());
        assertTrue(decisions.get(0).isDeny());
        assertEquals(ApprovalDecision.Source.GUARD, decisions.get(0).source());
        assertNotNull(decisions.get(0).reason());
    }

    // ------------------------------------------------------- durable replay

    @Test
    public void aCompletedCallIsReplayedWithoutRunningTheToolAgain() {
        FakeTool tool = new FakeTool(readSpec());
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());
        pipeline.setLedger(ledger);

        RunScope.bind(42L, "run-a", ledger);
        ToolResult first;
        try {
            first = pipeline.run(NO_CONTEXT, tool, goodRequest());
        } finally {
            RunScope.clear();
        }
        RunScope.bind(42L, "run-a", ledger);
        ToolResult replay;
        try {
            replay = pipeline.run(NO_CONTEXT, tool, goodRequest());
        } finally {
            RunScope.clear();
        }

        assertTrue(first.isSuccess());
        assertTrue(replay.isSuccess());
        assertEquals(first.result(), replay.result());
        assertEquals("the committed operation must execute once", 1, tool.runs.get());
    }

    @Test
    public void theSameTaskExecutesAgainUnderANewRunId() {
        FakeTool tool = new FakeTool(readSpec());
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());
        pipeline.setLedger(ledger);

        for (String run : new String[]{"run-a", "run-b"}) {
            RunScope.bind(42L, run, ledger);
            try {
                assertTrue(pipeline.run(NO_CONTEXT, tool, goodRequest()).isSuccess());
            } finally {
                RunScope.clear();
            }
        }
        assertEquals(2, tool.runs.get());
    }

    @Test
    public void anInDoubtNonIdempotentEffectIsNotRepeated() {
        ToolSpec spec = ToolSpec.named("writer")
                .tier(Tier.SANDBOX)
                .returns(OutputSpec.of(v -> String.valueOf(v.get("text")), "text"))
                .build();
        FakeTool tool = new FakeTool(spec);
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());
        pipeline.setLedger(ledger);

        RunScope.bind(8L, "run-a", ledger);
        try {
            ExecutionIdentity identity = RunScope.next("writer", "write",
                    java.util.Collections.emptyMap());
            ledger.prepare(identity, "writer", "write", Tier.SANDBOX);
            ledger.markRunning(identity);
        } finally {
            RunScope.clear();
        }

        RunScope.bind(8L, "run-a", ledger);
        ToolResult result;
        try {
            result = pipeline.run(NO_CONTEXT, tool, ToolRequest.of("write"));
        } finally {
            RunScope.clear();
        }
        assertTrue(result.isError());
        assertTrue(result.error(), result.error().contains("unknown outcome"));
        assertEquals(0, tool.runs.get());
    }

    // ---------------------------------------------------------- task scope

    @Test
    public void taskScopeIsPropagatedToTheToolExecutorAndThenCleared() {
        AtomicLong seen = new AtomicLong(-1L);
        FakeTool tool = new FakeTool(readSpec()) {
            @Override
            public ToolResult execute(Context context, ToolRequest request) {
                seen.set(TaskScope.currentTask());
                return super.execute(context, request);
            }
        };
        ToolPipeline pipeline = new ToolPipeline(new ToolPipeline.TierApproval());

        TaskScope.bind(42L);
        try {
            ToolResult result = pipeline.run(NO_CONTEXT, tool, goodRequest());
            assertTrue(result.isSuccess());
            assertEquals(42L, seen.get());
        } finally {
            TaskScope.clear();
        }

        seen.set(-1L);
        ToolResult outside = pipeline.run(NO_CONTEXT, tool, goodRequest());
        assertTrue(outside.isSuccess());
        assertEquals("a reused executor thread must not leak the prior task",
                TaskScope.NO_TASK, seen.get());
    }

    // ----------------------------------------------------------------- misc

    @Test
    public void aDecisionAlwaysCarriesItsSource() {
        ApprovalDecision decision = new ToolPipeline.TierApproval()
                .decide(ToolCall.of("shell", ToolRequest.of("run"), Tier.EXEC));
        assertTrue(decision.needsConfirmation());
        assertEquals(ApprovalDecision.Source.TIER, decision.source());
        assertFalse(decision.reason().isEmpty());
    }

    @Test
    public void readsAreNotInterrogated() {
        ApprovalDecision decision = new ToolPipeline.TierApproval()
                .decide(ToolCall.of("reader", goodRequest(), Tier.READ));
        assertTrue(decision.isAllow());
    }

    @Test
    public void aCallSummaryTruncatesItsArguments() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 500; i++) huge.append('x');
        ToolCall call = ToolCall.of("reader", ToolRequest.of("read", "url", huge.toString()), Tier.READ);
        assertTrue("a summary must stay loggable", call.summary().length() < 120);
    }

    @Test
    public void everyCallGetsItsOwnId() {
        ToolCall a = ToolCall.of("reader", goodRequest(), Tier.READ);
        ToolCall b = ToolCall.of("reader", goodRequest(), Tier.READ);
        assertNotNull(a.id());
        assertFalse(a.id().equals(b.id()));
    }

    @Test
    public void anUnknownToolIsNotAnException() {
        // The engine resolves names; a miss must look like every other failure.
        ToolResult result = ToolResult.fail("no tool named nope");
        assertTrue(result.isError());
        assertNull(result.result());
    }
}
