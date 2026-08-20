package com.mrnobody.agent.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SearchToolBudgetTest {

    @Test
    public void remainingNeverGoesNegative() {
        assertEquals(0L, SearchTool.remaining(System.currentTimeMillis() - 1));
    }

    @Test
    public void remainingReflectsAFutureDeadline() {
        long left = SearchTool.remaining(System.currentTimeMillis() + 2_000);
        assertTrue(left > 0 && left <= 2_000);
    }
}
