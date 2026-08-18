package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TitleMatchTest {

    @Test
    public void aReleaseNameMatchesTheWork() {
        assertTrue(TitleMatch.matches(
                "Avengers.Infinity.War.2018.1080p.mkv", "Infinity War"));
        assertTrue(TitleMatch.score(
                "Avengers Infinity War (2018)",
                TitleMatch.queryFrom("download Infinity War from nkiri.ink", "nkiri.ink")) >= 60);
    }

    @Test
    public void anUnrelatedTitleDoesNotMatch() {
        assertFalse(TitleMatch.matches("The Godfather 1972", "Infinity War"));
    }

    @Test
    public void queryFromDropsTheHostAndTheVerbs() {
        String q = TitleMatch.queryFrom(
                "download Avengers Infinity War from nkiri.ink", "nkiri.ink");
        assertEquals("avengers infinity war", q);
    }
}
