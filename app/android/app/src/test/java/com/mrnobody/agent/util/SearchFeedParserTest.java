package com.mrnobody.agent.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class SearchFeedParserTest {

    @Test
    public void parsesAndDeduplicatesRssResults() {
        String xml = "<rss><channel>"
                + "<item><title>ScreenCrush Latest</title>"
                + "<link>https://www.youtube.com/watch?v=one&amp;utm_source=bing</link>"
                + "<description>Newest video &amp; breakdown.</description></item>"
                + "<item><title>Duplicate host</title>"
                + "<link>https://youtube.com/watch?v=two</link><description>x</description></item>"
                + "<item><title>Other source</title>"
                + "<link>https://example.com/story</link><description>Article</description></item>"
                + "</channel></rss>";

        List<SearchResult> results = SearchFeedParser.parse(xml, 6);
        assertEquals(2, results.size());
        assertEquals("ScreenCrush Latest", results.get(0).title);
        assertEquals("https://www.youtube.com/watch?v=one", results.get(0).url);
        assertEquals("Newest video & breakdown.", results.get(0).snippet);
    }
}
