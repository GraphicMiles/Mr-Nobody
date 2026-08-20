package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Reading a results page, and knowing when one has refused to be read.
 */
public class SearchProvidersTest {

    // The page duckduckgo.com/html actually returns to a non-browser request:
    // HTTP 200, no results, an anti-bot notice. A parser sees "no results".
    private static final String CHALLENGE_PAGE =
            "<!DOCTYPE html><html><head><title>DuckDuckGo</title></head><body>"
                    + "<div class='anomaly-modal__title'>Unfortunately, bots use DuckDuckGo too.</div>"
                    + "<p>Please try again. If this keeps happening, an anomaly was detected.</p>"
                    + "</body></html>";

    @Test
    public void aChallengePageIsNotAnEmptyInternet() {
        assertTrue(SearchChallenge.isChallenge(CHALLENGE_PAGE));
        assertTrue(SearchChallenge.isChallenge("<html>Just a moment...<div id='cf-browser-verification'>"));
        assertTrue(SearchChallenge.isChallenge("<html>Please verify you are human</html>"));
    }

    @Test
    public void arealPageIsNotMistakenForOne() {
        assertFalse(SearchChallenge.isChallenge(
                "<html><body><div class='result'><a class='result__a' href='x'>Lagos food</a>"
                        + "</div></body></html>"));
        assertFalse(SearchChallenge.isChallenge(""));
        assertFalse(SearchChallenge.isChallenge(null));
    }

    @Test
    public void theMessageSaysNothingWasSearched() {
        String message = SearchChallenge.message("DuckDuckGo");
        assertTrue(message, message.contains("blocked"));
        assertTrue(message, message.contains("nothing is being guessed"));
    }

    // ------------------------------------------------------------ the chain

    @Test
    public void theUsersEngineIsTriedFirst() {
        assertEquals("bing", SearchProviders.chain("https://www.bing.com/search?q=").get(0).id);
        assertEquals("startpage",
                SearchProviders.chain("https://www.startpage.com/sp/search?query=").get(0).id);
        assertEquals("ddg", SearchProviders.chain("https://duckduckgo.com/?q=").get(0).id);
    }

    @Test
    public void googleIsOnlyUsedWhenItIsAskedFor() {
        for (SearchProviders.Provider p : SearchProviders.chain(null)) {
            assertFalse("Google must not be in the default chain", "google".equals(p.id));
        }
        assertEquals("google", SearchProviders.chain("https://www.google.com/search?q=").get(0).id);
    }

    @Test
    public void explicitlyRequestedGoogleIsTheOnlyProvider() {
        List<SearchProviders.Provider> chain =
                SearchProviders.chainRequested("google", null);
        assertEquals(1, chain.size());
        assertEquals("google", chain.get(0).id);
    }

    @Test
    public void everyProviderBuildsAnEncodedQueryUrl() {
        for (SearchProviders.Provider p : SearchProviders.chain(null)) {
            String url = p.url("best chinese restaurants in lagos & environs");
            assertTrue(url, url.startsWith("https://"));
            assertFalse("the query must be encoded", url.contains(" "));
            assertTrue(url, url.contains("chinese"));
        }
    }

    @Test
    public void theExtractorIsSelfContainedJavascript() {
        String script = SearchProviders.chain(null).get(0).script(5);
        assertTrue(script.startsWith("(function()"));
        assertTrue(script, script.contains("JSON.stringify"));
        assertFalse("the placeholder must be substituted", script.contains("/*MAX*/"));
        assertFalse(script.contains("/*SELECTORS*/"));
    }

    // -------------------------------------------------------- result parsing

    @Test
    public void resultsComeBackCleanFromTheScript() {
        String json = "[{\"title\":\"  Best Chinese in Lagos  \","
                + "\"url\":\"https://guardian.ng/food/list?utm_source=ddg&page=2\","
                + "\"snippet\":\"Our\\n picks\"}]";

        List<SearchResult> results = SearchResultsJson.parse(json, 5);

        assertEquals(1, results.size());
        assertEquals("Best Chinese in Lagos", results.get(0).title);
        assertEquals("https://guardian.ng/food/list?page=2", results.get(0).url);
        assertEquals("Our picks", results.get(0).snippet);
    }

    @Test
    public void aRedirectorIsUnwrapped() {
        assertEquals("https://guardian.ng/food",
                SearchResultsJson.clean("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fguardian.ng%2Ffood"));
        assertEquals("https://guardian.ng/food",
                SearchResultsJson.clean("https://www.google.com/url?q=https%3A%2F%2Fguardian.ng%2Ffood&sa=U"));
    }

    @Test
    public void oneResultPerSiteSoTheAgentReadsThreePlacesNotOneBlog() {
        String json = "[{\"title\":\"A\",\"url\":\"https://blog.example.com/1\"},"
                + "{\"title\":\"B\",\"url\":\"https://blog.example.com/2\"},"
                + "{\"title\":\"C\",\"url\":\"https://other.example.org/3\"}]";

        List<SearchResult> results = SearchResultsJson.parse(json, 5);

        assertEquals(2, results.size());
        assertEquals("blog.example.com", SearchResultsJson.hostOf(results.get(0).url));
        assertEquals("other.example.org", SearchResultsJson.hostOf(results.get(1).url));
    }

    @Test
    public void junkIsDiscardedRatherThanPassedOn() {
        assertTrue(SearchResultsJson.parse("not json", 5).isEmpty());
        assertTrue(SearchResultsJson.parse("[]", 5).isEmpty());
        assertTrue(SearchResultsJson.parse(null, 5).isEmpty());
        // Entries missing a usable URL or title are dropped, not half-kept.
        assertTrue(SearchResultsJson.parse(
                "[{\"title\":\"x\",\"url\":\"javascript:alert(1)\"},{\"url\":\"https://a.com\"}]", 5)
                .isEmpty());
    }

    @Test
    public void trackingParametersAreRemovedButRealOnesKept() {
        assertEquals("https://shop.example.com/item?id=42",
                SearchResultsJson.stripTracking(
                        "https://shop.example.com/item?id=42&utm_source=x&fbclid=y&gclid=z"));
        assertEquals("https://example.com/page",
                SearchResultsJson.stripTracking("https://example.com/page?utm_campaign=only"));
    }
}
