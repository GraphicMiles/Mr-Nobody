package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SiteMemoryTest {

    @Before
    public void setUp() {
        SiteMemory.reset();
    }

    @After
    public void tearDown() {
        SiteMemory.reset();
    }

    @Test
    public void aFreshHostStartsOnHttp() {
        assertFalse(SiteMemory.preferBrowser("x.com"));
        assertEquals(FetchLadder.Step.HTTP, FetchLadder.firstStep("x.com"));
    }

    @Test
    public void twoChallengesPreferTheBrowser() {
        SiteMemory.remember("x.com", PageKind.Kind.CHALLENGE);
        assertFalse(SiteMemory.preferBrowser("x.com"));
        SiteMemory.remember("x.com", PageKind.Kind.SPA);
        assertTrue(SiteMemory.preferBrowser("x.com"));
        assertEquals(FetchLadder.Step.BROWSER, FetchLadder.firstStep("x.com"));
    }

    @Test
    public void aStaticReadClearsTheStreak() {
        SiteMemory.remember("blog.com", PageKind.Kind.CHALLENGE);
        SiteMemory.remember("blog.com", PageKind.Kind.CHALLENGE);
        assertTrue(SiteMemory.preferBrowser("blog.com"));
        SiteMemory.remember("blog.com", PageKind.Kind.STATIC);
        assertFalse(SiteMemory.preferBrowser("blog.com"));
        assertEquals(PageKind.Kind.STATIC, SiteMemory.lastKind("blog.com"));
    }
}
