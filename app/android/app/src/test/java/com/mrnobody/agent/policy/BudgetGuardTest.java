package com.mrnobody.agent.policy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolRequest;

import org.junit.Test;

/**
 * A ceiling on work actually performed.
 *
 * <p>{@link RepeatCallGuard} stops the same call repeating and says nothing
 * about a task making a hundred different ones. A planner that can extend its
 * own plan can do exactly that, so limiting plan length without limiting work
 * would cap the shape of the plan rather than the battery.
 */
public class BudgetGuardTest {

    private static ToolCall call(String tool, Tier tier, String url) {
        return ToolCall.of(tool, ToolRequest.of("go", "url", url), tier);
    }

    @Test
    public void readsAreAllowedUpToTheTotalBudget() {
        BudgetGuard g = new BudgetGuard(3, 2);

        assertNull(g.denyReason(call("http", Tier.READ, "a")));
        assertNull(g.denyReason(call("http", Tier.READ, "b")));
        assertNull(g.denyReason(call("http", Tier.READ, "c")));

        String denied = g.denyReason(call("http", Tier.READ, "d"));
        assertNotNull("past the total budget it refuses", denied);
        assertTrue(denied, denied.contains("tool calls"));
    }

    @Test
    public void changesGetATighterAllowanceThanReads() {
        // A hundred reads is slow; a hundred writes is a phone full of files.
        BudgetGuard g = new BudgetGuard(50, 2);

        assertNull(g.denyReason(call("download", Tier.WRITE, "a")));
        assertNull(g.denyReason(call("download", Tier.WRITE, "b")));

        String denied = g.denyReason(call("download", Tier.WRITE, "c"));
        assertNotNull("the consequential budget bites first", denied);
        assertTrue(denied, denied.contains("changes"));
    }

    @Test
    public void execCountsAsAChangeToo() {
        BudgetGuard g = new BudgetGuard(50, 1);
        assertNull(g.denyReason(call("terminal", Tier.EXEC, "x")));
        assertNotNull(g.denyReason(call("terminal", Tier.EXEC, "y")));
    }

    @Test
    public void sandboxDownloadsDoNotSpendTheChangeBudget() {
        BudgetGuard g = new BudgetGuard(50, 1);
        assertNull(g.denyReason(call("download", Tier.SANDBOX, "a")));
        assertNull(g.denyReason(call("download", Tier.SANDBOX, "b")));
        assertNull("a sandbox write must not consume the consequential allowance",
                g.denyReason(call("http", Tier.WRITE, "f")));
    }

    @Test
    public void readsDoNotSpendTheChangeBudget() {
        BudgetGuard g = new BudgetGuard(50, 1);
        for (int i = 0; i < 10; i++) g.denyReason(call("http", Tier.READ, "u" + i));

        assertNull("reads should not have consumed the write allowance",
                g.denyReason(call("download", Tier.WRITE, "f")));
    }

    /**
     * A refused call has still been attempted. Not counting it would let a
     * task sit on the limit issuing refused calls indefinitely.
     */
    @Test
    public void refusedCallsStillCount() {
        BudgetGuard g = new BudgetGuard(1, 5);
        g.denyReason(call("http", Tier.READ, "a"));
        assertNotNull(g.denyReason(call("http", Tier.READ, "b")));
        assertTrue(g.totalCalls() >= 2);
    }

    @Test
    public void resetStartsAFreshBudget() {
        BudgetGuard g = new BudgetGuard(1, 1);
        g.denyReason(call("http", Tier.READ, "a"));
        assertNotNull(g.denyReason(call("http", Tier.READ, "b")));

        g.reset();
        assertNull("a new task starts clean", g.denyReason(call("http", Tier.READ, "c")));
    }

    @Test
    public void aNullCallIsIgnored() {
        BudgetGuard g = new BudgetGuard(1, 1);
        assertNull(g.denyReason(null));
        assertTrue(g.totalCalls() == 0);
    }

    @Test
    public void aGuardCanOnlyEverRefuse() {
        // There is no way to express "allow" here, so adding a guard can never
        // widen what the policy already permitted.
        BudgetGuard g = new BudgetGuard();
        assertNull(g.denyReason(call("http", Tier.READ, "a")));
    }
}
