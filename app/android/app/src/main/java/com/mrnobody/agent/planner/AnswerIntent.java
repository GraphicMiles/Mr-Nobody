package com.mrnobody.agent.planner;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a question is really asking for. The extraction path has to know this
 * to rank sentences sensibly: "what is the bitcoin price" wants a <em>figure</em>,
 * "who is mrbeast" wants an <em>identity</em>, "explain how bitcoin works" wants a
 * <em>reason</em>.
 *
 * <p>Classification is <em>form-first</em>. Instead of only scanning a list of cue
 * words, it reads the shape of the sentence — the question word, the verb, and
 * whether a numeric/comparative/possessive structure is present — so a phrasing
 * that never hits a cue list ("how much is a tesla model 3", "what does the
 * iphone cost", "which is heavier, gold or iron") is still classified correctly.
 * The cue lists are a fallback for the phrasing patterns that are stable, not the
 * sole mechanism. This is what keeps the local path from being a collection of
 * brittle keyword matchers.
 *
 * <p>Deterministic and pure: no model, so the local answer path and the remote
 * fallback classify reliably and are unit-testable.
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

    // Stable cue words — a fallback, not the primary signal. See the class doc.
    private static final String[] FIGURE_CUES = {
            "price", "cost", "age", "population", "percentage", "percent",
            "rate", "salary", "worth", "net worth", "value", "height", "weight",
            "distance", "size", "temperature", "inflation", "gdp",
            "how old", "how tall", "how long", "how big",
    };
    private static final String[] PERSON_CUES = {
            "who is", "who was", "who are", "who's", "who invented",
            "who created", "who wrote", "who founded", "byname of",
            "tell me about", "biography", "about ",
    };
    private static final String[] DEFINITION_CUES = {
            "what is a", "what is the", "meaning of", "define",
            "definition of", "what does \"", "what does a",
    };
    private static final String[] EXPLAIN_CUES = {
            "why is", "why does", "why do", "why are", "why was", "how does",
            "how do", "how is", "how are", "explain", "how it works", "how did",
    };
    private static final String[] COMPARE_CUES = {
            "compare", " vs ", "versus", "difference between", "differences between",
            "better than", "which is better",
    };

    // Grammatical shapes that signal an intent regardless of vocabulary.
    private static final Pattern FIGURE_RE = Pattern.compile(
            "(?i)(?:what|how)\\s+(?:much|many|old|tall|long|big|large|high|low|"
                    + "heavy|wide|deep|far)\\b"
                    + "|\\bhow\\s+(?:much|many)\\b|\\bwhat\\s+is\\s+.{1,40}?\\s*(?:"
                    + "price|cost|worth|value|population|age|rate|salary|gdp)\\b"
                    + "|\\b(?:price|cost|worth|value|population|rate|salary)\\s+of\\s+\\w+"
                    + "|\\b\\w+'s\\s+(?:price|cost|worth|value|population|age|rate)\\b");
    private static final Pattern PERSON_RE = Pattern.compile(
            "(?i)\\bwho\\s+(?:is|was|are|were)\\b|\\bwho's\\b"
                    + "|\\bwho\\s+(?:invented|created|wrote|founded)\\b"
                    + "|\\bwhat\\s+is\\s+(?:the\\s+real\\s+name\\s+of|the\\s+byname\\s+of)\\b"
                    + "|\\bwhat\\s+is\\s+\\w+\\s+(?:known|famous)\\s+for\\b"
                    + "|\\bwhat\\s+(?:is|are)\\s+\\w+\\s+(?:known\\s+for|the\\s+byname\\s+of)\\b");
    private static final Pattern DEFINITION_RE = Pattern.compile(
            "(?i)\\bwhat\\s+is\\s+(?:a|an|the)\\s+\\w+\\b"
                    + "|\\bwhat\\s+is\\s+\\w+\\b"
                    + "|\\bwhat's\\s+(?:a|an|the)\\b"
                    + "|\\bwhat\\s+does\\s+\\w+\\s+(?:mean|stand\\s+for)\\b"
                    + "|\\bmeaning\\s+of\\b|\\bdefine\\b|\\bdefinition\\s+of\\b");
    private static final Pattern EXPLAIN_RE = Pattern.compile(
            "(?i)\\bwhy\\s+(?:is|are|was|were|does|do|did)\\b"
                    + "|\\bhow\\s+(?:does|do|is|are|did)\\s+\\w+\\s+(?:work|function|happen|form)\\b"
                    + "|\\bwhat\\s+makes\\b|\\bwhat\\s+causes\\b|\\bexplain\\b");
    private static final Pattern COMPARE_RE = Pattern.compile(
            "(?i)\\bcompare\\b|\\bvs\\.?\\b|\\bversus\\b|\\bdifference\\s+between\\b"
                    + "|\\b(?:better|worse|bigger|smaller|faster|heavier|larger|cheaper|more|less)\\s+than\\b"
                    + "|\\bwhich\\s+is\\s+(?:better|best|bigger|faster|heavier|larger|cheaper)\\b");

    /**
     * Classify a question. Form-first; cue-word list as fallback. Never throws.
     */
    public static AnswerIntent classify(String question) {
        if (question == null) return GENERIC;
        String q = question.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return GENERIC;

        // Structural shapes take precedence over a cue list, but only when the
        // shape is unambiguous. "what is the population of x" is a figure; "what
        // is a cloud" is a definition; "who is she" is a person.
        if (FIGURE_RE.matcher(q).find()) return FIGURE;
        if (COMPARE_RE.matcher(q).find()) return COMPARE;
        if (PERSON_RE.matcher(q).find()) return PERSON;
        if (DEFINITION_RE.matcher(q).find()) return DEFINITION;
        if (EXPLAIN_RE.matcher(q).find()) return EXPLAIN;

        // Fallback to the cue-word lists for stable, vocabulary-driven phrasings.
        if (containsAny(q, COMPARE_CUES)) return COMPARE;
        if (containsAny(q, FIGURE_CUES)) return FIGURE;
        if (containsAny(q, PERSON_CUES)) return PERSON;
        if (containsAny(q, EXPLAIN_CUES)) return EXPLAIN;
        if (containsAny(q, DEFINITION_CUES)) return DEFINITION;

        // A bare "X is Y" / "what is X" is a definition; "who " is a person.
        if (q.startsWith("what is ") || q.startsWith("what's ")) return DEFINITION;
        if (q.startsWith("who ")) return PERSON;
        return GENERIC;
    }

    /**
     * Evidence that a sentence actually answers this intent, 0..1.
     *
     * <p>Structural, not just keyword: a FIGURE sentence must carry a standalone
     * number; a PERSON sentence must identify (a "is/was/born/named" or role
     * pattern); a DEFINITION must define ("is a", "refers to"); an EXPLAIN must
     * carry a causal link; a COMPARE must compare. The identifiers/role words
     * below are hints, never the only way to win.
     */
    public double evidence(String sentence) {
        if (sentence == null) return 0;
        String s = sentence.toLowerCase(Locale.ROOT);
        switch (this) {
            case FIGURE:
                if (!hasFigure(sentence)) return 0.2;
                return hasFigureMarker(sentence) ? 1.0 : 0.4;
            case PERSON:
                return identifiesPerson(s) ? 1.0 : 0.2;
            case DEFINITION:
                return containsAny(s, "is a", "is the", "refers to", "is defined as",
                        "means", "describes", "is a term", "is called", "stands for") ? 1.0 : 0.0;
            case EXPLAIN:
                return containsAny(s, "because", "due to", "caused by", "leads to",
                        "which means", "result of", "as a result", "is why", "helps",
                        "allows", "makes it", "so the", "so it", "is caused", "causes",
                        "makes the", "is what", "which is why", "enables", "produces",
                        "results in", "accounts for", "depends on") ? 1.0 : 0.0;
            case COMPARE:
                return containsAny(s, " than ", " vs", "versus", "whereas",
                        "while ", "compared to", "whereas ", "while the", "by contrast") ? 1.0 : 0.0;
            default:
                return 0.5;
        }
    }

    /**
     * True when a sentence identifies a person/thing rather than naming a list
     * of attributes. Recognizes the copula ("is a", "was"), being-born/known/
     * named constructions, and a role or origin clause. A comma-separated
     * "Biography, Age, Career" dump fails this because it has no verb.
     */
    private static boolean identifiesPerson(String s) {
        if (containsAny(s, "is a", "is an", "is the", "was a", "was an", "were",
                "is known for", "is the byname", "is the real name", "is an american",
                "is a british", "grew up", "born", "is named", "is called")) {
            // A role/origin/identity clause following the copula strengthens it,
            // but the construction alone is enough to be an identifying claim.
            return s.matches(".*\\b(is|was|is an|is a)\\b.{3,}.*")
                    || s.matches(".*(youtuber|singer|actor|actress|athlete|founder|"
                            + "artist|entrepreneur|publisher|influencer|streamer|creator|"
                            + "businessman|businesswoman|comedian|musician|scientist|"
                            + "politician|author|director|producer|model|player|cook|"
                            + "chef|designer|engineer|teacher|doctor|lawyer|american|"
                            + "british|nigerian|canadian|indian|french).*");
        }
        return false;
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
