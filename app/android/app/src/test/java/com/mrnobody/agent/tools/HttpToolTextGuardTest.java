package com.mrnobody.agent.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression: http.fetch promised markup-stripped text but shipped raw HTML when
 * the structured extractors failed on a malformed page, which then failed the
 * output contract and the whole read.
 */
public final class HttpToolTextGuardTest {

    @Test
    public void plainTextPassesThroughUntouched() {
        String text = "The 2024 report showed steady growth. Nothing changed in Q3.";
        assertEquals(text, HttpTool.ensureText(text));
    }

    @Test
    public void survivingMarkupIsStrippedMechanically() {
        String raw = "<!DOCTYPE html><html><head><title>T</title>"
                + "<script>var x = 1;</script><style>.a{color:red}</style></head>"
                + "<body><div class=\"main\"><p>Android 16 shipped with a new "
                + "notification system.</p><p>It also improves battery life.</p>"
                + "</div></body></html>";
        String clean = HttpTool.ensureText(raw);
        assertFalse(clean, clean.contains("<"));
        assertFalse(clean, clean.contains("var x"));
        assertFalse(clean, clean.contains("color:red"));
        assertTrue(clean, clean.contains("Android 16 shipped"));
        assertTrue(clean, clean.contains("battery life"));
    }

    @Test
    public void proseMentioningTagsInPassingIsNotMangled() {
        // A page ABOUT html may legally mention a tag or two in its text.
        String text = "The <p> element wraps a paragraph and is the most common block tag.";
        assertEquals(text, HttpTool.ensureText(text));
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertEquals("", HttpTool.ensureText(null));
        assertEquals("", HttpTool.ensureText(""));
    }
}
