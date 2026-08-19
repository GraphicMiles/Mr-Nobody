package com.mrnobody.agent.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

public class CompletionStatsTest {

    @Before
    public void setUp() {
        CompletionStats.reset();
    }

    @After
    public void tearDown() {
        CompletionStats.reset();
    }

    @Test
    public void anUnattendedRunCountsTowardTheRate() {
        CompletionStats.beginRun();
        CompletionStats.endRun(true);
        assertEquals(1, CompletionStats.finished());
        assertEquals(1, CompletionStats.unattended());
        assertEquals(0, CompletionStats.interrupted());
        assertEquals(1.0, CompletionStats.unattendedRate(), 0.0001);
    }

    @Test
    public void aConfirmDropsTheRate() {
        CompletionStats.beginRun();
        CompletionStats.endRun(true);
        CompletionStats.beginRun();
        CompletionStats.markConfirm();
        CompletionStats.endRun(true);
        assertEquals(2, CompletionStats.finished());
        assertEquals(1, CompletionStats.interrupted());
        assertEquals(0.5, CompletionStats.unattendedRate(), 0.0001);
    }

    @Test
    public void aFailedRunDoesNotCount() {
        CompletionStats.beginRun();
        CompletionStats.endRun(false);
        assertEquals(0, CompletionStats.finished());
        assertTrue(Double.isNaN(CompletionStats.unattendedRate()));
    }

    @Test
    public void snapshotCarriesTheTarget() {
        Map<String, Object> snap = CompletionStats.snapshot();
        assertEquals(0.90, (Double) snap.get("target"), 0.0001);
        assertEquals(0, snap.get("finished"));
    }
}
