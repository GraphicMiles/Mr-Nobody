package com.mrnobody.agent.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The CFO circuit breaker: refuse a call when the run would exceed its ceiling.
 * The invariant that matters is monotonicity — once a run's spend crosses the
 * cap, no further call may be permitted — and that the check is deterministic.
 */
public class SpendCapTest {

    private static final ModelPricing.Price PRICE = new ModelPricing.Price(1.00, 3.00);

    @Test
    public void aRunUnderCapIsAllowed() {
        SpendCap cap = new SpendCap(1.00, PRICE);
        // ~10k chars ≈ 2700 tokens ≈ $0.0027 — far under $1.
        assertNull(cap.check(TokenUsage.ZERO, 10_000));
    }

    @Test
    public void aRunPastTheCapIsRefused() {
        SpendCap cap = new SpendCap(0.01, PRICE);
        // Already spent $0.02 (2M input tokens at $1/M), next call would add more.
        TokenUsage spent = new TokenUsage(2_000_000, 0);
        String reason = cap.check(spent, 10_000);
        assertNotNull("must refuse", reason);
        assertTrue(reason.contains("cap"));
    }

    @Test
    public void theNextCallEstimatePushesItOver() {
        SpendCap cap = new SpendCap(0.01, PRICE);
        // $0.008 spent, next call estimates $0.005 → over $0.01.
        TokenUsage spent = new TokenUsage(800_000, 0); // $0.008
        assertNotNull(cap.check(spent, 20_000));
    }

    @Test
    public void aZeroOrNegativeCapMeansNoLimit() {
        SpendCap unlimited = new SpendCap(0, PRICE);
        assertEquals(Double.MAX_VALUE, unlimited.capUsd(), 0);
        assertNull(unlimited.check(new TokenUsage(1_000_000, 1_000_000), 1_000_000));
    }

    @Test
    public void estimateCostScalesWithPromptLength() {
        SpendCap cap = new SpendCap(1.00, PRICE);
        assertTrue(cap.estimateCost(100_000) > cap.estimateCost(1_000));
    }

    @Test
    public void theNoLimitCapNeverRefuses() {
        // NO_LIMIT is the sentinel the engine uses when no ceiling is set.
        SpendCap cap = new SpendCap(SpendCap.NO_LIMIT, PRICE);
        assertNull(cap.check(TokenUsage.ZERO, 10_000_000));
    }
}
