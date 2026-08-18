package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A plan that can grow while it runs.
 *
 * <p>The planner used a fixed four-element array, which is why "find the file,
 * then download it" was impossible: the shape of the work was fixed before any
 * of it happened, so nothing a step learned could add a step.
 *
 * <p>The ceiling tests matter as much as the growth ones. A plan that can
 * extend itself can extend itself forever, and on a phone the failure mode is
 * a flat battery rather than a wrong answer.
 */
public class PlanTest {

    private static Plan.Step step(String label) {
        return Plan.Step.internal(label);
    }

    @Test
    public void aPlanRunsItsStepsInOrder() {
        Plan p = Plan.of(step("a"), step("b"));

        assertEquals("a", p.current().label);
        p.advance();
        assertEquals("b", p.current().label);
        p.advance();
        assertTrue(p.isFinished());
        assertNull(p.current());
    }

    @Test
    public void aStepDiscoveredWhileRunningCanBeAdded() {
        Plan p = Plan.of(step("read"));
        assertTrue(p.append(step("download")));

        p.advance();
        assertFalse("the plan grew, so it is not finished", p.isFinished());
        assertEquals("download", p.current().label);
    }

    @Test
    public void aStepCanBeInsertedToRunNext() {
        Plan p = Plan.of(step("a"), step("c"));
        p.insertNext(step("b"));

        p.advance();
        assertEquals("b", p.current().label);
        p.advance();
        assertEquals("c", p.current().label);
    }

    @Test
    public void theCeilingIsEnforced() {
        Plan p = new Plan(null);
        for (int i = 0; i < Plan.MAX_STEPS; i++) {
            assertTrue("step " + i + " should fit", p.append(step("s" + i)));
        }
        assertFalse("past the ceiling it refuses", p.append(step("one too many")));
        assertEquals(Plan.MAX_STEPS, p.size());
    }

    @Test
    public void refusingToGrowIsNotFailing() {
        // A plan that stops growing still finishes with what it has.
        Plan p = new Plan(null);
        for (int i = 0; i < Plan.MAX_STEPS + 5; i++) p.append(step("s" + i));

        assertFalse(p.isAbandoned());
        assertEquals(Plan.MAX_STEPS, p.size());
    }

    @Test
    public void abandoningIsDistinctFromFinishing() {
        Plan p = Plan.of(step("a"), step("b"));
        p.abandon("nothing worked");

        assertTrue(p.isFinished());
        assertTrue(p.isAbandoned());
        assertEquals("nothing worked", p.abandonedReason());
        assertNull("an abandoned plan has no current step", p.current());
        assertFalse("it must not grow after being stopped", p.append(step("c")));
    }

    @Test
    public void progressComesFromPositionNotGuesswork() {
        Plan p = Plan.of(step("a"), step("b"), step("c"), step("d"));
        assertEquals(0, p.progress());
        p.advance();
        assertEquals(25, p.progress());
        p.advance();
        p.advance();
        p.advance();
        assertEquals(100, p.progress());
    }

    @Test
    public void anAbandonedPlanDoesNotReportProgress() {
        Plan p = Plan.of(step("a"), step("b"));
        p.advance();
        p.abandon("stopped");
        assertEquals(0, p.progress());
    }

    @Test
    public void itDescribesWhereItHasGot() {
        Plan p = Plan.of(step("search"), step("read"), step("answer"));
        p.advance();
        String d = p.describe();

        assertTrue(d, d.contains("[read]"));
        assertTrue(d, d.contains("search"));
    }

    @Test
    public void toolStepsKnowTheirTool() {
        Plan.Step s = new Plan.Step("Download", "download", "the user named a file");
        assertTrue(s.isToolStep());
        assertFalse(Plan.Step.internal("Answer").isToolStep());
    }

    @Test
    public void nullsAreIgnoredRatherThanStored() {
        Plan p = Plan.of(step("a"));
        assertFalse(p.append(null));
        assertFalse(p.insertNext(null));
        assertEquals(1, p.size());
    }

    @Test
    public void anEmptyPlanIsFinishedImmediately() {
        Plan p = new Plan(null);
        assertTrue(p.isFinished());
        assertEquals(0, p.progress());
    }
}
