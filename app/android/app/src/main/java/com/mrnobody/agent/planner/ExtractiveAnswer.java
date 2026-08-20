package com.mrnobody.agent.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The local (no-model) answer: extractive, cited, and honest.
 *
 * <p>This is not an AI agent. There is no on-device model. The previous local
 * path dumped the search listing and the UI treated that dump as a reasoned
 * answer. This class only rearranges text that was actually read, cites it,
 * and says so.
 */
public final class ExtractiveAnswer {

    private static final int MAX_SENTENCES = 8;
    private static final int MAX_CHARS = 2800;

    private ExtractiveAnswer() {
    }

    /**
     * @param question  what the user asked
     * @param sources   numbered source block ({@code [1] title\\nurl\\ntext})
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
        List<Source> parsed = parseSources(sources);
        StringBuilder out = new StringBuilder();
        out.append("# ").append(heading(question)).append("\n\n");

        int used = 0;
        for (Source src : parsed) {
            List<String> picked = pick(question, src.body, 3);
            if (picked.isEmpty()) continue;
            if (used > 0) out.append("\n");
            for (String sentence : picked) {
                out.append(sentence.trim());
                if (!sentence.trim().endsWith(".")) out.append('.');
                out.append(" [").append(src.number).append("]\n");
                used++;
                if (used >= MAX_SENTENCES) break;
            }
            if (used >= MAX_SENTENCES) break;
        }

        if (used == 0) {
            // Pages were read but nothing matched the question. Quote the
            // opening of each page rather than inventing a summary.
            for (Source src : parsed) {
                String excerpt = firstSentences(src.body, 2);
                if (excerpt.isEmpty()) continue;
                out.append(excerpt);
                if (!excerpt.endsWith(".")) out.append('.');
                out.append(" [").append(src.number).append("]\n");
            }
        }

        out.append("\nExtracted from the pages read. No language model was used.");
        String text = out.toString().trim();
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
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
        List<String> terms = termsOf(question);
        String[] sentences = splitSentences(body);
        int[] scores = new int[sentences.length];
        int best = 0;
        for (int i = 0; i < sentences.length; i++) {
            scores[i] = score(sentences[i], terms);
            if (scores[i] > best) best = scores[i];
        }
        if (best <= 0) return out;
        for (int i = 0; i < sentences.length && out.size() < max; i++) {
            if (scores[i] >= Math.max(1, best / 2) && sentences[i].length() >= 40) {
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

    private static int score(String sentence, List<String> terms) {
        if (sentence == null || terms.isEmpty()) return 0;
        String lower = sentence.toLowerCase(Locale.ROOT);
        int n = 0;
        for (String t : terms) {
            if (lower.contains(t)) n++;
        }
        return n;
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
            if (p.trim().length() < 20) continue;
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

    private static String trimAt(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static final java.util.Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "and", "for", "that", "this", "with", "from", "what", "when",
            "where", "which", "who", "how", "are", "was", "were", "you", "your",
            "about", "into", "just", "can", "could", "would", "should", "have",
            "has", "had", "not", "but", "its", "they", "them", "their", "open",
            "one", "second", "first", "third", "please", "tell", "find"));
}
