package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PageKindTest {

    @Test
    public void aChallengeIsNotARead() {
        PageKind.Kind k = PageKind.classify(
                "<html><title>Just a moment...</title><p>Checking your browser</p>");
        assertEquals(PageKind.Kind.CHALLENGE, k);
        assertTrue(k.needsBrowser());
    }

    @Test
    public void nextDataIsEmbeddedJson() {
        String html = "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"title\":\"Hello there friend\"}}</script>"
                + "<div id=\"root\"></div></html>";
        assertEquals(PageKind.Kind.EMBEDDED_JSON, PageKind.classify(html));
        assertFalse(PageKind.classify(html).needsBrowser());
    }

    @Test
    public void aFeedIsAFeed() {
        assertEquals(PageKind.Kind.FEED, PageKind.classify(
                "<?xml version=\"1.0\"?><rss><channel><item><title>a</title></item></channel></rss>"));
    }

    @Test
    public void aRealArticleIsStatic() {
        String html = "<html><body><article><p>" + "word ".repeat(40) + "</p></article></body></html>";
        assertEquals(PageKind.Kind.STATIC, PageKind.classify(html));
        assertEquals(FetchLadder.Step.HTTP, FetchLadder.afterHttp(PageKind.Kind.STATIC));
    }

    @Test
    public void anEmptyReactRootIsSpa() {
        String html = "<html><body><div id=\"root\"></div>"
                + "<script src=\"/app.js\"></script></body></html>";
        assertEquals(PageKind.Kind.SPA, PageKind.classify(html));
        assertEquals(FetchLadder.Step.BROWSER, FetchLadder.afterHttp(PageKind.Kind.SPA));
    }
}
