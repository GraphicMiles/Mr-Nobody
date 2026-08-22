package com.mrnobody.agent.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The local (no-model) answer: extractive, cited, and honest.
 *
 * <p>This is not an AI agent. There is no on-device model. It only rearranges
 * text that was actually read, cites it, and says so.
 *
 * <p>Answer quality is a ranking problem, not a formatting one. The old scorer
 * matched query words and dumped the top hits as equal-weight sentences, so a
 * "what is the price" question answered with a sentence about hashing, and a
 * "who is X" question quoted a comma-separated metadata dump. This composer:
 *
 * <ol>
 *   <li>classifies what the question wants ({@link AnswerIntent}),</li>
 *   <li>rejects keyword/metadata dumps and menu rails before ranking,</li>
 *   <li>scores each candidate on how well it answers <em>that</em> intent, not
 *       how many query words it happens to contain,</li>
 *   <li>picks a lead sentence plus only factually distinct supporting ones,</li>
 *   <li>renders them as a structured answer: a lead, then a short key-facts
 *       list, with the key element bolded.</li>
 * </ol>
 */
public final class ExtractiveAnswer {

    private static final int MAX_SENTENCES = 9;
    private static final int MAX_LEAD = 3;         // supporting facts beyond the lead
    private static final int MAX_CHARS = 2800;

    private ExtractiveAnswer() {
    }

    /**
     * @param question  what the user asked
     * @param sources   numbered source block ({@code [1] title\nurl\ntext})
     * @param pagesRead true when whole pages were fetched, not just snippets
     * @param results   parsed search rows, used only when no page was read
     */
    public static String compose(String question, String sources, boolean pagesRead,
                                 List<Map<String, Object>> results) {
        if (pagesRead && sources != null && !sources.trim().isEmpty()) {
            return fromPages(question, sources);
        }
        if (results != null && !results.isEmpty()) {
            return fromListings(results);
        }
        if (sources != null && !sources.trim().isEmpty()) {
            return fromPages(question, sources);
        }
        return "Nothing was read, so there is no answer.";
    }

    static String fromPages(String question, String sources) {
        AnswerIntent intent = AnswerIntent.classify(question);
        List<Source> parsed = dedupeBodies(parseSources(sources));

        // Score every candidate sentence across every source. A "candidate" is
        // one sentence that passed the prose gate, with an intent-aware score
        // and the source it came from.
        List<Candidate> candidates = new ArrayList<>();
        List<Source> used = new ArrayList<>();
        for (Source src : parsed) {
            for (String sentence : splitSentences(src.body)) {
                String clean = sentence.trim();
                if (!com.mrnobody.agent.util.ReadableText.proseSentence(clean)) continue;
                double score = score(clean, question, intent);
                if (score <= 0) continue;
                candidates.add(new Candidate(clean, src.number, score));
            }
            used.add(src);
        }

        // Rank by answer-relevance and drop verbatim repeats (the same sentence
        // echoed by a mirror page, a "key facts" box, or an identical excerpt).
        candidates.sort((a, b) -> Double.compare(b.score, a.score));
        List<Candidate> picked = new ArrayList<>();
        for (Candidate c : candidates) {
            if (picked.size() >= MAX_SENTENCES) break;
            if (isDupeOfAny(c.sentence, picked)) continue;
            picked.add(c);
        }

        // Nothing ranked: quote the opening of each source so the user at
        // least sees what was read, rather than inventing a summary.
        if (picked.isEmpty()) {
            return fallback(question, parsesOf(parsed));
        }

        StringBuilder out = new StringBuilder();
        out.append("# ").append(heading(question)).append("\n\n");

        // The lead directly answers the intent. Keep it a single, bolded
        // sentence.
        Candidate lead = picked.get(0);
        out.append(bold(lead.sentence, question, intent))
                .append(" [").append(lead.source).append("]");

        // Additional facts, only if distinct from the lead and relevant to the
        // intent. A supporting fact for a price query must carry an actual
        // amount, so "bitcoin is secured with SHA-256" and other tangential
        // sentences never fill the key-facts list as noise.
        int usedFacts = 0;
        for (int i = 1; i < picked.size() && usedFacts < MAX_LEAD; i++) {
            Candidate c = picked.get(i);
            if (!supports(c.sentence, question, intent)) continue;
            if (usedFacts == 0) out.append("\n\n**Key facts**");
            out.append("\n- ").append(bold(c.sentence, question, intent))
                    .append(" [").append(c.source).append("]");
            usedFacts++;
        }

        out.append("\n\nExtracted from the pages read. No language model was used.");
        String text = out.toString().trim();
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }

