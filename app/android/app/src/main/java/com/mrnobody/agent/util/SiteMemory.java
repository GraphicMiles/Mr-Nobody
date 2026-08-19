package com.mrnobody.agent.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Last successful fetch strategy per host.
 *
 * <p>From scrapling's site-patterns file: remember whether a host was a
 * static page, a feed, or a challenge/SPA, so the next visit can start
 * on the right FetchLadder rung. No cookies, no selectors, no site names.
 * In-memory and bounded — a process restart forgets, which is fine.
 */
public final class SiteMemory {

    private static final int MAX = 64;
    private static final int BROWSER_STREAK = 2;

    private static final Map<String, Observation> MEM =
            new LinkedHashMap<String, Observation>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Observation> eldest) {
                    return size() > MAX;
                }
            };

    private SiteMemory() {
    }

    public static synchronized void reset() {
        MEM.clear();
    }

    public static synchronized void remember(String host, PageKind.Kind kind) {
        String h = norm(host);
        if (h.isEmpty() || kind == null) return;
        Observation o = MEM.get(h);
        if (o == null) o = new Observation();
        o.kind = kind;
        if (kind.needsBrowser()) o.challengeStreak++;
        else o.challengeStreak = 0;
        MEM.put(h, o);
    }

    public static synchronized PageKind.Kind lastKind(String host) {
        Observation o = MEM.get(norm(host));
        return o == null ? null : o.kind;
    }

    public static synchronized int challengeStreak(String host) {
        Observation o = MEM.get(norm(host));
        return o == null ? 0 : o.challengeStreak;
    }

    /** True after two challenge/SPA observations in a row. */
    public static synchronized boolean preferBrowser(String host) {
        Observation o = MEM.get(norm(host));
        return o != null && o.challengeStreak >= BROWSER_STREAK;
    }

    private static String norm(String host) {
        if (host == null || host.isEmpty()) return "";
        String h = host.toLowerCase(Locale.ROOT).trim();
        if (h.startsWith("www.")) h = h.substring(4);
        return h;
    }

    private static final class Observation {
        PageKind.Kind kind;
        int challengeStreak;
    }
}
