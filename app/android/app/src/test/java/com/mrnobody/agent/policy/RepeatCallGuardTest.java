package com.mrnobody.agent.policy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolRequest;

import org.junit.Test;

/**
 * The loop breaker.
 *
 * <p>A planner that cannot make progress does not stop on its own: it reissues
 * the same fetch, gets the same failure, and reissues it. On a phone that is
 * someone's battery and data allowance.
 */
public class RepeatCallGuardTest {

    private static ToolCall get(String url) {
        return ToolCall.of("http", ToolRequest.of("fetch", "url", url), Tier.READ);
    }

    @Test
    public void identicalCallsAreAllowedUpToTheLimitThenRefused() {
        RepeatCallGuard guard = new RepeatCallGuard(3);
        ToolCall same = get("https://example.test/a");

        assertNull(guard.denyReason(same));
        assertNull(guard.denyReason(same));
        assertNull(guard.denyReason(same));

        String denied = guard.denyReason(same);
        assertNotNull("the fourth identical call should be refused", denied);
        assertTrue(denied, denied.contains("already run"));
    }

    @Test
    public void adifferentArgumentIsProgressNotARepeat() {
        RepeatCallGuard guard = new RepeatCallGuard(2);
        guard.denyReason(get("https://example.test/a"));
        guard.denyReason(get("https://example.test/a"));
        guard.denyReason(get("https://example.test/a"));

        // Same tool, different URL: the agent is getting somewhere.
        assertNull(guard.denyReason(get("https://example.test/b")));
    }

    @Test
    public void adifferentToolIsNotARepeat() {
        RepeatCallGuard guard = new RepeatCallGuard(1);
        ToolCall http = get("https://example.test/a");
        guard.denyReason(http);
        guard.denyReason(http);

        assertNull(guard.denyReason(
                ToolCall.of("search", ToolRequest.of("search", "q", "x"), Tier.READ)));
    }

    @Test
    public void resetForgetsEverything() {
        RepeatCallGuard guard = new RepeatCallGuard(1);
        ToolCall same = get("https://example.test/a");
        guard.denyReason(same);
        assertNotNull(guard.denyReason(same));

        guard.reset();
        assertNull("a new task starts clean", guard.denyReason(same));
    }

    @Test
    public void itCountsHowManyTimesACallWasSeen() {
        RepeatCallGuard guard = new RepeatCallGuard(5);
        ToolCall same = get("https://example.test/a");
        guard.denyReason(same);
        guard.denyReason(same);

        assertTrue(guard.timesSeen(same) == 2);
    }

    @Test
    public void aNullCallIsIgnoredRatherThanCounted() {
        RepeatCallGuard guard = new RepeatCallGuard(1);
        assertNull(guard.denyReason(null));
        assertNull(guard.denyReason(null));
    }

    /**
     * A guard can only ever subtract permission. There is deliberately no way
     * to express "allow" here, so a new guard can never widen the policy.
     */
    @Test
    public void aGuardOnlyEverDeniesOrAbstains() {
        RepeatCallGuard guard = new RepeatCallGuard(1);
        String first = guard.denyReason(get("https://example.test/a"));
        assertNull("abstaining is null, not an approval", first);
    }
}
