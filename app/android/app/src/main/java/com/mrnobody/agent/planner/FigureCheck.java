package com.mrnobody.agent.planner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that the figures in an answer were actually on the pages that were read.
 *
 * <p>This exists because of a specific, reproduced failure. Asked to track the
 * Bitcoin price, the agent fetched CoinMarketCap, copied the 24-hour low and
 * high correctly — $63,222.65 and $64,547.85, both genuinely on the page — and
 * reported a headline price of <b>$64,235.37</b>, which appears nowhere in the
 * fetched text. The page said $64,282.19. Every existing check passed: the
 * citation marker [1] was well-formed and in range, the hosts named were hosts
 * that had been read, and the answer was therefore "grounded".
 *
 * <p>That is the shape of the problem. {@link AnswerVerifier} validates the
 * <em>frame</em> of an answer — its citations and its hostnames — and a model
 * that copies most of a page and drifts on one number produces a perfectly
 * framed answer with a false fact in it. For a price, a dosage or a date, the
 * number <em>is</em> the answer, so the one thing that was never checked was
 * the only thing that mattered.
 *
 * <p><b>What this does.</b> It pulls the significant figures out of an answer
 * and asks whether each appears in the source text. Not whether the answer
 * looks well cited: whether the number is there.
 *
 * <p><b>What it deliberately does not do.</b> It does not attempt arithmetic,
 * units, or whether a derived figure is correctly derived. A model that adds
 * two numbers from a page legitimately produces a third that is not on it. So
 * an unsupported figure is reported, never silently removed, and the reader is
 * told which one — a wrong warning wastes a moment, and a wrong deletion
 * destroys a correct answer.
 *
 * <p>Pure Java: no Android, no network, testable on a JVM.
 */
public final class FigureCheck {

    /**
     * A number worth checking.
     *
     * <p>Requires at least two digits, so ordinary prose ("three of the five",
     * "top 5") does not generate noise. Allows thousands separators and
     * decimals, which is the form prices and measurements take.
     */
    private static final Pattern FIGURE = Pattern.compile(
            "\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?"   // 64,235.37  1,280
                    + "|\\d+\\.\\d+"             // 64235.37   1.18
                    + "|\\d{2,}");               // 2026       64235

    /**
     * Figures that are almost never a claim about the world.
     *
     * <p>Citation markers are checked by {@link AnswerVerifier}; re-reporting
     * them here would flag the very mechanism that makes an answer checkable.
     */
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[\\d{1,2}]");

    /** How many unsupported figures to name before saying "and others". */
    private static final int MAX_REPORTED = 4;

    public static final class Report {
        /** Figures in the answer that were not found in any source. */
        public final List<String> unsupported;
        /** How many figures were checked at all. */
        public final int checked;

        Report(List<String> unsupported, int checked) {
            this.unsupported = unsupported;
            this.checked = checked;
        }

        public boolean hasProblems() {
            return !unsupported.isEmpty();
        }
    }

    private FigureCheck() {
    }

    /**
     * @param answer  what the model wrote
     * @param sources the concatenated text of every page that was read
     */
    public static Report check(String answer, String sources) {
        List<String> unsupported = new ArrayList<>();
        if (answer == null || answer.trim().isEmpty()) {
            return new Report(unsupported, 0);
        }

        // Citation markers are a different mechanism, checked elsewhere.
        String prose = CITATION_MARKER.matcher(answer).replaceAll(" ");
        String haystack = normalise(sources == null ? "" : sources);

        Set<String> seen = new LinkedHashSet<>();
        int checked = 0;

        Matcher m = FIGURE.matcher(prose);
        while (m.find()) {
            String raw = m.group();
            if (!seen.add(raw)) continue;
            checked++;
            if (!appearsIn(haystack, raw)) unsupported.add(raw);
        }
        return new Report(unsupported, checked);
    }

    /**
     * Whether {@code figure} occurs in the source text.
     *
     * <p>Compared without separators so that a page writing {@code 64282.19}
     * and an answer writing {@code 64,282.19} agree — they are the same
     * number, and treating a comma as a difference would flag every correctly
     * copied price.
     *
     * <p>A trailing {@code .0} is also tolerated in the same direction: a page
     * saying {@code 1280} supports an answer saying {@code 1280.0}. It is the
     * same quantity written to a different precision, not a new claim.
     */
    private static boolean appearsIn(String normalisedSources, String figure) {
        String needle = normalise(figure);
        if (needle.isEmpty()) return true;
        if (normalisedSources.contains(needle)) return true;

        // 1280.0 -> 1280, then look again.
        String trimmed = needle;
        if (trimmed.contains(".")) {
            trimmed = trimmed.replaceAll("0+$", "");
            trimmed = trimmed.endsWith(".")
                    ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
            if (!trimmed.isEmpty() && normalisedSources.contains(trimmed)) return true;
        }
        return false;
    }

    /** Lowercase, and drop the separators that are formatting rather than value. */
    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
    }

    /**
     * A note for the reader naming the figures that could not be found.
     *
     * <p>Names them explicitly. "Some figures could not be verified" tells a
     * reader to distrust the whole answer, which is both unhelpful and usually
     * wrong; naming the one number that is unsupported lets them check that
     * number and keep the rest.
     */
    public static String note(Report report) {
        if (report == null || !report.hasProblems()) return "";

        List<String> shown = report.unsupported.size() <= MAX_REPORTED
                ? report.unsupported
                : report.unsupported.subList(0, MAX_REPORTED);

        StringBuilder sb = new StringBuilder("⚠︎ ");
        sb.append(report.unsupported.size() == 1
                ? "This figure does not appear on the pages that were read: "
                : "These figures do not appear on the pages that were read: ");
        sb.append(String.join(", ", shown));
        if (report.unsupported.size() > shown.size()) {
            sb.append(", and ").append(report.unsupported.size() - shown.size())
                    .append(" more");
        }
        sb.append(". They may be worked out from the sources, or they may be "
                + "wrong — check them before relying on them.");
        return sb.toString();
    }
}
