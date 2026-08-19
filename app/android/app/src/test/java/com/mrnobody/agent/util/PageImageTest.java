package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PageImageTest {

    @Test
    public void extensionFollowsTheUrl() {
        assertEquals(".png", PageImage.extensionOf("https://x/a.png"));
        assertEquals(".webp", PageImage.extensionOf("https://x/a.webp?w=400"));
        assertEquals(".jpg", PageImage.extensionOf("https://x/a"));
    }

    @Test
    public void downloadWithoutContextIsEmpty() {
        assertEquals("", PageImage.download(null, "https://cdn.example/a.jpg"));
        assertEquals("", PageImage.download(null, ""));
    }

    @Test
    public void downloadRejectsNonImages() {
        assertTrue(PageImage.download(null, "not-a-url").isEmpty());
        assertTrue(PageImage.download(null, "data:image/png;base64,xx").isEmpty());
    }
}