    // --------------------------------------------------------------- ranking

    /**
     * Score a sentence against the question and its intent. The intent weight
     * dominates, so an answer that <em>answers</em> beats one that merely
     * mentions a topic.
     */
    private static double score(String sentence, String question, AnswerIntent intent) {
        String lower = sentence.toLowerCase(Locale.ROOT);
        double intentScore = intent.evidence(sentence);

        // Term overlap with the question's content words.
        List<String> terms = termsOf(question);
        int hit = 0;
        for (String t : terms) {
            if (lower.contains(t)) hit++;
        }
        double overlap = terms.isEmpty() ? 0 : (double) hit / terms.size();

        // A sentence that pins the subject and the intent together is worth the
        // most. Figure sentences need a figure; person sentences need an
        // identity; a definition needs "is a".
        double score = 5.0 * intentScore + 3.0 * overlap;

        // Long, scattershot "sentences" (a whole page glued together) are less
        // likely to be a clean, standalone claim.
        if (sentence.length() > 400) score -= 1.0;
        if (sentence.length() < 40) score -= 0.5;
        // A title-like fragment with no verb is a label, not a claim — only
        // used as a tiebreaker, never as a hard reject.
        if (!com.mrnobody.agent.util.ReadableText.hasFiniteVerb(lower)) score -= 1.0;
        return score;
    }

    /**
     * True when a supporting fact is genuinely relevant to the intent. A figure
     * answer's supporting facts must carry an amount; a definition's must name
     * the topic; otherwise we just repeat the lead or cite noise.
     */
    private static boolean supports(String sentence, String question, AnswerIntent intent) {
        String lower = sentence.toLowerCase(Locale.ROOT);
        switch (intent) {
            case FIGURE:
                return hasFigureMarker(lower);
            case PERSON:
            case DEFINITION:
            case EXPLAIN:
            case COMPARE:
                return anyTerm(sentence, termsOf(question));
            default:
                return true;
        }
    }

    /** A supporting sentence that names the question's subject (any term). */
    private static boolean anyTerm(String sentence, List<String> terms) {
        if (terms.isEmpty()) return true;
        String lower = sentence.toLowerCase(Locale.ROOT);
        for (String t : terms) {
            if (lower.contains(t)) return true;
        }
        return false;
    }

    /** True when the sentence carries a currency/unit-marked amount. */
    static boolean hasFigureMarker(String lower) {
        if (lower == null) return false;
        String[] markers = {"$", "\u20ac", "\u00a3", " usd", " percent", "%", " dollars",
                " years old", " million", " billion", " trillion", " per ",
                " rupees", " naira"};
        for (String m : markers) {
            if (lower.contains(m)) return true;
        }
        return false;
    }

    /**
     * True when {@code s} restates a fact already chosen. Two distinct sources
     * about one topic are <em>not</em> duplicates — only a sentence that is
     * verbatim (after case/punctuation normalisation) is. This keeps source 2
     * citeable while mirrors and repeated boilerplate collapse to one.
     */
    private static boolean isDupeOfAny(String s, List<Candidate> chosen) {
        String normalized = normalise(s);
        if (normalized.length() < 40) return false;
        for (Candidate c : chosen) {
            if (c.sentence.length() < 40) continue;
            if (normalise(c.sentence).equals(normalized)) return true;
        }
        return false;
    }

