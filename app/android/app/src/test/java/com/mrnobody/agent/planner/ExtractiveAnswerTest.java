package com.mrnobody.agent.planner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExtractiveAnswerTest {

    @Test
    public void pagesReadYieldCitedSentencesNotASearchDump() {
        String sources = "\n[1] Bitcoin price\nhttps://example.com/btc\n"
                + "Bitcoin traded at 64000 dollars on Tuesday. Analysts said demand was steady.\n";
        String answer = ExtractiveAnswer.compose("what is the bitcoin price", sources, true, null);
        assertTrue(answer, answer.contains("64000"));
        assertTrue(answer, answer.contains("[1]"));
        assertTrue(answer, answer.contains("No language model was used"));
        assertFalse(answer, answer.startsWith("Search results for"));
    }

    @Test
    public void headingDropsResearchDirectivesAndKeepsTheSubject() {
        String heading = ExtractiveAnswer.heading(
                "Research why the sky appears blue. Use at least two reliable sources "
                        + "and include citations.");
        assertTrue(heading, heading.equals("Why the sky appears blue"));
        assertFalse(heading, heading.contains("include citations"));
    }

    @Test
    public void unreadPagesAreLabelledAsListingsNotAnAnswer() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "Some page");
        row.put("url", "https://example.com/a");
        row.put("snippet", "A snippet.");
        rows.add(row);
        String answer = ExtractiveAnswer.compose("find laptops", "", false, rows);
        assertTrue(answer, answer.contains("search listings, not an answer"));
        assertTrue(answer, answer.contains("Some page"));
        assertFalse(answer, answer.contains("Search results for"));
    }

    @Test
    public void nothingReadSaysSo() {
        String answer = ExtractiveAnswer.compose("hello", "", false, null);
        assertTrue(answer, answer.contains("Nothing was read"));
    }
}
