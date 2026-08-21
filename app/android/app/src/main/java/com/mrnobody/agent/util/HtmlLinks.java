package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every http(s) target a page exposes: {@code <a href>}, media tags, and
 * URLs hiding in the markup (inline JS, data attributes).
 *
 * <p>A page that lists a file only inside a script tag is why "the links
 * tool returned nothing downloadable" used to happen. This is the same
 * extraction an earlier resolver used, without its host allowlists.
 */
public final class HtmlLinks {

    private static final Pattern HREF = Pattern.compile(
            "(?:href|src)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern BARE_URL = Pattern.compile(
            "https?://[^\\s\"'<>\\[\\]]+", Pattern.CASE_INSENSITIVE);

    private HtmlLinks() {
    }

    /** Distinct absolute URLs, in order of appearance, capped at 200. */
    public static List<String> extract(String html, String pageUrl) {
        Set<String> found = new LinkedHashSet<>();
        if (html == null || html.isEmpty()) return new ArrayList<>();

        Matcher hrefs = HREF.matcher(html);
        while (hrefs.find() && found.size() < 200) {
            add(found, hrefs.group(1), pageUrl);
        }
        Matcher bare = BARE_URL.matcher(html);
        while (bare.find() && found.size() < 200) {
            add(found, bare.group(), pageUrl);
        }
        return new ArrayList<>(found);
    }

    private static void add(Set<String> found, String raw, String pageUrl) {
        String abs = UrlResolve.resolve(raw, pageUrl);
        if (abs == null) return;
        String lower = abs.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return;
        found.add(abs);
    }
}
