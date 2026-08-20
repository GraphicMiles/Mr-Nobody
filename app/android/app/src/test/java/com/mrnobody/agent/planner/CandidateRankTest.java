package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.mrnobody.agent.util.SiteMemory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rule 6: candidates are read cheapest-first, relevance order preserved. */
public class CandidateRankTest {

    @Before
    public void setUp() {
        SiteMemory.reset();
    }

    @After
    public void tearDown() {
        SiteMemory.reset();
    }

    private static Map<String, Object> row(String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("title", url);
        return m;
    }

    @Test
    public void unknownHostsKeepTheSearchEnginesOrder() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("https://one.example/a"));
        rows.add(row("https://two.example/b"));
        rows.add(row("https://three.example/c"));
        List<Map<String, Object>> ranked = CandidateRank.byCheapSuccess(rows);
        assertEquals("https://one.example/a", ranked.get(0).get("url"));
        assertEquals("https://two.example/b", ranked.get(1).get("url"));
        assertEquals("https://three.example/c", ranked.get(2).get("url"));
    }

    @Test
    public void aProvenCheapHostJumpsAProvenExpensiveOne() {
        SiteMemory.recordHttpOutcome("slow.example", false);
        SiteMemory.recordHttpOutcome("slow.example", false);
        SiteMemory.recordHttpOutcome("fast.example", true);

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("https://slow.example/page"));
        rows.add(row("https://fast.example/page"));
        rows.add(row("https://unknown.example/page"));
        List<Map<String, Object>> ranked = CandidateRank.byCheapSuccess(rows);
        assertEquals("https://fast.example/page", ranked.get(0).get("url"));
        assertEquals("https://unknown.example/page", ranked.get(1).get("url"));
        assertEquals("https://slow.example/page", ranked.get(2).get("url"));
    }

    @Test
    public void tinyOrNullListsPassThroughUntouched() {
        assertSame(null, CandidateRank.byCheapSuccess(null));
        List<Map<String, Object>> one = new ArrayList<>();
        one.add(row("https://one.example/a"));
        assertSame(one, CandidateRank.byCheapSuccess(one));
    }
}
