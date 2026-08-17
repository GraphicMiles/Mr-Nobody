package com.mrnobody.browser.blocking;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** JVM unit tests for conservative URL tracking-parameter stripping. */
public class TrackingParamsTest {

    @Test
    public void stripsUtmParams() {
        assertEquals("https://example.com/article?id=10",
                TrackingParams.strip("https://example.com/article?id=10&utm_source=x&utm_medium=y"));
    }

    @Test
    public void stripsKnownExactParams() {
        assertEquals("https://example.com/page",
                TrackingParams.strip("https://example.com/page?gclid=abc&fbclid=def"));
    }

    @Test
    public void preservesLegitimateParams() {
        assertEquals("https://example.com/article?id=10&page=2",
                TrackingParams.strip("https://example.com/article?id=10&page=2"));
    }

    @Test
    public void preservesFragment() {
        assertEquals("https://example.com/a#section",
                TrackingParams.strip("https://example.com/a?utm_source=x#section"));
    }

    @Test
    public void noQueryUntouched() {
        assertEquals("https://example.com/plain",
                TrackingParams.strip("https://example.com/plain"));
    }

    @Test
    public void allTrackingParamsRemovedLeavesPath() {
        assertEquals("https://example.com/p",
                TrackingParams.strip("https://example.com/p?gclid=1"));
    }

    @Test
    public void nullAndEmptySafe() {
        assertEquals(null, TrackingParams.strip(null));
        assertEquals("", TrackingParams.strip(""));
    }

    @Test
    public void caseInsensitive() {
        assertEquals("https://example.com/",
                TrackingParams.strip("https://example.com/?UTM_SOURCE=x&Gclid=1"));
    }
}
