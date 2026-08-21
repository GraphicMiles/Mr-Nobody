package com.mrnobody.agent.planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * When has the read loop gathered enough to answer?
 *
 * <p>Read-loop rule 1 (see README): after each read, if at least {@link #MIN_SOURCES}
 * distinct sources each contribute at least {@link #MIN_SENTENCES_EACH}
 * question-matching prose sentences, stop reading and answer. Before this
 * existed the engine ground through every candidate the search returned —
 * "how old is messi" read six pages for over sixty-nine seconds and was
 * stopped by the user, when page two already contained the answer twice.
 *
 * <p>The judgment reuses {@link ExtractiveAnswer#pick} — the same scoring and
 * prose/boilerplate gates that will compose the final answer — so "sufficient
 * here" and "usable there" can never drift apart. Mirrored sentences are
 * deduplicated across sources, exactly as the composer does, so two mirrors
 * of one page never count as two sources of evidence.
 */
public final class EvidenceSufficiency {

    /** Distinct sources that must each carry enough matching prose. */
    static final int MIN_SOURCES = 2;

    /** Question-matching prose sentences each source must contribute. */
    static final int MIN_SENTENCES_EACH = 2;

    private EvidenceSufficiency() {
    }

    /**
     * True when the sources block already supports an answer.
     *
     * @param question what the user asked
     * @param sources  the numbered source block built by the read loop
     *                 ({@code [1] title\nurl\ntext})
     */
    public static boolean enough(String question, String sources) {
        if (question == null || question.trim().isEmpty()) return false;
        if (sources == null || sources.trim().isEmpty()) return false;

        List<ExtractiveAnswer.Source> parsed =
                ExtractiveAnswer.dedupeBodies(ExtractiveAnswer.parseSources(sources));
        if (parsed.size() < MIN_SOURCES) return false;

        Set<String> seen = new HashSet<>();
        int sufficient = 0;
        for (ExtractiveAnswer.Source src : parsed) {
            List<String> picked = new ArrayList<>(
                    ExtractiveAnswer.pick(question, src.body, MIN_SENTENCES_EACH + 1));
            int fresh = 0;
            for (String sentence : picked) {
                if (seen.add(ExtractiveAnswer.normalise(sentence))) fresh++;
            }
            if (fresh >= MIN_SENTENCES_EACH) sufficient++;
            if (sufficient >= MIN_SOURCES) return true;
        }
        return false;
    }
}
