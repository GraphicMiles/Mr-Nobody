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

    @Test
    public void stripsHtmlAndEntitiesFromLiftedStrings() {
        String html = "<script type=\"application/ld+json\">"
                + "{\"articleBody\":\"Politics, Law &amp; Entrepreneurs "
                + "<div> Who is the star of the show? </div>\"}</script>";
        String text = EmbeddedJson.readable(html);
        assertTrue(text, text.contains("Politics, Law & Entrepreneurs"));
        assertFalse(text, text.contains("<div>"));
        assertFalse(text, text.contains("&amp;"));
    }

    @Test
    public void dropsMetadataFieldsLikeDisplayType() {
        String html = "<script type=\"application/ld+json\">"
                + "{\"displayType\":\"standard article\","
                + "\"articleBody\":\"The actual story body is long enough to lift here.\"}</script>";
        String text = EmbeddedJson.readable(html);
        assertFalse("metadata leaked into prose", text.contains("standard article"));
        assertTrue(text, text.contains("actual story body"));
    }
}
