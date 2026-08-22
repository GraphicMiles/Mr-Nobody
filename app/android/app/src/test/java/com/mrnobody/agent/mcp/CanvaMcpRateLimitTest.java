package com.mrnobody.agent.mcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CanvaMcpRateLimitTest {
    @Test public void mutationLimitMatchesPublishedTwentyPerMinute() {
        CanvaMcpRateLimit.resetForTest();
        for (int i = 0; i < 20; i++) assertTrue(CanvaMcpRateLimit.tryAcquire("export-design"));
        assertFalse(CanvaMcpRateLimit.tryAcquire("export-design"));
    }

    @Test public void editOperationsUsePublishedFiftyPerMinute() {
        assertTrue(CanvaMcpRateLimit.limit("perform-editing-operations") == 50);
        assertTrue(CanvaMcpRateLimit.limit("search-designs") == 100);
    }
}
