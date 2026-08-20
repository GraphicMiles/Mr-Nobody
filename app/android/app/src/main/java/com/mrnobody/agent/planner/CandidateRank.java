package com.mrnobody.agent.planner;

import com.mrnobody.agent.util.Hosts;
import com.mrnobody.agent.util.SiteMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Order read candidates by the likelihood that a plain HTTP fetch succeeds.
 *
 * <p>The owner's rule 6: rank CHOOSE PAGES by cheap-success. A host that has
 * been serving usable text over plain HTTP is read before a host that keeps
 * demanding the twenty-second headless browser, so the read loop's early exit
 * (see {@link EvidenceSufficiency}) fires on the cheap sources first.
 *
 * <p>The sort is stable: among hosts with the same score — including the
 * common case where nothing is known about any of them — the search engine's
 * relevance order is preserved untouched.
 */
public final class CandidateRank {

    private CandidateRank() {
    }

    /** Search-result rows, reordered by per-host HTTP success score. */
    public static List<Map<String, Object>> byCheapSuccess(List<Map<String, Object>> results) {
        if (results == null || results.size() < 2) return results;
        List<Map<String, Object>> out = new ArrayList<>(results);
        out.sort((a, b) -> Integer.compare(scoreOf(b), scoreOf(a)));
        return out;
    }

    private static int scoreOf(Map<String, Object> row) {
        String url = String.valueOf(row.get("url"));
        return SiteMemory.httpScore(Hosts.firstIn(url));
    }
}
