package com.mrnobody.agent.util;

import java.util.Locale;

/**
 * What a raw HTTP body actually is.
 *
 * <p>A pre-read classification gate in the spirit of tiered-fetcher
 * tree: decide HTTP vs browser from the document, not from a site list.
 * A challenge page that returns 200 is not a successful read. A Next.js
 * dump is not empty just because the visible DOM is a spinner.
 */
public final class PageKind {

    public enum Kind {
        CHALLENGE,
        FEED,
        EMBEDDED_JSON,
        SPA,
        THIN,
        STATIC;

        /** True when a headless WebView should try next. */
        public boolean needsBrowser() {
            return this == CHALLENGE || this == SPA || this == THIN;
        }
    }

    private PageKind() {
    }

    public static Kind classify(String html) {
        if (html == null || html.isEmpty()) return Kind.THIN;
        String head = html.substring(0, Math.min(html.length(), 24_000))
                .toLowerCase(Locale.ROOT);

        if (SearchChallenge.isChallenge(html)) return Kind.CHALLENGE;
        if (looksLikeFeed(head)) return Kind.FEED;
        if (hasEmbeddedJson(html)) return Kind.EMBEDDED_JSON;
        if (isSpaShell(head, html)) return Kind.SPA;

        String text = HtmlText.toText(html);
        if (text.length() < 80 && html.length() > 1500) return Kind.THIN;
        return Kind.STATIC;
    }

    private static boolean looksLikeFeed(String head) {
        return head.contains("<rss") || head.contains("<feed")
                || head.contains("<rdf:rdf")
                || (head.contains("<channel") && head.contains("<item"));
    }

    private static boolean hasEmbeddedJson(String html) {
        return html.contains("__NEXT_DATA__")
                || html.contains("__NUXT_DATA__")
                || html.contains("window.__NUXT__")
                || html.contains("__INITIAL_STATE__")
                || html.contains("application/ld+json");
    }

    private static boolean isSpaShell(String head, String html) {
        boolean spaMarker = head.contains("data-reactroot")
                || head.contains("ng-version=")
                || head.contains("id=\"root\"")
                || head.contains("id='root'")
                || head.contains("id=\"app\"")
                || head.contains("_sveltekit");
        if (!spaMarker) return false;
        // SSR that already shipped the article is STATIC, not SPA.
        String text = HtmlText.toText(html);
        return text.length() < 200;
    }
}
