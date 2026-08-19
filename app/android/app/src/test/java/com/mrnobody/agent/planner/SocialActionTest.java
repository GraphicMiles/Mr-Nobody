package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.mrnobody.agent.core.ImpactKind;
import com.mrnobody.agent.core.Tier;

import org.junit.Test;

public class SocialActionTest {

    @Test
    public void notificationsAreRead() {
        assertEquals(Tier.READ, SocialAction.classify("check my notifications").tier);
    }

    @Test
    public void draftIsWrite() {
        assertEquals(ImpactKind.DRAFT, SocialAction.classify("draft a reply").impact);
    }

    @Test
    public void publishAndDeleteAreExec() {
        assertEquals(Tier.EXEC, SocialAction.classify("publish this post").tier);
        assertEquals(ImpactKind.DELETE, SocialAction.classify("delete the tweet").impact);
    }

    @Test
    public void aLaptopQuestionIsNotSocial() {
        assertNull(SocialAction.classify("find laptops under 500000"));
    }
}
