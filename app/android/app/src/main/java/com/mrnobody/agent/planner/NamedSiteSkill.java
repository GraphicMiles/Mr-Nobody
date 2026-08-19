package com.mrnobody.agent.planner;

import com.mrnobody.agent.util.FeedDiscover;
import com.mrnobody.agent.util.Hosts;
import com.mrnobody.agent.util.SearchUrls;
import com.mrnobody.agent.util.TitleMatch;
import com.mrnobody.agent.util.XQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * How to honour "get X from [this site]".
 *
 * <p>A named host is not a search. Opening {@code https://nkiri.ink} and
 * reading the homepage is how Infinity War was answered from Prime Video:
 * the one site the user named was fetched, but never <em>queried</em>.
 * This skill turns the host + the work they named into the search pages
 * that site actually serves.
 *
 * <p>No site-specific selectors. Search URL shapes come from
 * {@link SearchUrls}; ranking comes from {@link TitleMatch}.
 */
public final class NamedSiteSkill {

    private NamedSiteSkill() {
    }

    /**
     * Pages to open, in order: search URLs for the work on the named host,
     * then the host itself. Empty when the instruction names no site.
     */
    public static List<String> pagesToOpen(String instruction) {
        List<String> pages = new ArrayList<>();
        NamedSource src = NamedSource.extract(instruction);
        if (src == null) return pages;

        String host = Hosts.firstIn(src.fetchUrl);
        String query = TitleMatch.queryFrom(instruction, host);
        String handle = src.written.startsWith("@") ? src.written : "";
        if (handle.isEmpty() && XQuery.isXHost(host)) {
            handle = XQuery.clean(src.fetchUrl);
        }
        if (!handle.isEmpty() && (XQuery.isXHost(host) || src.written.startsWith("@"))) {
            pages.addAll(XQuery.pages(handle, query));
        } else if (host != null && !query.isEmpty() && looksLikeRoot(src.fetchUrl)) {
            pages.addAll(SearchUrls.forHost(host, query));
        }
        if (host != null && looksLikeRoot(src.fetchUrl)) {
            for (String feed : FeedDiscover.candidates(host, null)) {
                if (!pages.contains(feed)) pages.add(feed);
            }
        }
        if (!pages.contains(src.fetchUrl)) pages.add(src.fetchUrl);
        return pages;
    }

    /** The work the user named, for ranking files on a results page. */
    public static String query(String instruction) {
        NamedSource src = NamedSource.extract(instruction);
        String host = src == null ? null : Hosts.firstIn(src.fetchUrl);
        return TitleMatch.queryFrom(instruction, host);
    }

    static boolean looksLikeRoot(String url) {
        if (url == null) return true;
        try {
            java.net.URI u = java.net.URI.create(url);
            String path = u.getPath();
            return path == null || path.isEmpty() || "/".equals(path);
        } catch (Exception e) {
            return true;
        }
    }
}
