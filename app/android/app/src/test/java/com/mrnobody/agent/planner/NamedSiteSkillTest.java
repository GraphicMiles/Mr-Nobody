package com.mrnobody.agent.planner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class NamedSiteSkillTest {

    @Test
    public void aNamedHostPlusATitleOpensSearchNotJustTheHomepage() {
        List<String> pages = NamedSiteSkill.pagesToOpen(
                "download Avengers Infinity War from nkiri.ink");
        assertFalse(pages.isEmpty());
        boolean hasSearch = false;
        for (String p : pages) {
            if (p.contains("nkiri.ink") && (p.contains("?s=") || p.contains("/search"))) {
                hasSearch = true;
            }
        }
        assertTrue("expected a search URL on the named host, got " + pages, hasSearch);
        assertTrue(pages.contains("https://nkiri.ink"));
    }

    @Test
    public void aTypedDeepLinkIsUsedAsIs() {
        List<String> pages = NamedSiteSkill.pagesToOpen(
                "get https://nkiri.ink/reacher-s04e01/ for me");
        assertTrue(pages.toString(), pages.contains("https://nkiri.ink/reacher-s04e01/"));
        for (String p : pages) {
            assertFalse("a deep link should not grow a ?s= search: " + p,
                    p.contains("?s="));
        }
    }

    @Test
    public void noSiteMeansNoPages() {
        assertTrue(NamedSiteSkill.pagesToOpen("what is the bitcoin price").isEmpty());
    }
}
