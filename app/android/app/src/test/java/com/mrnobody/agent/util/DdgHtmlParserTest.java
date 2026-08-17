package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** JVM tests for the DuckDuckGo results parser. */
public class DdgHtmlParserTest {

    private static final String SAMPLE =
            "<html><body>"
            + "<div class=\"region\">Argentina</div><div class=\"region\">Australia</div>"
            + "<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&rut=x\">"
            + "Example <b>Title</b></a>"
            + "<a class=\"result__snippet\" href=\"#\">A short snippet about the result.</a>"
            + "<a class=\"result__url\" href=\"#\">example.com</a>"
            + "<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fsecond.com%2F\">Second Title</a>"
            + "<a class=\"result__snippet\" href=\"#\">Another snippet.</a>"
            + "<a class=\"result__url\" href=\"#\">second.com</a>"
            + "</body></html>";

    @Test
    public void parsesResults() {
        List<SearchResult> results = DdgHtmlParser.parse(SAMPLE, 5);
        assertEquals(2, results.size());
        assertEquals("Example Title", results.get(0).title);
        assertEquals("https://example.com/page", results.get(0).url);
        assertTrue(results.get(0).snippet.contains("short snippet"));
        assertEquals("Second Title", results.get(1).title);
        assertEquals("https://second.com/", results.get(1).url);
    }

    @Test
    public void discardsNavigationAndRegionJunk() {
        List<SearchResult> results = DdgHtmlParser.parse(SAMPLE, 5);
        for (SearchResult r : results) {
            assertFalse(r.title.contains("Argentina"));
            assertFalse(r.title.contains("Australia"));
            assertFalse(r.snippet.contains("Argentina"));
        }
    }

    @Test
    public void emptyAndNullSafe() {
        assertEquals(0, DdgHtmlParser.parse(null, 5).size());
        assertEquals(0, DdgHtmlParser.parse("", 5).size());
        assertEquals(0, DdgHtmlParser.parse("<html>no results here</html>", 5).size());
    }

    @Test
    public void decodesRedirectUrl() {
        assertEquals("https://example.com/a?b=1&c=2",
                DdgHtmlParser.decodeRedirect("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1%26c%3D2&rut=x"));
        assertEquals(null, DdgHtmlParser.decodeRedirect("//duckduckgo.com/other"));
    }

    @Test
    public void stripsTagsFromTitleAndSnippet() {
        List<SearchResult> results = DdgHtmlParser.parse(SAMPLE, 5);
        assertFalse(results.get(0).title.contains("<b>"));
        assertFalse(results.get(0).title.contains("</b>"));
    }
}
