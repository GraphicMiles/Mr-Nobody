package com.mrnobody.agent.util;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class HtmlLinksTest {

    @Test
    public void hrefsAndBareUrlsAreFound() {
        String html = "<a href=\"/dl/a.mkv\">a</a>"
                + "<script>var u='https://cdn.example/b.mp4';</script>";
        List<String> links = HtmlLinks.extract(html, "https://site.com/show");
        assertTrue(links.toString(), links.contains("https://site.com/dl/a.mkv"));
        assertTrue(links.toString(), links.contains("https://cdn.example/b.mp4"));
    }
}
