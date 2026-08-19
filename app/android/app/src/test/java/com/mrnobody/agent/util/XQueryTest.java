package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class XQueryTest {

    @Test
    public void liveFromUsesThePublicOperator() {
        assertEquals("https://x.com/search?q=from%3AMarvel&f=live",
                XQuery.liveFrom("@Marvel"));
        assertEquals("https://x.com/Marvel", XQuery.profile("@Marvel"));
    }

    @Test
    public void pagesPreferLiveSearch() {
        List<String> pages = XQuery.pages("Marvel", "announcements");
        assertTrue(pages.get(0).contains("from%3AMarvel"));
        assertTrue(pages.get(0).contains("f=live"));
    }

    @Test
    public void namedSiteSkillOpensFromHandle() {
        java.util.List<String> pages = com.mrnobody.agent.planner.NamedSiteSkill.pagesToOpen(
                "ping me about anything new from @StudioHub");
        boolean from = false;
        for (String p : pages) {
            if (p.contains("from%3AStudioHub") || p.contains("from:StudioHub")) from = true;
        }
        assertTrue("expected a from: search, got " + pages, from);
    }
}
