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

    @Test
    public void theAnnotationCanBeStrippedBackOut() {
        OutputPreview.Decision d = OutputPreview.decide(text(20_000));
        String clean = OutputPreview.stripAnnotation(d.inline);
        assertFalse(clean, clean.contains("characters omitted"));
        assertFalse(clean, clean.contains("NOT retained"));
        assertFalse(clean, clean.contains("before answering"));
        assertTrue(clean, clean.startsWith("xxx"));
    }

    @Test
    public void stripAnnotationLeavesOrdinaryTextAlone() {
        assertEquals("plain prose.", OutputPreview.stripAnnotation("plain prose."));
        assertEquals("", OutputPreview.stripAnnotation(""));
        assertEquals(null, OutputPreview.stripAnnotation(null));
    }

    @Test
    public void previewingIsNotRecordedAsAnError() throws Exception {
        // BUG-6: every big page read logged a non-error to the debug panel.
        String source = new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(
                                "src/main/java/com/mrnobody/agent/core/ToolPipeline.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(source.contains("output previewed"));
        // The genuine contract violation must still be recorded.
        assertTrue(source.contains("broke its output contract"));
    }
}
