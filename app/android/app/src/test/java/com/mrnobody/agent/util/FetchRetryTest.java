package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FetchRetryTest {

    @Test
    public void onlyTransientStatusesRetry() {
        assertTrue(FetchRetry.shouldRetry(429));
        assertTrue(FetchRetry.shouldRetry(503));
        assertTrue(FetchRetry.shouldRetry(502));
        assertFalse(FetchRetry.shouldRetry(200));
        assertFalse(FetchRetry.shouldRetry(404));
        assertFalse(FetchRetry.shouldRetry(403));
    }

    @Test
    public void retryAfterSecondsIsHonouredAndCapped() {
        assertEquals(2000L, FetchRetry.delayMs(0, "2"));
        assertEquals(FetchRetry.MAX_WAIT_MS, FetchRetry.delayMs(0, "30"));
    }

    @Test
    public void backoffGrowsThenCaps() {
        long a = FetchRetry.delayMs(0, null);
        long b = FetchRetry.delayMs(1, null);
        assertTrue(b > a);
        assertTrue(FetchRetry.delayMs(8, null) <= FetchRetry.MAX_WAIT_MS);
    }

    @Test
    public void oneRetryThenStop() {
        // Rule 4: a hard failure gets exactly one more chance. The third
        // attempt was cost with no observed benefit on-device.
        assertTrue(FetchRetry.hasAttemptsLeft(0));
        assertFalse(FetchRetry.hasAttemptsLeft(1));
        assertEquals(2, FetchRetry.MAX_ATTEMPTS);
    }
}
