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
    public void articlePrefersTheArticleTag() {
        String html = "<nav>Home About</nav><article><p>"
                + "This is the actual story the user asked to read about today."
                + "</p></article><footer>copyright</footer>";
        String text = HtmlText.article(html);
        assertTrue(text.contains("actual story"));
        assertFalse(text.contains("copyright"));
    }

    @Test
    public void nullAndEmptySafe() {
        assertEquals("", HtmlText.toText(null));
        assertEquals("", HtmlText.toText(""));
    }

    @Test
    public void previewImagePrefersOpenGraph() {
        String html = "<html><head>"
                + "<meta property=\"og:image\" content=\"https://cdn.example/poster.jpg\">"
                + "</head><body><img src=\"/icon.png\"></body></html>";
        assertEquals("https://cdn.example/poster.jpg",
                HtmlText.previewImage(html, "https://example.com/show"));
    }

    @Test
    public void previewImageResolvesRelativeContentImages() {
        String html = "<html><body><img src=\"/media/hero.webp\"></body></html>";
        assertEquals("https://example.com/media/hero.webp",
                HtmlText.previewImage(html, "https://example.com/page"));
    }

    @Test
    public void previewImageSkipsChrome() {
        String html = "<html><body>"
                + "<img src=\"/favicon.ico\">"
                + "<img src=\"https://example.com/pixel.gif\">"
                + "<img src=\"https://example.com/photos/cast.jpg\">"
                + "</body></html>";
        assertEquals("https://example.com/photos/cast.jpg",
                HtmlText.previewImage(html, "https://example.com"));
    }

    @Test
    public void previewImageEmptyWhenNone() {
        assertEquals("", HtmlText.previewImage("<p>no pictures</p>", "https://x"));
        assertEquals("", HtmlText.previewImage(null, "https://x"));
    }
}
