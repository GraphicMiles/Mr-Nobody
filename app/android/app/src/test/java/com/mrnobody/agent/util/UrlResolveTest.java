package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class UrlResolveTest {

    @Test
    public void absoluteIsUnchanged() {
        assertEquals("https://cdn.example/a.mkv",
                UrlResolve.resolve("https://cdn.example/a.mkv", "https://site.com/p"));
    }

    @Test
    public void protocolRelativeBecomesHttps() {
        assertEquals("https://cdn.example/a.mkv",
                UrlResolve.resolve("//cdn.example/a.mkv", "https://site.com/p"));
    }

    @Test
    public void rootRelativeJoinsTheHost() {
        assertEquals("https://site.com/file.mkv",
                UrlResolve.resolve("/file.mkv", "https://site.com/show/ep1"));
    }

    @Test
    public void relativeJoinsTheDirectory() {
        assertEquals("https://site.com/show/2",
                UrlResolve.resolve("2", "https://site.com/show/1"));
    }

    @Test
    public void emptyIsNull() {
        assertNull(UrlResolve.resolve("", "https://site.com"));
        assertNull(UrlResolve.resolve(null, "https://site.com"));
    }
}