    /** Put ** around a figure amount in a figure answer (the key fact). */
    private static String bold(String sentence, String question, AnswerIntent intent) {
        if (intent != AnswerIntent.FIGURE) return sentence;
        Matcher f = FIGURE_RUN.matcher(sentence);
        if (f.find()) {
            return wrap(sentence, f.start(), f.end());
        }
        return sentence;
    }

    private static String wrap(String s, int start, int end) {
        if (start < 0 || end > s.length() || start >= end) return s;
        return s.substring(0, start) + "**" + s.substring(start, end) + "**" + s.substring(end);
    }

    // A figure worth bolding carries a currency symbol or an explicit unit.
    // A bare number inside an identifier ("SHA-256", "Web3", "5G") is matched by
    // neither alternative, so it is never highlighted.
    private static final Pattern FIGURE_RUN = Pattern.compile(
            "(?i)(?:\\$|€|£)\\s?\\d[\\d,]*(?:\\.\\d+)?"
                    + "|\\d[\\d,]*(?:\\.\\d+)?\\s?(?:%|k|m|bn|mn|billion|million|"
                    + "thousand|dollars|usd|years old|naira|rupees)");

    // ---------------------------------------------------------------- format

    private static String fallback(String question, List<Source> parsed) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(heading(question)).append("\n\n");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Source src : parsed) {
            for (String excerpt : firstSentences(src.body, 2).split("\n")) {
                if (excerpt.trim().isEmpty()) continue;
                if (!seen.add(normalise(excerpt))) continue;
                out.append(excerpt.trim()).append(" [").append(src.number).append("]\n");
            }
        }
        if (out.length() == 0) {
            return "# " + heading(question) + "\n\nThe pages read did not contain a clear answer.\n\n"
                    + "Extracted from the pages read. No language model was used.";
        }
        out.append("\n\nExtracted from the pages read. No language model was used.");
        String text = out.toString().trim();
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }

    private static List<Source> parsesOf(List<Source> parsed) {
        return parsed;
    }

    /**
     * Drop sources whose text duplicates an earlier source. Multi-URL
     * mirrors of one document (one-page/multipage/dev builds of a spec, AMP
     * and canonical variants) otherwise inflate the source count and repeat
     * the same extraction under different citation numbers.
     */
    static List<Source> dedupeBodies(List<Source> parsed) {
        List<Source> out = new ArrayList<>();
        java.util.Set<String> prefixes = new java.util.HashSet<>();
        for (Source src : parsed) {
            String key = normalise(src.body);
            key = key.substring(0, Math.min(key.length(), 240));
            if (key.length() >= 40 && !prefixes.add(key)) continue;
            out.add(src);
        }
        return out;
    }

    /** Case/punctuation-insensitive key for sentence and body dedupe. */
    static String normalise(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    static String fromListings(List<Map<String, Object>> results) {
        StringBuilder out = new StringBuilder();
        out.append("# Sources found\n\n");
        out.append("The pages themselves could not be read. These are search listings, ");
        out.append("not an answer.\n");
        int n = 1;
        for (Map<String, Object> row : results) {
            if (n > 6) break;
            String title = String.valueOf(row.getOrDefault("title", "")).trim();
            String url = String.valueOf(row.getOrDefault("url", "")).trim();
            String snippet = String.valueOf(row.getOrDefault("snippet", "")).trim();
            if (url.isEmpty() || "null".equals(url)) continue;
            out.append("\n").append(n).append(". ");
            out.append(title.isEmpty() ? url : title);
            if (!snippet.isEmpty()) out.append(" — ").append(trimAt(snippet, 180));
            out.append(" [").append(n).append("]\n");
            n++;
        }
        out.append("\nNo language model was used.");
        return out.toString().trim();
    }

    static String heading(String question) {
        if (question == null || question.trim().isEmpty()) return "What was found";
        String q = question.trim();
        // Follow-up annotations are not the heading.
        int cut = q.indexOf("\n\nThe user is referring");
        if (cut > 0) q = q.substring(0, cut).trim();
        int follow = q.toLowerCase(Locale.ROOT).indexOf("\n\nfollow-up from the user:");
        if (follow > 0) {
            String extra = q.substring(follow + "\n\nFollow-up from the user:".length()).trim();
            if (!extra.isEmpty()) q = extra;
        }
        // Operational constraints belong to the plan, not the answer title.
        q = q.replaceFirst("(?i)^research\\s+", "").trim();
        Matcher directive = Pattern.compile(
                "(?i)(?:[.!?]\\s+|\\s+)(?:use at least|include citations|and include|"
                        + "with citations|cite sources).*$").matcher(q);
        if (directive.find()) q = q.substring(0, directive.start()).trim();
        if (q.length() > 80) q = q.substring(0, 77) + "…";
        if (!q.isEmpty()) q = Character.toUpperCase(q.charAt(0)) + q.substring(1);
        return q;
    }

    static List<String> pick(String question, String body, int max) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        AnswerIntent intent = AnswerIntent.classify(question);
        List<String> terms = termsOf(question);
        String[] sentences = splitSentences(body);
        int[] scores = new int[sentences.length];
        int best = 0;
        for (int i = 0; i < sentences.length; i++) {
            if (!com.mrnobody.agent.util.ReadableText.proseSentence(sentences[i])) {
                scores[i] = 0;
                continue;
            }
            scores[i] = (int) Math.round(score(sentences[i], question, intent) * 10);
            if (scores[i] > best) best = scores[i];
        }
        if (best <= 0) return out;
        for (int i = 0; i < sentences.length && out.size() < max; i++) {
            if (scores[i] >= Math.max(5, best / 2) && sentences[i].length() >= 40) {
                out.add(sentences[i].trim());
            }
        }
        return out;
    }

    static List<String> termsOf(String question) {
        List<String> out = new ArrayList<>();
        if (question == null) return out;
        String[] words = question.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (String w : words) {
            if (w.length() < 3) continue;
            if (STOP.contains(w)) continue;
            out.add(w);
        }
        return out;
    }

    static String[] splitSentences(String text) {
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.split("(?<=[.!?])\\s+");
    }

    static String firstSentences(String body, int n) {
        if (body == null) return "";
        String[] parts = splitSentences(body);
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String p : parts) {
            if (!com.mrnobody.agent.util.ReadableText.proseSentence(p)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.trim());
            if (++used >= n) break;
        }
        return sb.toString();
    }

    private static final Pattern SOURCE = Pattern.compile(
            "\\[(\\d+)]\\s*(.*)\\n(\\S+)\\n([\\s\\S]*?)(?=\\n\\[\\d+]\\s|$)");

    static List<Source> parseSources(String sources) {
        List<Source> out = new ArrayList<>();
        if (sources == null) return out;
        Matcher m = SOURCE.matcher(sources);
        while (m.find()) {
            out.add(new Source(
                    Integer.parseInt(m.group(1)),
                    m.group(2).trim(),
                    m.group(3).trim(),
                    m.group(4).trim()));
        }
        return out;
    }

    static final class Source {
        final int number;
        final String title;
        final String url;
        final String body;

        Source(int number, String title, String url, String body) {
            this.number = number;
            this.title = title;
            this.url = url;
            this.body = body;
        }
    }

    private static final class Candidate {
        final String sentence;
        final int source;
        final double score;

        Candidate(String sentence, int source, double score) {
            this.sentence = sentence;
            this.source = source;
            this.score = score;
        }
    }

    private static String trimAt(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static final java.util.Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "and", "for", "that", "this", "with", "from", "what", "when",
            "where", "which", "who", "how", "are", "was", "were", "you", "your",
            "about", "into", "just", "can", "could", "would", "should", "have",
            "has", "had", "not", "but", "its", "they", "them", "their", "open",
            "one", "second", "first", "third", "please", "tell", "find",
            "why", "is", "it", "to", "in", "on", "of", "as", "at", "by",
            "will", "be", "or", "an", "if", "so", "do", "does", "did", "then",
            "also", "there", "here", "more", "some", "than", "very", "only",
            "both", "each", "such", "much", "many", "most", "other", "same",
            "still", "through", "during", "while", "because", "since", "after",
            "before", "between", "under", "over", "again", "further", "once"));
}
