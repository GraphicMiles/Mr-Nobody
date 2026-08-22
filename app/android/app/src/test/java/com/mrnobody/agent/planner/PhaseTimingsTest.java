package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/** The per-run phase accumulator: names, merge-on-repeat, and the string form. */
public class PhaseTimingsTest {

    @Test
    public void recordsNamedPhasesInOrder() {
        PhaseTimings t = new PhaseTimings();
        t.begin("plan");
        t.end();
        t.begin("tool");
        t.end();
        t.begin("verify");
        t.end();

        Map<String, Long> snap = t.snapshotMs();
        assertEquals(3, snap.size());
        assertNotNull(snap.get("plan"));
        assertNotNull(snap.get("tool"));
        assertNotNull(snap.get("verify"));
        assertTrue("durations are non-negative", snap.get("plan") >= 0);
    }

    @Test
    public void beginningANewPhaseClosesThePreviousOne() {
        PhaseTimings t = new PhaseTimings();
        t.begin("plan");
        t.begin("tool");   // implicitly closes "plan"
        t.end();

        Map<String, Long> snap = t.snapshotMs();
        assertTrue("plan and tool both captured", snap.containsKey("plan"));
        assertTrue(snap.containsKey("tool"));
    }

    @Test
    public void repeatedSamePhaseMergesIntoSingleEntry() {
        PhaseTimings t = new PhaseTimings();
        for (int i = 0; i < 9; i++) {
            t.begin("plan");
            t.end();
        }
        t.begin("tool");
        t.end();

        Map<String, Long> snap = t.snapshotMs();
        assertEquals("nine planning calls read as one phase", 2, snap.size());
        assertNotNull(snap.get("plan"));
    }

    @Test
    public void totalAndDescribeAreSummarised() {
        PhaseTimings t = new PhaseTimings();
        t.begin("plan");
        t.end();
        t.begin("tool");
        t.end();

        assertTrue("total is non-negative", t.totalMs() >= 0);
        String describe = t.describe();
        assertTrue(describe, describe.contains("plan="));
        assertTrue(describe, describe.contains("tool="));
        assertTrue(describe, describe.contains("total="));
    }

    @Test
    public void emptyTimingsDescribeAsZero() {
        PhaseTimings t = new PhaseTimings();
        String describe = t.describe();
        assertTrue(describe, describe.contains("total="));
    }
}
