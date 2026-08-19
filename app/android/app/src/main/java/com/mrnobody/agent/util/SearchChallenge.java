package com.mrnobody.agent.util;

import java.util.Locale;

/**
 * Recognises a search provider refusing to answer.
 *
 * <p>DuckDuckGo's HTML endpoint returns HTTP 200 with an anti-bot challenge
 * page when it does not like a request. To a parser that page looks like a
 * results page with no results, and "no results" reads as "nothing exists" —
 * which is how a task ends up handing an empty context to a model and getting
 * a confident, invented answer back.
 *
 * <p>Being challenged and finding nothing are different facts, and the agent
 * has to be able to tell the user which one happened.
 */
public final class SearchChallenge {

    private static final String[] MARKERS = {
            "anomaly",                 // DDG's own wording
            "unusual traffic",
            "are you a robot",
            "verify you are human",
            "captcha",
            "challenge-platform",      // Cloudflare
            "cf-browser-verification",
            "just a moment...",
            "attention required! | cloudflare",
            "performing security verification",
            "cdn-cgi/challenge-platform",
            "gokuprops",               // AWS WAF
            "awswafcookiedomainlist",
            "cf-chl-bypass",
            "access denied",
            "rate limit",
    };

    private SearchChallenge() {
    }

    /**
     * True when the page is a block/challenge rather than results. Only applied
     * to pages that yielded no results, so a page that legitimately discusses
     * captchas is never mistaken for one.
     */
    public static boolean isChallenge(String html) {
        if (html == null || html.isEmpty()) return false;
        String head = html.substring(0, Math.min(html.length(), 20_000))
                .toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (head.contains(marker)) return true;
        }
        return false;
    }

    /** What to tell the user, in their terms rather than the provider's. */
    public static String message(String provider) {
        return "The search provider (" + provider + ") blocked this request instead of "
                + "answering it. Nothing was searched, so nothing is being guessed — "
                + "try again shortly, or browse the web directly.";
    }
}
