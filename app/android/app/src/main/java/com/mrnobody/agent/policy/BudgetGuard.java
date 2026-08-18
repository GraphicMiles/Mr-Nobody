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

    /** Total calls of any kind per task. */
    public static final int DEFAULT_TOTAL = 40;

    /** Calls that change something, per task. */
    public static final int DEFAULT_CONSEQUENTIAL = 8;

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
