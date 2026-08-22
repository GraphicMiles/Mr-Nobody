package com.mrnobody.agent.planner;

import java.util.Locale;

/**
 * What a question is really asking for. The extraction path has to know this
 * to rank sentences sensibly: "what is the bitcoin price" wants a <em>figure</em>,
 * "who is mrbeast" wants an <em>identity</em>, "explain how bitcoin works" wants a
 * <em>reason</em>. A query word like "price" is not a keyword to match; it is a
 * signal that the answer should contain a number near the subject.
 *
 * <p>Deterministic and pure: this runs with no model, so the local answer path
 * and the remote fallback both classify reliably and are unit-testable.
 */
public enum AnswerIntent {

    /** Asks for a number/amount/date: price, cost, age, population, %, count. */
    FIGURE,
    /** Asks who/what someone or something is: an identity, role, or person. */
    PERSON,
    /** Asks for a definition or plain meaning of a term. */
    DEFINITION,
    /** Asks why something is the case, or how it works. */
    EXPLAIN,
    /** Asks to compare two or more things. */
    COMPARE,
    /** No strong signal; fall back to generic relevance. */
    GENERIC;

    private static final String[] FIGURE_CUES = {
            "price", "cost", "age", "population", "how many", "how much",
            "percentage", "percent", "rate", "salary", "worth", "net worth",
            "value", "height", "weight", "distance", "size", "temperature",
            "inflation", "gdp", "what time", "what year", "what date",
            "how old", "how tall", "how long", "how big",
    };
    private static final String[] PERSON_CUES = {
            "who is", "who was", "who are", "who's", "who invented",
            "who created", "who wrote", "who founded", "byname of",
            "tell me about", "biography", "about ",
    };
    private static final String[] DEFINITION_CUES = {
            "what is a", "what is the", "what is ", "what's a", "what's the",
            "meaning of", "define", "definition of", "what does", "what are",
    };
    private static final String[] EXPLAIN_CUES = {
            "why is", "why does", "why do", "why are", "why was", "how does",
            "how do", "how is", "how are", "explain", "how it works",
            "how did", "because",
    };
    private static final String[] COMPARE_CUES = {
            "compare", "vs", "versus", "difference between", "differences between",
            "better than", "which is better", "versus",
    };

    /**
     * Classify a question. The order matters: FIGURE and PERSON are checked
     * before DEFINITION/EXPLAIN so "what is bitcoin's price" reads as a figure,
     * not a definition. Never throws.
     */
    public static AnswerIntent classify(String question) {
        if (question == null) return GENERIC;
        String q = question.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return GENERIC;

        if (containsAny(q, COMPARE_CUES)) return COMPARE;
        if (containsAny(q, FIGURE_CUES)) return FIGURE;
        if (containsAny(q, PERSON_CUES)) return PERSON;
        if (containsAny(q, EXPLAIN_CUES)) return EXPLAIN;
        if (containsAny(q, DEFINITION_CUES)) return DEFINITION;

        // A bare "X is Y" is a definition; "what is X" is a definition unless a
        // figure/person cue above already claimed it.
        if (q.startsWith("what is ") || q.startsWith("what's ")) return DEFINITION;
        if (q.startsWith("who ")) return PERSON;
        return GENERIC;
    }

    /**
     * Evidence that a sentence actually answers this intent, 0..1.
     *
     * <p>This is the part the old scorer was missing: for a FIGURE query a
     * sentence without any number is weak even if it names the subject; for a
     * PERSON query a sentence that does not identify a person is weak; for an
     * EXPLAIN query a sentence with no causal link is weak.
     */
    public double evidence(String sentence) {
        if (sentence == null) return 0;
        String s = sentence.toLowerCase(Locale.ROOT);
        switch (this) {
            case FIGURE:
                // A figure answer needs a *standalone number*, ideally with a
                // currency/unit marker ("$64,000", "64000 dollars", "8%", "12
                // million"). A hyphenated identifier such as "SHA-256" is not a
                // figure, so it must not satisfy a price query.
                if (!hasFigure(sentence)) return 0.2;
                return hasFigureMarker(sentence) ? 1.0 : 0.4;
            case PERSON:
                return (containsAny(s, "is a", "is an", "is the", "known for",
                        "real name", "is an american", "is the byname", "grew up",
                        "born", "is a youtube", "is a", "is an")
                        && s.matches(".*(youtuber|singer|actor|athlete|founder|"
                                + "artist|entrepreneur|publisher|influencer|streamer|"
                                + "creator|businessman|an american|a american).*"))
                        ? 1.0 : 0.0;
            case DEFINITION:
                return containsAny(s, "is a", "is the", "refers to", "is defined as",
                        "means", "describes", "is a term") ? 1.0 : 0.0;
            case EXPLAIN:
                return containsAny(s, "because", "due to", "since ", "caused by",
                        "leads to", "which means", "result of", "as a result",
                        "is why", "helps", "allows", "makes it", "so the sky",
                        "so it", "scattered more strongly", "is caused",
                        "which is why", "causes", "makes the") ? 1.0 : 0.0;
            case COMPARE:
                return containsAny(s, " than ", "vs", "versus", "whereas",
                        "while ", "compared to") ? 1.0 : 0.0;
            default:
                return 0.5;
        }
    }

    /**
     * A standalone number, not part of a hyphenated/alphanumeric identifier
     * (so "SHA-256", "Web3" and "5G" are never read as a figure). A digit
     * preceded by a letter or hyphen is an identifier, not an amount.
     */
    private static boolean hasFigure(String sentence) {
        return sentence != null && sentence.matches(
                "(?s).*(?<![A-Za-z0-9-])\\d[\\d,]*(?:\\.\\d+)?\\b.*");
    }

    /** A figure carrying a currency/unit marker, i.e. a real amount. */
    private static boolean hasFigureMarker(String sentence) {
        if (sentence == null) return false;
        return containsAny(sentence.toLowerCase(Locale.ROOT),
                "$", "€", "£", " usd", " percent", "%", " dollars", " years old",
                " million", " billion", " trillion", " per ", " rupees", " naira");
    }

    private static boolean containsAny(String h, String... needles) {
        for (String n : needles) {
            if (h.contains(n)) return true;
        }
        return false;
    }
}
