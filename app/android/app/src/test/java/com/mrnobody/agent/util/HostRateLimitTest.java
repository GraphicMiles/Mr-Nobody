package com.mrnobody.agent.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HostRateLimitTest {

    private long now;

    @Before
    public void setUp() {
        now = 1_000_000L;
        HostRateLimit.reset();
        HostRateLimit.configure(new HostRateLimit.Clock() {
            @Override public long now() { return now; }
        }, 3);
    }

    @After
    public void tearDown() {
        HostRateLimit.reset();
    }

    @Test
    public void emptyHostIsAlwaysAllowed() {
        assertTrue(HostRateLimit.tryAcquire(null));
        assertTrue(HostRateLimit.tryAcquire(""));
    }

    @Test
    public void aHostIsPausedAfterTheCeiling() {
        assertTrue(HostRateLimit.tryAcquire("nkiri.ink"));
        assertTrue(HostRateLimit.tryAcquire("nkiri.ink"));
        assertTrue(HostRateLimit.tryAcquire("nkiri.ink"));
        assertFalse(HostRateLimit.tryAcquire("nkiri.ink"));
        assertTrue(HostRateLimit.retryAfterMs("nkiri.ink") > 0);
        assertTrue(HostRateLimit.denyMessage("nkiri.ink").contains("nkiri.ink"));
    }

    @Test
    public void aDifferentHostIsUnaffected() {
        for (int i = 0; i < 3; i++) HostRateLimit.tryAcquire("a.com");
        assertTrue(HostRateLimit.tryAcquire("b.com"));
    }

    @Test
    public void theWindowExpires() {
        for (int i = 0; i < 3; i++) HostRateLimit.tryAcquire("x.com");
        assertFalse(HostRateLimit.tryAcquire("x.com"));
        now += HostRateLimit.WINDOW_MS + 1;
        assertTrue(HostRateLimit.tryAcquire("x.com"));
    }
}
