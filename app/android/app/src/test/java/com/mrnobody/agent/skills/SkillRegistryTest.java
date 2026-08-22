package com.mrnobody.agent.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SkillRegistryTest {
    @Test public void creationRoutesAboveResearch() {
        SkillMatch match = SkillRegistry.standard().route("Create an Instagram sale poster in Canva");
        assertTrue(match.isDesign());
        assertEquals("canva-mcp", match.executionPlatform);
        assertEquals(1, match.toolScope.size());
        assertTrue(match.toolScope.contains("design"));
    }

    @Test public void questionAboutDesignRemainsResearch() {
        SkillMatch match = SkillRegistry.standard().route("Explain graphic design principles");
        assertFalse(match.isDesign());
        assertEquals("web.research", match.id);
    }

    @Test public void clockAndResearchRemainTopLevelRoutes() {
        assertTrue(SkillRegistry.standard().route("what time is it").isClock());
        assertEquals("web.research", SkillRegistry.standard()
                .route("who created bitcoin").id);
    }
}
