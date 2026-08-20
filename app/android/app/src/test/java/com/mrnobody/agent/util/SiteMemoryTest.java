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

    // ------------------------------------------------ http cheap-success score

    @Test
    public void anUnknownHostScoresZero() {
        assertEquals(0, SiteMemory.httpScore("never-seen.example"));
    }

    @Test
    public void outcomesMoveTheScoreBothWays() {
        SiteMemory.recordHttpOutcome("news.example", true);
        SiteMemory.recordHttpOutcome("news.example", true);
        assertEquals(2, SiteMemory.httpScore("news.example"));
        SiteMemory.recordHttpOutcome("news.example", false);
        assertEquals(1, SiteMemory.httpScore("news.example"));
    }

    @Test
    public void theScoreIsClampedSoHistoryCannotCondemnAHostForever() {
        for (int i = 0; i < 10; i++) SiteMemory.recordHttpOutcome("bad.example", false);
        assertEquals(-3, SiteMemory.httpScore("bad.example"));
        for (int i = 0; i < 10; i++) SiteMemory.recordHttpOutcome("good.example", true);
        assertEquals(3, SiteMemory.httpScore("good.example"));
        // Two good reads pull a condemned host back to answering range.
        SiteMemory.recordHttpOutcome("bad.example", true);
        SiteMemory.recordHttpOutcome("bad.example", true);
        assertEquals(-1, SiteMemory.httpScore("bad.example"));
    }

    @Test
    public void wwwAndCaseNormaliseToTheSameHostForScoring() {
        SiteMemory.recordHttpOutcome("WWW.Score.Example", true);
        assertEquals(1, SiteMemory.httpScore("score.example"));
    }
}
