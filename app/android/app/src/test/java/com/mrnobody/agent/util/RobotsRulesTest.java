package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RobotsRulesTest {

    @Test
    public void starGroupIsUsedWhenWeHaveNoSpecificBlock() {
        RobotsRules r = RobotsRules.parse(
                "User-agent: *\n"
                        + "Disallow: /admin\n"
                        + "Allow: /admin/public\n"
                        + "Crawl-delay: 2\n"
                        + "Sitemap: https://example.com/sitemap.xml\n");
        assertFalse(r.allows("/admin/secret"));
        assertTrue(r.allows("/admin/public/index"));
        assertTrue(r.allows("/posts/1"));
        assertEquals(2, r.crawlDelaySeconds());
        assertTrue(r.sitemaps().contains("https://example.com/sitemap.xml"));
    }

    @Test
    public void ourUserAgentWinsOverStar() {
        RobotsRules r = RobotsRules.parse(
                "User-agent: *\nDisallow: /\n\n"
                        + "User-agent: MrNobody\nDisallow: /private\n");
        assertTrue(r.allows("/posts"));
        assertFalse(r.allows("/private/x"));
    }

    @Test
    public void emptyDisallowDoesNotBlock() {
        RobotsRules r = RobotsRules.parse("User-agent: *\nDisallow:\n");
        assertTrue(r.allows("/anything"));
    }

    @Test
    public void sitemapLocsAreLifted() {
        String xml = "<urlset><url><loc>https://nkiri.ink/avengers-infinity-war/</loc></url>"
                + "<url><loc>https://nkiri.ink/other/</loc></url></urlset>";
        List<String> locs = RobotsRules.locsFrom(xml);
        assertEquals(2, locs.size());
        List<String> hit = RobotsRules.locsMatching(xml, "infinity war", 4);
        assertEquals(1, hit.size());
        assertTrue(hit.get(0).contains("infinity-war"));
    }

    @Test
    public void urlForStripsWww() {
        assertEquals("https://example.com/robots.txt", RobotsRules.urlFor("www.example.com"));
    }
}
