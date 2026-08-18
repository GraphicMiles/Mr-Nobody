package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Candidate search URLs for a host + query.
 *
 * <p>Sites do not share a vocabulary of verbs, but they do share a few
 * URL shapes: WordPress {@code ?s=}, {@code /search/}, {@code ?q=}.
 * Trying those on the host the user named is how "get Infinity War from
 * nkiri.ink" becomes a page that actually lists the film, instead of the
 * site's homepage.
 *
 * <p>No site names live here. A well-known public search path (X, Reddit)
 * is the same kind of structure as {@code ?s=} — a URL template, not a
 * scraper.
 */
public final class SearchUrls {

    private SearchUrls() {
    }

    /** Search pages to try, homepage last so a dedicated search wins. */
    public static List<String> forHost(String host, String query) {
        List<String> out = new ArrayList<>();
        if (host == null || host.isEmpty()) return out;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            out.add("https://" + h + "/");
            return out;
        }
        String enc = urlEncode(q);

        if (h.equals("x.com") || h.equals("twitter.com")) {
            out.add("https://x.com/search?q=" + enc + "&f=live");
            out.add("https://x.com/search?q=" + enc);
            return out;
        }
        if (h.equals("reddit.com")) {
            out.add("https://www.reddit.com/search/?q=" + enc);
            return out;
        }

        out.add("https://" + h + "/?s=" + enc);
        out.add("https://" + h + "/search/" + enc);
        out.add("https://" + h + "/search?q=" + enc);
        out.add("https://" + h + "/?q=" + enc);
        return out;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s.replace(" ", "%20");
        }
    }
}
