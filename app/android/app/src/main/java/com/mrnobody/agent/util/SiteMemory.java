package com.mrnobody.agent.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Last successful fetch strategy per host.
 *
 * <p>Per-host fetch memory: remember whether a host was a
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

    /**
     * One plain-HTTP outcome for a host: {@code usable} when the fetch
     * returned prose the read loop could actually cite, false when it failed
     * or needed the browser. Clamped so one bad afternoon cannot condemn a
     * host forever, and one lucky hit cannot canonise it.
     */
    public static synchronized void recordHttpOutcome(String host, boolean usable) {
        String h = norm(host);
        if (h.isEmpty()) return;
        Observation o = MEM.get(h);
        if (o == null) o = new Observation();
        o.httpScore = clampScore(o.httpScore + (usable ? 1 : -1));
        MEM.put(h, o);
    }

    /**
     * The cheap-success score for a host: positive when plain HTTP has been
     * yielding usable text, negative when it has not, zero when unknown.
     * Read candidates are ranked by this (read-loop rule 6).
     */
    public static synchronized int httpScore(String host) {
        Observation o = MEM.get(norm(host));
        return o == null ? 0 : o.httpScore;
    }

    private static int clampScore(int v) {
        return Math.max(-3, Math.min(3, v));
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
        /** Clamped running score of plain-HTTP fetch outcomes. */
        int httpScore;
    }
}
