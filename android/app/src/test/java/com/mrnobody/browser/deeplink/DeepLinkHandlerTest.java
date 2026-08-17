package com.mrnobody.browser.deeplink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM tests for deep-link parsing (pure Java — java.net.URI only). */
public class DeepLinkHandlerTest {

    @Test
    public void parsesSearch() {
        DeepLinkHandler d = DeepLinkHandler.parse("mrnobody://search?q=latest+news");
        assertEquals(DeepLinkHandler.Action.SEARCH, d.action);
        assertEquals("latest news", d.arg);
    }

    @Test
    public void parsesTask() {
        DeepLinkHandler d = DeepLinkHandler.parse("mrnobody://task?instruction=find+laptops+under+500000");
        assertEquals(DeepLinkHandler.Action.TASK, d.action);
        assertEquals("find laptops under 500000", d.arg);
    }

    @Test
    public void parsesOpenWithUrl() {
        DeepLinkHandler d = DeepLinkHandler.parse("mrnobody://open?url=https://example.com");
        assertEquals(DeepLinkHandler.Action.OPEN, d.action);
        assertEquals("https://example.com", d.arg);
    }

    @Test
    public void parsesSimpleDestinations() {
        assertEquals(DeepLinkHandler.Action.SETTINGS, DeepLinkHandler.parse("mrnobody://settings").action);
        assertEquals(DeepLinkHandler.Action.PRIVACY, DeepLinkHandler.parse("mrnobody://privacy").action);
        assertEquals(DeepLinkHandler.Action.TABS, DeepLinkHandler.parse("mrnobody://tabs").action);
        assertEquals(DeepLinkHandler.Action.TASKS, DeepLinkHandler.parse("mrnobody://tasks").action);
        assertEquals(DeepLinkHandler.Action.DOWNLOADS, DeepLinkHandler.parse("mrnobody://downloads").action);
    }

    @Test
    public void webUrlsAreNotDeepLinks() {
        assertTrue(DeepLinkHandler.isWebUrl("https://example.com"));
        assertTrue(DeepLinkHandler.isWebUrl("http://example.com"));
        assertFalse(DeepLinkHandler.isWebUrl("mrnobody://search?q=x"));
        assertEquals(DeepLinkHandler.Action.NONE, DeepLinkHandler.parse("https://example.com").action);
    }

    @Test
    public void unknownOrMalformedIsNone() {
        assertEquals(DeepLinkHandler.Action.NONE, DeepLinkHandler.parse("mrnobody://bogus").action);
        assertEquals(DeepLinkHandler.Action.NONE, DeepLinkHandler.parse("ftp://x").action);
        assertEquals(DeepLinkHandler.Action.NONE, DeepLinkHandler.parse("").action);
        assertEquals(DeepLinkHandler.Action.NONE, DeepLinkHandler.parse(null).action);
        assertEquals(DeepLinkHandler.Action.NONE, DeepLinkHandler.parse("not a uri").action);
    }
}
