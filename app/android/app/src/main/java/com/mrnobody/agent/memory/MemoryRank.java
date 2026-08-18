package com.mrnobody.agent.memory;

import com.mrnobody.agent.core.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ranks the agent's past tasks against a query, so a task can ask "what have I
 * done about this before" and get the relevant history back instead of the
 * whole store.
 *
 * <p>Pure on purpose: the relevance decision is a word-overlap score, unit-
 * tested on the JVM. It is deliberately simple — this is a recall step for a
 * local, privacy-first memory, not a search engine. A token shared with the
 * query's words scores; the instruction weighs more than the result, because
 * what the user asked is a better signal of relevance than the prose the model
 * produced. Only completed tasks are returned: a half-finished or failed task
 * is not something to build continuity on.
 */
public final class MemoryRank {

    /** How much more an instruction word counts than a result word. */
    private static final int INSTRUCTION_WEIGHT = 2;

    /** A ranked hit. */
    public static final class Hit {
        public final long id;
        public final String instruction;
        public final String result;
        public final int score;

        Hit(long id, String instruction, String result, int score) {
            this.id = id;
            this.instruction = instruction;
            this.result = result;
            this.score = score;
        }
    }

    private MemoryRank() {
    }

    /**
     * The up-to-{@code limit} most relevant completed tasks for {@code query},
     * best first, with a score above zero only. Returns empty on a blank query
     * or no matches.
     */
    public static List<Hit> search(String query, List<Task> tasks, int limit) {
        if (query == null || query.trim().isEmpty() || tasks == null) {
            return Collections.emptyList();
        }
        Set<String> q = wordsOf(query);

        List<Hit> hits = new ArrayList<>();
        for (Task t : tasks) {
            if (t == null || t.status() != Task.Status.COMPLETED) continue;
            int score = score(q, t.instruction(), t.result());
            if (score <= 0) continue;
            hits.add(new Hit(t.id(), t.instruction(), t.result(), score));
        }
        hits.sort((a, b) -> Integer.compare(b.score, a.score));
        if (hits.size() > limit) return hits.subList(0, limit);
        return hits;
    }

    private static int score(Set<String> query, String instruction, String result) {
        int total = 0;
        for (String w : wordsOf(instruction)) {
            if (query.contains(w)) total += INSTRUCTION_WEIGHT;
        }
        for (String w : wordsOf(result)) {
            if (query.contains(w)) total += 1;
        }
        return total;
    }

    static Set<String> wordsOf(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String token : text.toLowerCase(Locale.ROOT).split("\\s+")) {
            int start = 0;
            int end = token.length();
            while (start < end && !Character.isLetterOrDigit(token.charAt(start))) start++;
            while (end > start && !Character.isLetterOrDigit(token.charAt(end - 1))) end--;
            String w = token.substring(start, end);
            if (!w.isEmpty()) out.add(singular(w));
        }
        return out;
    }

    /**
     * A light, naive singularisation so "laptop" matches "laptops" — both sides
     * go through it, so it is consistent and cheap. This is recall, not exact
     * search, so an occasional collision is harmless: the user sees what was
     * retrieved and can disregard it.
     */
    private static String singular(String w) {
        return w.length() > 4 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
    }
}
