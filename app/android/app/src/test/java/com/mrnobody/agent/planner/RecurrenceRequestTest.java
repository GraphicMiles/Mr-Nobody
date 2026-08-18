package com.mrnobody.agent.planner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "Keep up on Marvel announcements" was answered once and recommended
 * third-party Twitter monitors. The schedule machinery was idle because
 * this parser did not treat "keep up" as tracking.
 */
public class RecurrenceRequestTest {

    @Test
    public void keepUpOnIsTracking() {
        RecurrenceRequest.Request r = RecurrenceRequest.parse(
                "keep up on any new Marvel announcements on X");
        assertTrue(r.isRecurring());
        assertFalse("no interval was named, so the default is assumed", r.explicit);
    }

    @Test
    public void stayUpdatedIsTracking() {
        assertTrue(RecurrenceRequest.parse("stay updated on the bitcoin price").isRecurring());
    }

    @Test
    public void aOneOffQuestionIsNotScheduled() {
        assertFalse(RecurrenceRequest.parse("what is the bitcoin price").isRecurring());
        assertFalse(RecurrenceRequest.parse("download infinity war from nkiri.ink").isRecurring());
    }

    @Test
    public void anExplicitIntervalStillWins() {
        RecurrenceRequest.Request r = RecurrenceRequest.parse("check the price every day");
        assertTrue(r.isRecurring());
        assertTrue(r.explicit);
    }
}
