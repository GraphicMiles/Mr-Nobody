package com.mrnobody.agent.planner;

import com.mrnobody.agent.util.SearchResultsJson;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an answer against the sources it was given.
 *
 * <p>A model handed five snippets and asked for "the best Chinese restaurants
 * in Lagos" will produce a confident table of five restaurants with four
 * citations, some of which it invented. Nothing in the pipeline noticed,
 * because nothing was looking: the answer was returned exactly as written.
 *
 * <p>This looks. It cannot judge whether a claim is true — only whether the
 * answer is anchored to material that was actually fetched, and whether it
 * points at anywhere that was not. Both are cheap, and both are things a
 * reader deserves to be told.
 */
public final class AnswerVerifier {

    /** Bracketed source markers: [1], [2] — what the prompt asks for. */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2})]");

    /** Bare URLs and hostnames appearing in prose. */
    private static final Pattern URL_LIKE = Pattern.compile(
            "\\bhttps?://[^\\s)\\]},\"']+|\\b([a-z0-9-]+\\.)+(com|org|net|ng|io|co|uk|info|news)\\b",
            Pattern.CASE_INSENSITIVE);

    public static final class Report {
        /** Source numbers the answer referred to. */
        public final List<Integer> citations;
        /** Hosts named in the answer that were not among the sources. */
        public final List<String> unsupportedHosts;
        /** True when the answer anchors to at least one source that was read. */
        public final boolean grounded;

        Report(List<Integer> citations, List<String> unsupportedHosts, boolean grounded) {
            this.citations = citations;
            this.unsupportedHosts = unsupportedHosts;
            this.grounded = grounded;
        }

        public boolean hasProblems() {
            return !grounded || !unsupportedHosts.isEmpty();
        }
    }

    private AnswerVerifier() {
    }

    /**
     * @param answer     what the model wrote
     * @param sourceUrls the URLs actually fetched, in the order they were shown
     */
    public static Report check(String answer, List<String> sourceUrls) {
        List<Integer> citations = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        if (answer == null || answer.trim().isEmpty()) {
            return new Report(citations, unsupported, false);
        }

        Set<String> allowedHosts = new LinkedHashSet<>();
        for (String url : sourceUrls) {
            String host = SearchResultsJson.hostOf(url);
            if (!host.isEmpty()) allowedHosts.add(host);
        }

        Matcher citation = CITATION.matcher(answer);
        while (citation.find()) {
            int n = Integer.parseInt(citation.group(1));
            if (n >= 1 && n <= sourceUrls.size() && !citations.contains(n)) citations.add(n);
        }

        Set<String> seen = new LinkedHashSet<>();
        Matcher urls = URL_LIKE.matcher(answer);
        while (urls.find()) {
            String found = urls.group();
            String host = found.startsWith("http")
                    ? SearchResultsJson.hostOf(found)
                    : found.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            if (host.isEmpty()) continue;
            if (allowedHosts.contains(host)) continue;
            if (seen.add(host)) unsupported.add(host);
        }

        boolean grounded = !citations.isEmpty()
                || mentionsAnyAllowedHost(answer, allowedHosts);
        return new Report(citations, unsupported, grounded);
    }

    private static boolean mentionsAnyAllowedHost(String answer, Set<String> allowedHosts) {
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String host : allowedHosts) {
            if (lower.contains(host)) return true;
        }
        return false;
    }

    /**
     * A note appended to an answer that could not be verified. Written for the
     * reader, not the model: it says what was checked and what was not, so an
     * unverified answer is visibly unverified rather than quietly wrong.
     */
    public static String note(Report report, List<String> sourceUrls) {
        StringBuilder sb = new StringBuilder();
        if (!report.grounded) {
            sb.append("⚠︎ This answer does not cite any of the pages that were read. ")
                    .append("Treat it as the model's own recollection, not as research.");
        }
        if (!report.unsupportedHosts.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("⚠︎ It refers to ")
                    .append(String.join(", ", report.unsupportedHosts))
                    .append(", which ")
                    .append(report.unsupportedHosts.size() == 1 ? "was" : "were")
                    .append(" not among the pages read.");
        }
        if (!sourceUrls.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("\nSources actually read:");
            for (int i = 0; i < sourceUrls.size(); i++) {
                sb.append("\n[").append(i + 1).append("] ").append(sourceUrls.get(i));
            }
        }
        return sb.toString();
    }
}
