package com.mrnobody.agent.util;

/**
 * One parsed search result: title, display URL and a short snippet.
 * Pure Java (unit-testable); produced by {@link DdgHtmlParser}.
 */
public final class SearchResult {
    public final String title;
    public final String url;
    public final String snippet;

    public SearchResult(String title, String url, String snippet) {
        this.title = title == null ? "" : title;
        this.url = url == null ? "" : url;
        this.snippet = snippet == null ? "" : snippet;
    }
}
