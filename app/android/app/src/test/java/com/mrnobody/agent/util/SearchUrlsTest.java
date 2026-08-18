package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class SearchUrlsTest {

    @Test
    public void aGenericHostGetsWordpressShapes() {
        List<String> urls = SearchUrls.forHost("nkiri.ink", "Infinity War");
        assertTrue(urls.toString(), urls.contains("https://nkiri.ink/?s=Infinity%20War"));
        assertTrue(urls.toString(), urls.contains("https://nkiri.ink/search/Infinity%20War"));
        assertTrue(urls.toString(), urls.contains("https://nkiri.ink/?q=Infinity%20War"));
    }

    @Test
    public void xUsesItsPublicSearchPath() {
        List<String> urls = SearchUrls.forHost("x.com", "Marvel");
        assertTrue(urls.get(0).startsWith("https://x.com/search?q=Marvel"));
    }

    @Test
    public void noQueryIsJustTheHomepage() {
        assertEquals("https://example.com/",
                SearchUrls.forHost("example.com", "").get(0));
    }
}
