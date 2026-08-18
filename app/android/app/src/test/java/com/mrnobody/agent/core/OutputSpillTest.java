package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Oversized tool output.
 *
 * <p>A page can be a megabyte of text. Inlining that crowds out the
 * instruction and, on a small context window, silently truncates the middle —
 * which is worse than refusing, because the model then answers confidently
 * from half a document without knowing the other half existed.
 */
public class OutputSpillTest {

    private static String text(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append('x');
        return sb.toString();
    }

    @Test
    public void smallOutputIsPassedThroughUntouched() {
        String small = "a short answer";
        OutputSpill.Decision d = OutputSpill.decide(small, "spill://x/1/1");

        assertFalse(d.spill);
        assertEquals(small, d.inline);
    }

    @Test
    public void outputAtTheLimitIsStillInlined() {
        String atLimit = text(OutputSpill.INLINE_LIMIT);
        assertFalse(OutputSpill.shouldSpill(atLimit));
        assertFalse(OutputSpill.decide(atLimit, "spill://x/1/1").spill);
    }

    @Test
    public void oversizedOutputIsSpilled() {
        String big = text(OutputSpill.INLINE_LIMIT + 1);
        assertTrue(OutputSpill.shouldSpill(big));
        assertTrue(OutputSpill.decide(big, "spill://x/1/1").spill);
    }

    @Test
    public void thePreviewSaysHowMuchIsMissingAndWhereItIs() {
        // "Truncated" alone invites the model to answer anyway. Saying how
        // much is missing and where makes the gap actionable.
        String big = text(20_000);
        OutputSpill.Decision d = OutputSpill.decide(big, "spill://http/7/2");

        assertTrue(d.inline, d.inline.contains("more characters not shown"));
        assertTrue(d.inline, d.inline.contains("spill://http/7/2"));
        assertTrue(d.inline, d.inline.contains("PREVIEW"));
        assertEquals(20_000, d.originalLength);
    }

    @Test
    public void thePreviewIsMuchSmallerThanTheOriginal() {
        String big = text(500_000);
        OutputSpill.Decision d = OutputSpill.decide(big, "spill://x/1/1");
        assertTrue("a preview must not itself blow the context",
                d.inline.length() < 2_000);
    }

    @Test
    public void locatorsAreStableAndDistinct() {
        assertEquals(OutputSpill.locatorFor("http", 1, 2),
                OutputSpill.locatorFor("http", 1, 2));
        assertFalse(OutputSpill.locatorFor("http", 1, 2)
                .equals(OutputSpill.locatorFor("http", 1, 3)));
    }

    @Test
    public void nullIsSafe() {
        OutputSpill.Decision d = OutputSpill.decide(null, "spill://x/1/1");
        assertFalse(d.spill);
        assertEquals(0, d.originalLength);
        assertFalse(OutputSpill.shouldSpill(null));
    }
}
