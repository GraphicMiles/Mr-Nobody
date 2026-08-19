package com.mrnobody.agent.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cheap labels on an answer: fact, source opinion, or agent inference.
 *
 * <p>The brief forbids claiming "best" as an objective fact when the
 * evidence is rankings. This does not judge truth; it tags the shape of
 * the sentence so the reader can see the distinction.
 */
public final class ClaimKind {

    public enum Kind { FACT, OPINION, INFERENCE }

    private static final Pattern OPINION = Pattern.compile(
            "\\b(best|worst|greatest|ranked|ranking|review(?:ers)?|critics?|"
                    + "according to|rated|score[sd]?|recommended)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INFERENCE = Pattern.compile(
            "\\b(appears?|seems?|likely|probably|suggests?|I think|"
                    + "it looks like|on balance|overall)\\b",
            Pattern.CASE_INSENSITIVE);

    private ClaimKind() {
    }

    public static Kind classify(String sentence) {
        if (sentence == null || sentence.trim().isEmpty()) return Kind.FACT;
        if (INFERENCE.matcher(sentence).find()) return Kind.INFERENCE;
        if (OPINION.matcher(sentence).find()) return Kind.OPINION;
        return Kind.FACT;
    }

    /**
     * A short legend when the answer mixes kinds. Empty when everything
     * reads as a sourced fact.
     */
    public static String note(String answer) {
        if (answer == null || answer.isEmpty()) return "";
        List<Kind> seen = new ArrayList<>();
        for (String part : answer.split("(?<=[.!?])\\s+")) {
            Kind k = classify(part);
            if (!seen.contains(k)) seen.add(k);
        }
        if (seen.size() <= 1 && !seen.contains(Kind.OPINION) && !seen.contains(Kind.INFERENCE)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (seen.contains(Kind.OPINION)) {
            sb.append("Some statements above are source opinions or rankings, not facts.");
        }
        if (seen.contains(Kind.INFERENCE)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("Some statements are the agent's inference from those sources.");
        }
        return sb.toString();
    }
}
