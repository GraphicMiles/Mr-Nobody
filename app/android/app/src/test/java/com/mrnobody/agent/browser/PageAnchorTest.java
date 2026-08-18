package com.mrnobody.agent.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Refusing a page action when the page moved.
 *
 * <p>The agent reads a page, decides to click something, and clicks a moment
 * later. In between the page can navigate or re-render, and a selector that
 * matched the old document will happily match something else in the new one —
 * the click "succeeds", on the wrong thing. Refusing is recoverable; clicking
 * the wrong element is not.
 *
 * <p>The tolerance tests matter equally: a guard that refuses on every byte of
 * drift is useless on the real web, and an agent that always refuses gets its
 * guard switched off.
 */
public class PageAnchorTest {

    private static final String URL = "https://example.test/page";
    private static final String TEXT = "Download the report here. Published Tuesday.";

    @Test
    public void anUnchangedPageStillMatches() {
        PageAnchor a = PageAnchor.of(URL, TEXT);
        assertTrue(a.matches(URL, TEXT));
        assertNull(a.staleReason(URL, TEXT));
    }

    @Test
    public void navigatingAwayIsRefused() {
        PageAnchor a = PageAnchor.of(URL, TEXT);
        String why = a.staleReason("https://example.test/other", TEXT);

        assertNotNull(why);
        assertTrue(why, why.contains("moved"));
    }

    @Test
    public void replacedContentIsRefused() {
        PageAnchor a = PageAnchor.of(URL, TEXT);
        String why = a.staleReason(URL,
                "Something else entirely, with quite different words in it now.");

        assertNotNull(why);
        assertTrue(why, why.contains("contents changed"));
    }

    @Test
    public void aLiveTickerDoesNotInvalidateTheDecision() {
        // Real pages tick clocks and rotate ads. None of that changes what the
        // agent decided to do.
        PageAnchor a = PageAnchor.of(URL, TEXT);
        assertTrue(a.matches(URL, TEXT + " 12:01"));
    }

    @Test
    public void reflowAndCasingAreNotContentChanges() {
        PageAnchor a = PageAnchor.of(URL, "Download   the report\nhere.");
        assertTrue(a.matches(URL, "download the report here."));
    }

    @Test
    public void fragmentsAndTrailingSlashesAreNotNavigation() {
        PageAnchor a = PageAnchor.of(URL, TEXT);
        assertTrue(a.matches(URL + "/", TEXT));
        assertTrue(a.matches(URL + "#section", TEXT));
    }

    @Test
    public void alargeContentChangeIsCaughtEvenOnALongPage() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append("paragraph ").append(i).append(' ');
        PageAnchor a = PageAnchor.of(URL, big.toString());

        assertFalse(a.matches(URL, "gone"));
    }

    @Test
    public void nullsAreTreatedAsAChangeNotAMatch() {
        PageAnchor a = PageAnchor.of(URL, TEXT);
        assertFalse(a.matches(null, TEXT));
        assertFalse(a.matches(URL, null));
    }

    @Test
    public void itRemembersWhereItWasAnchored() {
        assertTrue(PageAnchor.of(URL, TEXT).url().contains("example.test"));
    }
}
