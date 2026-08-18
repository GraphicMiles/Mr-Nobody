package com.mrnobody.agent.tasks;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a recurring task's new answer is meaningfully different from
 * the last one it produced.
 *
 * <p>Monitoring is only useful when it says something happened. A "track the
 * price" task that re-runs hourly and reports the identical figure every time
 * is noise; one that reports "still $64,282" when nothing moved and "$64,282
 * → $65,901" when it did is the whole point. This class is the pure comparison
 * behind that, and it deliberately mirrors {@code PageAnchor}: word overlap,
 * not a hash, so a clock stamp or a reworded sentence does not read as a change
 * while an actually-different figure does.
 */
public final class ChangeDetector {

    /** The sentence a recurring answer carries when nothing changed. */
    public static final String NO_CHANGE = "No change since your last check.";

    /** The sentence a recurring answer carries when something did change. */
    public static final String CHANGED = "Changed since your last check.";

    /**
     * How much of the previous wording must survive for two answers to count
     * as "the same". High enough that a real change registers, low enough that
     * a timestamp or a rephrased sentence does not.
     */
    private static final double MIN_RETAINED = 0.85;

    private static final int MAX_WORDS = 2_000;

    private ChangeDetector() {
    }

    /**
     * True when {@code previous} and {@code current} are close enough that
     * nothing worth telling the user has changed. Both empty counts as
     * unchanged (there is nothing to differ over).
     */
    public static boolean unchanged(String previous, String current) {
        Set<String> before = wordsOf(previous);
        Set<String> after = wordsOf(current);
        if (before.isEmpty() && after.isEmpty()) return true;
        if (before.isEmpty() || after.isEmpty()) return false;

        int retained = 0;
        for (String w : before) {
            if (after.contains(w)) retained++;
        }
        return (double) retained / before.size() >= MIN_RETAINED;
    }

    private static Set<String> wordsOf(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String token : text.toLowerCase(Locale.ROOT).split("\\s+")) {
            // Strip punctuation at the edges so "today," "today." and "today"
            // are one word — the facts, not the formatting, are what changed.
            String w = token;
            int start = 0;
            int end = w.length();
            while (start < end && !isWordChar(w.charAt(start))) start++;
            while (end > start && !isWordChar(w.charAt(end - 1))) end--;
            w = w.substring(start, end);
            if (w.isEmpty()) continue;
            out.add(w);
            if (out.size() >= MAX_WORDS) break;
        }
        return out;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
