package com.mrnobody.agent.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HeadlessWebViewEngineTest {

    @Test
    public void blankNavigationsAreIgnored() {
        assertTrue(HeadlessWebViewEngine.isBlankNavigation(null));
        assertTrue(HeadlessWebViewEngine.isBlankNavigation(""));
        assertTrue(HeadlessWebViewEngine.isBlankNavigation("about:blank"));
        assertTrue(HeadlessWebViewEngine.isBlankNavigation("ABOUT:BLANK"));
        assertFalse(HeadlessWebViewEngine.isBlankNavigation("https://example.com/"));
    }

    @Test
    public void extractScriptReadsTheBodyNotTheTitle() {
        assertTrue(HeadlessWebViewEngine.EXTRACT_JS.contains("innerText"));
        assertFalse(HeadlessWebViewEngine.EXTRACT_JS.contains("document.title"));
    }
}
