package com.mrnobody.agent.planner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class LatestVideoSkillTest {

    private static final String ASK =
            "the latest video on youtube from screen crush channel";

    @Test
    public void queryIsRestrictedToYouTubeWatchResults() {
        assertTrue(LatestVideoSkill.matches(ASK));
        String query = LatestVideoSkill.searchQuery(ASK);
        assertTrue(query, query.contains("screen crush"));
        assertTrue(query, query.contains("youtube"));
        assertFalse(query, query.contains("site:youtube.com/watch"));
        assertFalse(query, query.contains("sky"));
    }

    @Test
    public void channelNameIsExtractedFromTheRequest() {
        assertEquals("screen crush", LatestVideoSkill.channel(ASK));
    }

    @Test
    public void channelPhrasingsBeyondFromAreUnderstood() {
        // BUG-2: only "from … channel" was recognised; every other phrasing
        // sent a junk query to search.
        assertEquals("mkbhd", LatestVideoSkill.channel(
                "find the latest video on the mkbhd channel"));
        assertEquals("veritasium", LatestVideoSkill.channel(
                "newest video by veritasium channel"));
        assertEquals("mkbhd", LatestVideoSkill.channel(
                "get the latest video on mkbhd's youtube channel"));
    }

    @Test
    public void noChannelFallbackQueryDropsTheQuestionWrapper() {
        String query = LatestVideoSkill.searchQuery(
                "Can you find me the latest video about quantum computing on youtube?");
        assertTrue(query, query.contains("quantum computing"));
        assertTrue(query, query.contains("youtube"));
        assertFalse(query, query.toLowerCase().contains("can you"));
        assertFalse(query, query.toLowerCase().contains("find me"));
        assertFalse(query, query.contains("?"));
    }

    @Test
    public void answerUsesListingMetadataNotWatchPageJavascript() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> wrong = new LinkedHashMap<>();
        wrong.put("title", "Movie news");
        wrong.put("url", "https://screencrush.com/news");
        rows.add(wrong);
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("title", "ScreenCrush: The New Superman Explained");
        video.put("url", "https://www.youtube.com/watch?v=abc123");
        video.put("snippet", "The newest breakdown from ScreenCrush.");
        rows.add(video);

        LatestVideoSkill.Match match = LatestVideoSkill.find(ASK, rows);
        assertNotNull(match);
        String answer = match.answer();
        assertTrue(answer, answer.contains("The New Superman Explained"));
        assertTrue(answer, answer.contains("https://www.youtube.com/watch?v=abc123"));
        assertFalse(answer, answer.contains("EXPERIMENT_FLAGS"));
    }
}
