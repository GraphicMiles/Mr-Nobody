package com.mrnobody.agent.core;

import android.content.Context;

import com.mrnobody.agent.execution.ExecutionIdentity;
import com.mrnobody.agent.execution.ExecutionLedger;
import com.mrnobody.agent.execution.RunScope;
import com.mrnobody.agent.resilience.FailureClassifier;
import com.mrnobody.agent.resilience.OperationFailure;
import com.mrnobody.agent.resilience.RetryPolicy;
import com.mrnobody.debug.ErrorLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one place a tool runs.
 *
 * <p>Every guarantee the agent offers is enforced here, in this order:
 *
 * <pre>
 *   record the call        user-facing audit before execution
 *   validate parameters    against the tool's declared spec
 *   prepare/replay ledger  durable identity; return a committed result if present
 *   approval               ALLOW / CONFIRM / DENY, with a source and a reason
 *   guards                 may DENY or abstain — never grant
 *   confirmation           and if nobody can answer, DENY
 *   execute with a timeout
 *   normalise throws       into a failed result, never a crashed loop
 *   validate the output    against the tool's declared shape
 *   render                 the model-facing projection
 *   record the result
 * </pre>
 *
 * <p>Two properties are deliberate. <b>Guards are monotonic:</b> a guard can
 * only narrow permission, so adding one can never widen the surface. And
 * <b>confirmation fails closed:</b> a task woken by WorkManager with the screen
 * off has no one to ask, and "no answer" must mean "did not run" — never
 * "ran anyway".
 */
public final class ToolPipeline {

    /** Somewhere to put the record of a call. The task event log implements this later. */
    public interface Recorder {
        void onCall(ToolCall call);

        void onResult(ToolCall call, ToolResult result, ApprovalDecision decision, long durationMs);

        Recorder NONE = new Recorder() {
            @Override public void onCall(ToolCall call) { }
            @Override public void onResult(ToolCall c, ToolResult r, ApprovalDecision d, long ms) { }
        };
    }

    /**
     * A monotonic check. Two outcomes only, by construction: a guard says DENY
     * or says nothing. There is no way to express "allow" here, which is the
     * point — a new guard can never grant a permission the policy withheld.
     */
    public interface Guard {
        /** @return a reason to deny, or null to abstain. */
        String denyReason(ToolCall call);
    }

    /** Resolves the permission for a call. Replaced by the tiered policy in the next stage. */
    public interface Approval {
        ApprovalDecision decide(ToolCall call);
    }

    /**
     * Asks a human. The call does not run unless the answer is ALLOWED.
     * UNAVAILABLE (no UI, timeout) is not a denial — the task parks.
     */
    public interface Confirmer {
        Confirmation confirm(ToolCall call, String reason);
    }

