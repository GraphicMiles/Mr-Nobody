package com.mrnobody.agent.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmbeddedJsonTest {

    @Test
    public void liftsProseFromNextData() {
        String html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"title\":\"A long enough article title here\","
                + "\"url\":\"https://example.com/x\"}}}</script>";
        String text = EmbeddedJson.readable(html);
        assertTrue(text, text.contains("long enough article title"));
        assertFalse(text, text.contains("https://example.com"));
    }

    @Test
    public void liftsJsonLd() {
        String html = "<script type=\"application/ld+json\">"
                + "{\"@type\":\"NewsArticle\",\"headline\":\"Studio announces a new film today\"}"
                + "</script>";
        assertTrue(EmbeddedJson.readable(html).contains("Studio announces"));
    }
}
