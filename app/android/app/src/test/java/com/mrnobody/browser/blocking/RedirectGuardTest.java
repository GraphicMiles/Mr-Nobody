package com.mrnobody.browser.blocking;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RedirectGuardTest {

    @Test
    public void blocksCrossSiteBettingRedirects() {
        assertTrue(RedirectGuard.shouldBlock(
                "https://news.example/watch", "https://www.bet9ja.com/sports", true));
        assertTrue(RedirectGuard.shouldBlock(
                "https://stream.example/play", "https://stake.com/casino", true));
        assertTrue(RedirectGuard.shouldBlock(
                "https://stream.example/play", "https://ads.1xbet.ng/promo", true));
        assertTrue(RedirectGuard.shouldBlock(
                "https://stream.example/play", "https://betnaija.com/offer", true));
        assertTrue(RedirectGuard.shouldBlock(
                "https://stream.example/play", "https://STAKE.COM./casino", true));
    }

    @Test
    public void ordinaryLinksAreNotBlocked() {
        assertFalse(RedirectGuard.shouldBlock(
                "https://news.example", "https://wikipedia.org", true));
        assertFalse(RedirectGuard.shouldBlock(
                "https://example.com", "https://better.example.com", true));
    }

    @Test
    public void subresourcesStayWithTheFilterEngine() {
        assertFalse(RedirectGuard.shouldBlock(
                "https://news.example", "https://stake.com/pixel.js", false));
    }

    @Test
    public void navigationInsideTheDestinationIsAllowed() {
        assertFalse(RedirectGuard.shouldBlock(
                "https://stake.com/home", "https://stake.com/sports", true));
        assertFalse(RedirectGuard.shouldBlock(
                "https://sports.stake.com/home", "https://casino.stake.com/play", true));
    }

    @Test
    public void missingOrMalformedSourceIsNotCalledACrossSiteRedirect() {
        assertFalse(RedirectGuard.shouldBlock(null, "https://stake.com", true));
        assertFalse(RedirectGuard.shouldBlock("not a URL", "https://stake.com", true));
        assertFalse(RedirectGuard.shouldBlock("https://example.com", "://bad", true));
    }

    @Test
    public void lookalikeDomainsAreNotCaught() {
        assertFalse(RedirectGuard.shouldBlock(
                "https://example.com", "https://notstake.com", true));
        assertFalse(RedirectGuard.shouldBlock(
                "https://example.com", "https://stake.com.evil.example", true));
    }
}
