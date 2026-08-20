package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the bounded RSS fallback returned by Bing search. */
public final class SearchFeedParser {

    private static final Pattern ITEM = Pattern.compile(
            "(?is)<item\\b[^>]*>(.*?)</item>");
    private static final Pattern TITLE = Pattern.compile(
            "(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern LINK = Pattern.compile(
            "(?is)<link[^>]*>(.*?)</link>");
    private static final Pattern DESCRIPTION = Pattern.compile(
            "(?is)<description[^>]*>(.*?)</description>");

    private SearchFeedParser() {
    }

    public static List<SearchResult> parse(String xml, int max) {
        List<SearchResult> out = new ArrayList<>();
        if (xml == null || xml.isEmpty() || max <= 0) return out;
        Set<String> hosts = new HashSet<>();
        Matcher items = ITEM.matcher(xml);
        while (items.find() && out.size() < max) {
            String item = items.group(1);
            String title = text(first(TITLE, item));
            String url = text(first(LINK, item));
            String snippet = text(first(DESCRIPTION, item));
            if (title.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) {
                continue;
            }
            url = SearchResultsJson.stripTracking(url);
            String host = SearchResultsJson.hostOf(url);
            if (host.isEmpty() || !hosts.add(host)) continue;
            out.add(new SearchResult(title, url, snippet));
        }
        return out;
    }

    private static String first(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }

    private static String text(String value) {
        return HtmlText.toText(value == null ? "" : value)
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .trim();
    }
}
