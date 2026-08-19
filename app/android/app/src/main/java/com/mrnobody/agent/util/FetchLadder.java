package com.mrnobody.agent.util;

/**
 * HTTP first, browser only if the document says so.
 *
 * <p>Scrapling's fetcher tree and the web-scraper Cheerio-vs-browser test
 * are the same rule: a static page must not pay for a WebView, and a
 * challenge or SPA must not be treated as an empty answer.
 */
public final class FetchLadder {

    public enum Step { HTTP, BROWSER }

    private FetchLadder() {
    }

    public static Step afterHttp(PageKind.Kind kind) {
        if (kind == null) return Step.HTTP;
        return kind.needsBrowser() ? Step.BROWSER : Step.HTTP;
    }

    /**
     * Where to start for a host we have seen before. Scrapling remembers
     * the last fetcher; two challenge/SPA hits in a row skip HTTP.
     */
    public static Step firstStep(String host) {
        return SiteMemory.preferBrowser(host) ? Step.BROWSER : Step.HTTP;
    }

    /** True when an HTTP result should be thrown away and the page re-read. */
    public static boolean escalate(ToolHint hint) {
        return hint != null && hint.needsBrowser;
    }

    /** What HttpTool reports alongside the text. */
    public static final class ToolHint {
        public final PageKind.Kind kind;
        public final boolean needsBrowser;

        public ToolHint(PageKind.Kind kind) {
            this.kind = kind == null ? PageKind.Kind.STATIC : kind;
            this.needsBrowser = this.kind.needsBrowser();
        }
    }
}
