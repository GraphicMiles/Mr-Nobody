package com.mrnobody.browser.blocking;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * JVM unit tests for the blocklist matcher (the privacy-critical code path).
 * No Android runtime required — Blocklist is pure Java.
 */
public class BlocklistTest {

    private Blocklist list;

    @Before
    public void setUp() {
        list = new Blocklist();
    }

    private void addAd(String rule) {
        list.addLine(rule, list.ads);
    }

    @Test
    public void exactDomainBlocked() {
        addAd("||doubleclick.net^");
        assertTrue(list.ads.matchesHost("doubleclick.net"));
    }

    @Test
    public void subdomainBlocked() {
        addAd("||doubleclick.net^");
        assertTrue(list.ads.matchesHost("ad.doubleclick.net"));
        assertTrue(list.ads.matchesHost("a.b.doubleclick.net"));
    }

    @Test
    public void unrelatedDomainNotBlocked() {
        addAd("||doubleclick.net^");
        assertFalse(list.ads.matchesHost("notdoubleclick.net"));
        assertFalse(list.ads.matchesHost("doubleclick.net.evil.com"));
    }

    @Test
    public void pathRuleBlocked() {
        addAd("||linkedin.com/px^");
        assertTrue(list.ads.matchesPath("linkedin.com", "/px/123"));
        assertFalse(list.ads.matchesPath("linkedin.com", "/profile"));
        // a subdomain of the rule's host matches; a look-alike domain does not
        assertTrue(list.ads.matchesPath("www.linkedin.com", "/px/x"));
        assertFalse(list.ads.matchesPath("licdn.com", "/px"));
    }

    @Test
    public void wildcardRuleMatches() {
        addAd("*adserver*/banner*");
        assertTrue(list.ads.matchesWildcard("cdn.adserver.com/banner/hero.jpg"));
        assertFalse(list.ads.matchesWildcard("cdn.adserver.com/index.html"));
    }

    @Test
    public void commentsAndExceptionsIgnored() {
        addAd("! this is a comment");
        addAd("@@doubleclick.net^");
        addAd("example.com##.ad-banner"); // element hiding — not a network rule
        assertFalse(list.ads.matchesHost("example.com"));
        assertFalse(list.ads.matchesHost("doubleclick.net"));
    }

    @Test
    public void optionsStripped() {
        addAd("||doubleclick.net^$third-party");
        assertTrue(list.ads.matchesHost("doubleclick.net"));
    }

    @Test
    public void caseInsensitive() {
        addAd("||DOUBLECLICK.NET^");
        assertTrue(list.ads.matchesHost("DoubleClick.net"));
    }
}
