package com.mrnobody.agent.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XTimelineTest {

    @Test
    public void tweetTextNodesBecomeMarkdown() {
        String html = "<div data-testid=\"User-Name\">@Marvel</div>"
                + "<div data-testid=\"tweetText\">New film announced today for fans everywhere</div>";
        String md = XTimeline.toMarkdown(html);
        assertTrue(md, md.contains("### @"));
        assertTrue(md, md.contains("New film announced"));
    }

    @Test
    public void fullTextJsonIsRead() {
        String html = "{\"screen_name\":\"Marvel\",\"full_text\":\"Avengers assemble once more this weekend\"}";
        String md = XTimeline.toMarkdown(html);
        assertTrue(md, md.contains("Avengers assemble"));
        assertTrue(md, md.contains("@Marvel"));
    }

    @Test
    public void emptyHtmlIsEmpty() {
        assertTrue(XTimeline.toMarkdown("").isEmpty());
        assertTrue(XTimeline.toMarkdown("<html><body>no tweets here</body></html>").isEmpty());
        assertFalse(XTimeline.looksLikeTweets("<p>hello</p>"));
    }
}
