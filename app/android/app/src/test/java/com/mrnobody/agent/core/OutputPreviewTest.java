package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OutputPreviewTest {

    private static String text(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append('x');
        return sb.toString();
    }

    @Test
    public void smallOutputIsPassedThroughUntouched() {
        String small = "a short answer";
        OutputPreview.Decision d = OutputPreview.decide(small);

        assertFalse(d.truncated);
        assertEquals(small, d.inline);
    }

    @Test
    public void outputAtTheLimitIsStillInlined() {
        String atLimit = text(OutputPreview.INLINE_LIMIT);
        assertFalse(OutputPreview.shouldTruncate(atLimit));
        assertFalse(OutputPreview.decide(atLimit).truncated);
    }

    @Test
    public void oversizedOutputIsAnHonestNonRetrievablePreview() {
        String big = text(20_000);
        OutputPreview.Decision d = OutputPreview.decide(big);

        assertTrue(d.truncated);
        assertTrue(d.inline, d.inline.contains("characters omitted"));
        assertTrue(d.inline, d.inline.contains("NOT retained"));
        assertTrue(d.inline, d.inline.contains("cannot be retrieved"));
        assertFalse(d.inline, d.inline.contains("spill://"));
        assertEquals(20_000, d.originalLength);
    }

    @Test
    public void thePreviewIsMuchSmallerThanTheOriginal() {
        OutputPreview.Decision d = OutputPreview.decide(text(500_000));
        assertTrue(d.inline.length() < 2_000);
    }

    @Test
    public void nullIsSafe() {
        OutputPreview.Decision d = OutputPreview.decide(null);
        assertFalse(d.truncated);
        assertEquals(0, d.originalLength);
        assertFalse(OutputPreview.shouldTruncate(null));
    }
}