    /**
     * Tool bodies run here. Bounded so a run of tools that ignore interruption
     * (a WebView callback that never fires, a non-interruptible read) cannot
     * grow the pool without limit. A {@code SynchronousQueue} hands a task to an
     * idle thread or spawns one up to the cap; above the cap the task runs on
     * the caller. The caller is never a tool-exec thread — it is the worker
     * awaiting the result — so caller-runs can never deadlock the pool.
     */
    private static final int MAX_TOOL_THREADS = 16;
    private static final ExecutorService EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            4, MAX_TOOL_THREADS, 60L, TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "tool-exec");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    private final Approval approval;
    private final List<Guard> guards = new ArrayList<>();
    private Confirmer confirmer;      // null until a UI is attached — deny until then
    private Recorder recorder = Recorder.NONE;
    private ExecutionLedger ledger = ExecutionLedger.NONE;

    public ToolPipeline(Approval approval) {
        this.approval = approval == null ? new TierApproval() : approval;
    }

    public ToolPipeline addGuard(Guard guard) {
        if (guard != null) guards.add(guard);
        return this;
    }

    /** Attach the thing that can ask the user. Detach by passing null. */
    public void setConfirmer(Confirmer confirmer) {
        this.confirmer = confirmer;
    }

    public void setRecorder(Recorder recorder) {
        this.recorder = recorder == null ? Recorder.NONE : recorder;
    }

    /** Attach the durable execution authority used for replay and idempotency. */
    public void setLedger(ExecutionLedger ledger) {
        this.ledger = ledger == null ? ExecutionLedger.NONE : ledger;
    }

    /** Run a tool call through the whole pipeline. Never throws. */
    public ToolResult run(Context context, Tool tool, ToolRequest request, Cancellation cancellation) {
        ToolSpec spec = tool.spec();
        Tier tier = tool.tierFor(request);
        ExecutionIdentity execution = RunScope.next(spec.name(), request.action(), request.params());
        ToolCall call = ToolCall.of(spec.name(), request, tier, execution);
        recorder.onCall(call);
        long startedAt = System.currentTimeMillis();

        ApprovalDecision decision = ApprovalDecision.allow(ApprovalDecision.Source.DEFAULT, "");
        ToolResult result;
        ExecutionLedger.Entry entry = null;
        try {
            String invalid = spec.validate(request);
            if (invalid != null) {
                result = ToolResult.fail(spec.name() + ": " + invalid);
                return finish(call, result, decision, startedAt);
            }

            if (execution.isDurable() && ledger != ExecutionLedger.NONE) {
                entry = ledger.prepare(execution, spec.name(), request.action(), tier);
                if (entry == null && tier.atLeast(Tier.SANDBOX)) {
                    result = ToolResult.fail("Execution ledger unavailable; "
                            + spec.name() + " was not run.");
                    return finish(call, result, decision, startedAt);
                }
                ToolResult prior = priorOutcome(context, tool, request, spec,
                        execution, entry, tier);
                if (prior != null) {
                    return finish(call, prior, decision, startedAt);
                }
            }

            decision = approval.decide(call);

            // Guards run after the policy and can only take permission away.
            for (Guard guard : guards) {
                String denied = guard.denyReason(call);
                if (denied != null) {
                    decision = ApprovalDecision.deny(ApprovalDecision.Source.GUARD, denied);
                    break;
                }
            }

            if (decision.isDeny()) {
                result = ToolResult.fail("Refused: " + reasonOr(decision, "not permitted"));
                commit(execution, result, false);
                return finish(call, result, decision, startedAt);
            }

            if (decision.needsConfirmation()) {
                Confirmer asker = confirmer;
                String why = reasonOr(decision, "this action needs your approval");
                Confirmation answer = asker == null
                        ? Confirmation.UNAVAILABLE
                        : asker.confirm(call, why);
                if (answer == null) answer = Confirmation.UNAVAILABLE;
                if (answer == Confirmation.UNAVAILABLE) {
                    // Fail-closed on the call; the task parks rather than dies.
                    result = ToolResult.needsApproval(call.tool(),
                            "Needs your approval: " + call.summary()
                                    + " — open Mr Nobody to allow it.");
                    if (execution.isDurable()) ledger.markWaiting(execution, result);
                    return finish(call, ApprovalDecision.deny(decision.source(),
                            "no one available to confirm"), result, startedAt);
                }
                if (answer == Confirmation.DENIED) {
                    result = ToolResult.fail("Declined: " + call.summary());
                    commit(execution, result, false);
                    return finish(call, ApprovalDecision.deny(ApprovalDecision.Source.USER_OVERRIDE,
                            "declined by the user"), result, startedAt);
                }
            }

            if (execution.isDurable() && ledger != ExecutionLedger.NONE) {
                ledger.markRunning(execution);
            }
            ToolExecution attempted = null;
            for (int attempt = 0; attempt < RetryPolicy.MAX_ATTEMPTS; attempt++) {
                attempted = execute(context, tool, request, spec, cancellation, execution);
                result = attempted.result;
                OperationFailure failure = result == null ? null : result.failure();
                if (failure == null && result != null && result.isError()) {
                    failure = FailureClassifier.fromMessage(result.error());
                }
                if (!RetryPolicy.shouldRetry(failure, attempt, tier,
                        tool.supportsIdempotency(request))) break;
                if (!waitForRetry(RetryPolicy.delayMs(failure, attempt), cancellation)) break;
            }
            result = attempted == null ? ToolResult.fail("tool did not run") : attempted.result;
            commit(execution, result, attempted != null && attempted.ambiguous);
        } catch (Throwable t) {
            // A tool that throws is a failed call, not a dead agent. Once a
            // consequential call entered RUNNING, a throw is an unknown
            // outcome and must not be blindly repeated.
            ErrorLog.record("tool " + spec.name() + " threw: " + t);
            result = ToolResult.fail(spec.name() + " failed: " + describe(t));
            commit(execution, result, tier.atLeast(Tier.SANDBOX));
        }
        return finish(call, result, decision, startedAt);
    }

    public ToolResult run(Context context, Tool tool, ToolRequest request) {
        return run(context, tool, request, Cancellation.NONE);
    }

    /** Return a committed outcome, refuse an unsafe replay, or null to execute. */
    private ToolResult priorOutcome(Context context, Tool tool, ToolRequest request,
                                    ToolSpec spec, ExecutionIdentity execution,
                                    ExecutionLedger.Entry entry, Tier tier) {
        if (entry == null) return null;
        if (entry.state == ExecutionLedger.State.SUCCEEDED && entry.result != null) {
            return entry.result;
        }
        if (entry.state == ExecutionLedger.State.FAILED) {
            if (tier == Tier.READ || tool.supportsIdempotency(request)) return null;
            return entry.result == null
                    ? ToolResult.fail("The prior call failed and was not repeated.")
                    : entry.result;
        }
        if (entry.state != ExecutionLedger.State.RUNNING
                && entry.state != ExecutionLedger.State.UNKNOWN) {
            // PREPARED means no effect began; WAITING means approval parked it.
            return null;
        }

        try {
            ToolResult reconciled = tool.reconcile(context, request, execution);
            if (reconciled != null) {
                ToolResult checked = validate(spec, reconciled);
                ledger.complete(execution, checked);
                return checked;
            }
        } catch (Throwable t) {
            ErrorLog.record("tool " + spec.name() + " reconciliation failed: " + t);
        }

        if (tier == Tier.READ || tool.supportsIdempotency(request)) {
            // Reads are harmless to repeat. An idempotent effect must receive
            // the exact same key through Tool.execute(..., execution).
            return null;
        }

        String message = "A previous " + spec.name()
                + " attempt has an unknown outcome; it was not repeated.";
        ledger.markUnknown(execution, message);
        return ToolResult.fail(message);
    }

    private void commit(ExecutionIdentity execution, ToolResult result, boolean ambiguous) {
        if (execution == null || !execution.isDurable() || ledger == ExecutionLedger.NONE) return;
        if (result != null && result.needsApproval()) {
            ledger.markWaiting(execution, result);
        } else if (ambiguous) {
            ledger.markUnknown(execution,
                    result == null || result.error() == null
                            ? "outcome unknown" : result.error());
        } else {
            ledger.complete(execution, result);
        }
    }

    // ------------------------------------------------------------ execution

    private ToolExecution execute(Context context, Tool tool, ToolRequest request,
                                  ToolSpec spec, Cancellation cancellation,
                                  ExecutionIdentity execution) {
        // Tool bodies run on pooled threads. Capture and explicitly propagate
        // the task identity; a plain ThreadLocal set by WorkManager is absent
        // on a reused executor thread, which previously made task-scoped
        // browser suppliers resolve to null.
        long taskId = TaskScope.currentTask();
        AgentRunContext run = AgentRunContext.current();
        Future<ToolResult> future = EXECUTOR.submit(
                () -> TaskScope.callAs(taskId,
                        () -> AgentRunContext.callAs(run,
                                () -> tool.execute(context, request,
                                        cancellation == null ? Cancellation.NONE : cancellation,
                                        execution))));
        long deadline = System.currentTimeMillis() + spec.timeoutMs();
        try {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    // Poll rather than block for the whole budget, so a cancel
                    // request is noticed while a slow tool is still running.
                    return new ToolExecution(validate(spec,
                            future.get(Math.min(remaining, 250), TimeUnit.MILLISECONDS)), false);
                } catch (TimeoutException waiting) {
                    if (cancellation != null && cancellation.isCancelled()) {
                        future.cancel(true);
                        return new ToolExecution(
                                ToolResult.fail(spec.name() + " cancelled"), true);
                    }
                }
            }
            future.cancel(true);
            return new ToolExecution(ToolResult.fail(
                    spec.name() + " timed out after " + spec.timeoutMs() + "ms"), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new ToolExecution(ToolResult.fail(spec.name() + " interrupted"), true);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            ErrorLog.record("tool " + spec.name() + " threw: " + cause);
            return new ToolExecution(ToolResult.fail(
                    spec.name() + " failed: " + describe(cause)), true);
        }
    }

    private static boolean waitForRetry(long delayMs, Cancellation cancellation) {
        long deadline = System.currentTimeMillis() + Math.max(0L, delayMs);
        while (System.currentTimeMillis() < deadline) {
            if (cancellation != null && cancellation.isCancelled()) return false;
            try {
                Thread.sleep(Math.min(100L, Math.max(1L,
                        deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static final class ToolExecution {
        final ToolResult result;
        final boolean ambiguous;

        ToolExecution(ToolResult result, boolean ambiguous) {
            this.result = result;
            this.ambiguous = ambiguous;
        }
    }

    /** The tool's value has to satisfy the shape the tool itself declared. */
    private ToolResult validate(ToolSpec spec, ToolResult result) {
        if (result == null) return ToolResult.fail(spec.name() + " returned nothing");
        if (result.isError()) return result;
        OutputSpec output = spec.output();
        if (output == null) return result;
        String invalid = output.validate(result.value());
        if (invalid != null) {
            ErrorLog.record("tool " + spec.name() + " broke its output contract: " + invalid);
            return ToolResult.fail(spec.name() + " returned an unusable result: " + invalid);
        }
        String rendered = output.render(result.value());

        // Oversized output is previewed, not inlined. A megabyte of page text
        // crowds out the instruction and, on a small context window, silently
        // truncates the middle -- which is worse than refusing, because the
        // model then answers confidently from half a document without knowing
        // the other half existed. Previewing is normal, expected behaviour,
        // so it is NOT recorded to ErrorLog (an earlier build flooded the debug
        // panel with a non-error on every large page read).
        if (OutputPreview.shouldTruncate(rendered)) {
            OutputPreview.Decision decision = OutputPreview.decide(rendered);
            return result.renderedAs(decision.inline);
        }

        return result.renderedAs(rendered);
    }

    private ToolResult finish(ToolCall call, ToolResult result, ApprovalDecision decision, long startedAt) {
        recorder.onResult(call, result, decision, System.currentTimeMillis() - startedAt);
        return result;
    }

    private ToolResult finish(ToolCall call, ApprovalDecision decision, ToolResult result, long startedAt) {
        return finish(call, result, decision, startedAt);
    }

    private static String reasonOr(ApprovalDecision decision, String fallback) {
        return decision.reason().isEmpty() ? fallback : decision.reason();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isEmpty() ? t.getClass().getSimpleName() : message;
    }

    /**
     * The interim policy: read and write run, anything that acts off-device
     * asks. The next stage replaces this with the user's approval mode and
     * per-tool overrides — this class stays the seam.
     */
    public static final class TierApproval implements Approval {
        @Override
        public ApprovalDecision decide(ToolCall call) {
            switch (call.tier()) {
                case EXEC:
                    return ApprovalDecision.confirm(ApprovalDecision.Source.TIER,
                            "runs a command or acts outside this device");
                case WRITE:
                case READ:
                default:
                    return ApprovalDecision.allow(ApprovalDecision.Source.TIER, "");
            }
        }
    }

    /** Convenience for tests and for the debug overlay. */
    public Map<String, Object> describeGuards() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("guards", guards.size());
        out.put("confirmer", confirmer != null);
        return out;
    }
}
