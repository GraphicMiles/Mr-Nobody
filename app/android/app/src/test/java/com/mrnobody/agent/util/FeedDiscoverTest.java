package com.mrnobody.agent.util;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class FeedDiscoverTest {

    @Test
    public void conventionalPathsAreOffered() {
        List<String> urls = FeedDiscover.candidates("blog.example.com", null);
        assertTrue(urls.contains("https://blog.example.com/feed"));
        assertTrue(urls.contains("https://blog.example.com/atom.xml"));
    }

    @Test
    public void alternateLinksArePickedFromHtml() {
        String html = "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"/news.xml\">";
        List<String> urls = FeedDiscover.candidates("site.com", html);
        assertTrue(urls.toString(), urls.contains("https://site.com/news.xml"));
    }

    @Test
    public void itemsBecomeReadableLines() {
        String xml = "<rss><channel>"
                + "<item><title>One</title><link>https://s.com/1</link></item>"
                + "<item><title>Two</title><link>https://s.com/2</link></item>"
                + "</channel></rss>";
        String text = FeedDiscover.toText(xml);
        assertTrue(text, text.contains("One"));
        assertTrue(text, text.contains("https://s.com/2"));
    }
}
