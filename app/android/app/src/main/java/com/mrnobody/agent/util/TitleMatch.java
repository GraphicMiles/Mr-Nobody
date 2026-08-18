package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a page title or filename is talking about the same work the user
 * named. Scoring, not equality: "Avengers.Infinity.War.2018.1080p.mkv"
 * must match "Infinity War".
 *
 * <p>Generic on purpose. Site watermarks and release tags are stripped as
 * <em>shape</em> (parentheses, quality tokens, extensions), not as a list
 * of pirate brands.
 */
public final class TitleMatch {

    private static final Pattern PARENS = Pattern.compile("[(\\[{].*?[)\\]}]");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern QUALITY = Pattern.compile(
            "\\b(webrip|web-dl|hdrip|bluray|brrip|720p|1080p|2160p|4k|x264|h264|"
                    + "x265|hevc|hdr|s\\d{1,2}e\\d{1,2}|season|episode|complete)\\b");

    private static final java.util.Set<String> STOP = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "a", "an", "the", "of", "in", "at", "to", "and", "for",
                    "with", "by", "from", "on", "or", "download", "get", "fetch",
                    "open", "watch", "search", "find", "off", "please"));

    private TitleMatch() {
    }

    /** Tokens that identify the work, stripped of the named host. */
    public static String queryFrom(String instruction, String host) {
        if (instruction == null) return "";
        String text = instruction;
        if (host != null && !host.isEmpty()) {
            text = text.replaceAll("(?i)" + Pattern.quote(host), " ");
        }
        text = text.replaceAll("(?i)https?://\\S+", " ");
        text = text.replaceAll("@[A-Za-z0-9_]+", " ");
        List<String> toks = tokens(clean(text));
        StringBuilder sb = new StringBuilder();
        for (String t : toks) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    /** 0–100. 0 means "not this work". */
    public static int score(String title, String query) {
        String t = clean(title);
        String q = clean(query);
        if (t.isEmpty() || q.isEmpty()) return 0;
        if (t.equals(q)) return 100;
        if (t.contains(q) || q.contains(t)) return 90;
        List<String> qt = tokens(q);
        List<String> tt = tokens(t);
        if (qt.isEmpty()) return 0;
        int hit = 0;
        for (String tok : qt) {
            if (tt.contains(tok)) hit++;
        }
        if (hit == 0) return 0;
        if (hit == qt.size()) return 80;
        return (60 * hit) / qt.size();
    }

    public static boolean matches(String title, String query) {
        return score(title, query) >= 60;
    }

    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.ROOT);
        s = PARENS.matcher(s).replaceAll(" ");
        s = QUALITY.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\.(mkv|mp4|webm|avi|mov|zip|pdf)$", " ");
        s = NON_ALNUM.matcher(s).replaceAll(" ").trim();
        return s.replaceAll("\\s+", " ");
    }

    static List<String> tokens(String cleaned) {
        List<String> out = new ArrayList<>();
        if (cleaned == null || cleaned.isEmpty()) return out;
        for (String w : cleaned.split("\\s+")) {
            if (w.length() >= 2 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }
}
