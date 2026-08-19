package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Public X/Twitter URL shapes, not a logged-in client.
 *
 * <p>mcp-twikit and Agent-Reach search with {@code from:handle} / Latest.
 * We cannot ship their cookie login. We can still open the same public
 * search URLs the site itself serves — profile, live from-user, live topic.
 */
public final class XQuery {

    private XQuery() {
    }

    public static String profile(String handle) {
        String h = clean(handle);
        return h.isEmpty() ? "" : "https://x.com/" + h;
    }

    /** Tweets <em>by</em> this account, newest first. */
    public static String liveFrom(String handle) {
        String h = clean(handle);
        if (h.isEmpty()) return "";
        return "https://x.com/search?q=" + enc("from:" + h) + "&f=live";
    }

    public static String liveTopic(String query) {
        if (query == null || query.trim().isEmpty()) return "";
        return "https://x.com/search?q=" + enc(query.trim()) + "&f=live";
    }

    /**
     * Pages to open for a handle plus optional topic, profile last so a
     * live search is tried first.
     */
    public static List<String> pages(String handle, String topic) {
        List<String> out = new ArrayList<>();
        String from = liveFrom(handle);
        if (!from.isEmpty()) out.add(from);
        String live = liveTopic(topic);
        if (!live.isEmpty()) out.add(live);
        String prof = profile(handle);
        if (!prof.isEmpty()) out.add(prof);
        return out;
    }

    public static boolean isXHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        return h.equals("x.com") || h.equals("twitter.com");
    }

    public static String clean(String handle) {
        if (handle == null) return "";
        String h = handle.trim();
        if (h.startsWith("@")) h = h.substring(1);
        int slash = h.lastIndexOf('/');
        if (slash >= 0) h = h.substring(slash + 1);
        return h.replaceAll("[^A-Za-z0-9_]", "");
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s.replace(" ", "+");
        }
    }
}
