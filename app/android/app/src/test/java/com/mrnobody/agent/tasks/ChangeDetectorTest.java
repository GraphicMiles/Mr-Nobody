package com.mrnobody.agent.tasks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The change detector behind monitoring: does a recurring run's new answer
 * differ meaningfully from the last one? A wrong answer here is either noise
 * (spamming "changed" hourly) or silence (never noticing the price moved).
 */
public class ChangeDetectorTest {

    @Test
    public void anIdenticalAnswerIsUnchanged() {
        assertTrue(ChangeDetector.unchanged(
                "Bitcoin is 64282 dollars today.", "Bitcoin is 64282 dollars today."));
    }

    @Test
    public void aDifferentFigureIsChanged() {
        assertFalse(ChangeDetector.unchanged(
                "Bitcoin is 64282 dollars today.", "Bitcoin is 65901 dollars today."));
    }

    @Test
    public void aRewordedSentenceWithTheSameFactsIsUnchanged() {
        // Same words, different order/wrapping: not worth waking the user.
        assertTrue(ChangeDetector.unchanged(
                "Bitcoin is 64282 dollars today, read at 09:00.",
                "Read at 10:00 — Bitcoin is 64282 dollars today."));
    }

    @Test
    public void aTimestampAloneIsNotAChange() {
        assertTrue(ChangeDetector.unchanged(
                "The price is 100 naira. Read at 09:00 on 18 Aug.",
                "The price is 100 naira. Read at 10:00 on 18 Aug."));
    }

    @Test
    public void emptyHandlingIsSafe() {
        assertTrue(ChangeDetector.unchanged(null, null));
        assertTrue(ChangeDetector.unchanged("", ""));
        assertFalse(ChangeDetector.unchanged("", "something new"));
        assertFalse(ChangeDetector.unchanged("something old", ""));
    }
}
