package com.mrnobody.agent.browser;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses a page action when the page is no longer the one that was read.
 *
 * <p>The agent reads a page, decides to click something, and clicks it a
 * moment later. In between the page can navigate, re-render, or swap its
 * contents under an ad refresh — and a selector that matched the old document
 * will happily match something else in the new one. The click still
 * "succeeds", on the wrong thing. Refusing is recoverable, because the agent
 * can re-read and decide again; clicking the wrong element is not.
 *
 * <p><b>Why word overlap rather than a hash.</b> The obvious implementation is
 * a hash of the page text, and it is wrong: a hash changes completely when one
 * character does, so a clock ticking or an ad rotating reads as "the page was
 * replaced". A guard that refuses constantly on the real web is a guard people
 * switch off, which leaves them worse protected than a lenient one. So this
 * measures how much of what the agent read is <em>still there</em>: additions
 * are free, and only losing a large fraction of the original content counts as
 * a different page.
 *
 * <p>Word sets are bounded, so a long page cannot make anchoring expensive.
 * This defends against drift, not against an attacker — someone who controls
 * the page already controlled what the agent read.
 */
public final class PageAnchor {

    /**
     * How much of the original wording must survive.
     *
     * <p>0.75 rather than 0.95: pages legitimately drop cookie banners, lazy
     * sections and rotating panels between a read and a click. Losing a
     * quarter of the words is drift; losing half is a different document.
     */
    private static final double MIN_RETAINED = 0.75;

    /** Cap on remembered words, so a huge page stays cheap to anchor. */
    private static final int MAX_WORDS = 2_000;

    private final String url;
    private final Set<String> words;

    private PageAnchor(String url, Set<String> words) {
        this.url = url;
        this.words = words;
    }

    /** Capture the state a decision is about to be based on. */
    public static PageAnchor of(String url, String text) {
        return new PageAnchor(normalise(url), wordsOf(text));
    }

    public String url() {
        return url;
    }

    /**
     * Why this anchor no longer matches, or null when it still does.
     *
     * @param currentUrl  where the browser is now
     * @param currentText what it says now
     */
    public String staleReason(String currentUrl, String currentText) {
        if (currentUrl == null || currentText == null) {
            return "the page could not be re-read before the action could run";
        }

        String now = normalise(currentUrl);
        if (!url.equals(now)) {
            return "the page moved from " + url + " to " + now
                    + " before the action could run";
        }

        // An empty original cannot be compared meaningfully; treat any
        // content as a change rather than silently approving.
        if (words.isEmpty()) {
            return wordsOf(currentText).isEmpty()
                    ? null
                    : "the page contents changed before the action could run";
        }

        Set<String> current = wordsOf(currentText);
        int retained = 0;
        for (String w : words) {
            if (current.contains(w)) retained++;
        }

        double fraction = (double) retained / words.size();
        if (fraction < MIN_RETAINED) {
            return "the page contents changed before the action could run";
        }
        return null;
    }

    public boolean matches(String currentUrl, String currentText) {
        return staleReason(currentUrl, currentText) == null;
    }

    /**
     * Distinct words, lowercased.
     *
     * <p>Case and spacing are normalised so reflow and styling changes do not
     * read as a different document — the agent decided on the basis of the
     * words, not the whitespace.
     */
    private static Set<String> wordsOf(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String token : text.toLowerCase(Locale.ROOT).split("\\s+")) {
            String w = token.trim();
            if (w.isEmpty()) continue;
            out.add(w);
            if (out.size() >= MAX_WORDS) break;
        }
        return out;
    }

    /** Ignore trailing slashes and fragments, which do not change the document. */
    private static String normalise(String url) {
        if (url == null) return "";
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
