package com.mrnobody.agent.policy;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolPipeline;

/**
 * Caps how much one task may do.
 *
 * <p>{@link RepeatCallGuard} stops the same call repeating; it says nothing
 * about a task that makes a hundred <em>different</em> calls. A planner that
 * can extend its own plan can do exactly that, so the ceiling on plan length
 * needs a matching ceiling on work actually performed — otherwise the limit is
 * on the shape of the plan rather than on the battery.
 *
 * <p>Two separate budgets, because they fail differently. A hundred reads is
 * slow; a hundred writes is a phone full of files. Consequential calls get the
 * tighter allowance.
 *
 * <p>Monotonic like every guard: it can only ever refuse. There is no way to
 * express "allow" here, so adding this can never widen what the policy
 * already permitted.
 */
public final class BudgetGuard implements ToolPipeline.Guard {

    /**
     * Total calls of any kind per task. Sized for a real research run
     * (search + named-site prefetch + a handful of reads + one download)
     * with headroom, not for a stuck loop. ~3× a typical finished task.
     */
    public static final int DEFAULT_TOTAL = 80;

    /**
     * Calls that mutate the open web or leave the sandbox, per task.
     * Sandbox downloads do not spend this budget.
     */
    public static final int DEFAULT_CONSEQUENTIAL = 16;

    private final int maxTotal;
    private final int maxConsequential;

    private int total;
    private int consequential;

    public BudgetGuard() {
        this(DEFAULT_TOTAL, DEFAULT_CONSEQUENTIAL);
    }

    public BudgetGuard(int maxTotal, int maxConsequential) {
        this.maxTotal = maxTotal < 1 ? DEFAULT_TOTAL : maxTotal;
        this.maxConsequential = maxConsequential < 1 ? DEFAULT_CONSEQUENTIAL : maxConsequential;
    }

    @Override
    public synchronized String denyReason(ToolCall call) {
        if (call == null) return null;

        // Counted before the verdict: a call that would exceed the budget has
        // still been attempted, and not counting it would let a task sit on
        // the limit issuing refused calls forever.
        total++;
        // SANDBOX (download into the app folder) is not the same risk as
        // clicking submit. Only WRITE and EXEC spend the tight budget.
        boolean changes = call.tier() != null && call.tier().atLeast(Tier.WRITE);
        if (changes) consequential++;

        if (total > maxTotal) {
            return "this task has already made " + maxTotal + " tool calls";
        }
        if (consequential > maxConsequential) {
            return "this task has already made " + maxConsequential
                    + " changes; that is as far as it goes without a new instruction";
        }
        return null;
    }

    /** Start a fresh budget. Called when a task begins. */
    public synchronized void reset() {
        total = 0;
        consequential = 0;
    }

    public synchronized int totalCalls() {
        return total;
    }

    public synchronized int consequentialCalls() {
        return consequential;
    }
}
