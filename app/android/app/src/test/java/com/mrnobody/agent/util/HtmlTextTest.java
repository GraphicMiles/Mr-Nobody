package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM tests for HTML → plain-text extraction (the "never show raw HTML" guard). */
public class HtmlTextTest {

    @Test
    public void stripsTags() {
        String html = "<html><head><title>t</title></head><body><p>Hello <b>world</b></p></body></html>";
        assertTrue(HtmlText.toText(html).contains("Hello"));
        assertTrue(HtmlText.toText(html).contains("world"));
        assertFalse(HtmlText.toText(html).contains("<html>"));
        assertFalse(HtmlText.toText(html).contains("<p>"));
    }

    @Test
    public void dropsScriptAndStyle() {
        String html = "<script>alert(1)</script><style>p{color:red}</style><p>visible</p>";
        String text = HtmlText.toText(html);
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("color"));
        assertTrue(text.contains("visible"));
    }

    @Test
    public void decodesEntities() {
        assertEquals("a & b <c> 'd'",
                HtmlText.toText("a &amp; b &lt;c&gt; &#39;d&apos;"));
    }

    @Test
    public void nullAndEmptySafe() {
        assertEquals("", HtmlText.toText(null));
        assertEquals("", HtmlText.toText(""));
    }
}
