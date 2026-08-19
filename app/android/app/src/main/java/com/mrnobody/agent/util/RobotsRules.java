package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * robots.txt and sitemap discovery.
 *
 * <p>From the web-scraper skill's Phase 0: {@code RobotsFile.find} then
 * sitemap URLs, before a browser. We honour Allow / Disallow / Crawl-delay
 * for our own user-agent and lift {@code <loc>} entries from a sitemap
 * body. No site-specific scrapers.
 */
public final class RobotsRules {

    public static final String USER_AGENT = "MrNobody";

    private static final Pattern LOC = Pattern.compile(
            "<loc>\\s*([^<\\s]+)\\s*</loc>", Pattern.CASE_INSENSITIVE);

    private final List<Rule> rules;
    private final List<String> sitemaps;
    private final int crawlDelaySeconds;

    private RobotsRules(List<Rule> rules, List<String> sitemaps, int crawlDelaySeconds) {
        this.rules = rules;
        this.sitemaps = sitemaps;
        this.crawlDelaySeconds = crawlDelaySeconds;
    }

    public static RobotsRules empty() {
        return new RobotsRules(new ArrayList<Rule>(), new ArrayList<String>(), 0);
    }

    public static String urlFor(String host) {
        if (host == null || host.isEmpty()) return "";
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        return "https://" + h + "/robots.txt";
    }

    public static boolean looksLikeRobots(String body) {
        if (body == null || body.isEmpty()) return false;
        String head = body.substring(0, Math.min(body.length(), 4000))
                .toLowerCase(Locale.ROOT);
        return head.contains("user-agent:") && (head.contains("disallow:")
                || head.contains("sitemap:") || head.contains("allow:"));
    }

    public static boolean looksLikeSitemap(String body) {
        if (body == null || body.isEmpty()) return false;
        String head = body.substring(0, Math.min(body.length(), 4000))
                .toLowerCase(Locale.ROOT);
        return head.contains("<urlset") || head.contains("<sitemapindex")
                || (head.contains("<loc>") && head.contains("</loc>"));
    }

    public static RobotsRules parse(String text) {
        if (text == null || text.isEmpty()) return empty();
        List<Group> groups = new ArrayList<>();
        Group current = null;
        List<String> sitemaps = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash).trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("sitemap".equals(key)) {
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    if (!sitemaps.contains(value)) sitemaps.add(value);
                }
                continue;
            }
            if ("user-agent".equals(key)) {
                String ua = value.toLowerCase(Locale.ROOT);
                if (current == null || !current.rules.isEmpty() || current.closed) {
                    current = new Group();
                    groups.add(current);
                }
                current.agents.add(ua);
                continue;
            }
            if (current == null) continue;
            if ("disallow".equals(key)) {
                current.rules.add(new Rule(false, value));
                current.closed = true;
            } else if ("allow".equals(key)) {
                current.rules.add(new Rule(true, value));
                current.closed = true;
            } else if ("crawl-delay".equals(key)) {
                try {
                    int n = (int) Math.round(Double.parseDouble(value));
                    if (n > current.crawlDelay) current.crawlDelay = n;
                } catch (NumberFormatException ignored) {
                }
                current.closed = true;
            }
        }
        Group chosen = pickGroup(groups);
        if (chosen == null) return new RobotsRules(new ArrayList<Rule>(), sitemaps, 0);
        return new RobotsRules(chosen.rules, sitemaps, chosen.crawlDelay);
    }

    public boolean allows(String path) {
        return allows(USER_AGENT, path);
    }

    public boolean allows(String userAgent, String path) {
        String p = path == null || path.isEmpty() ? "/" : path;
        Rule best = null;
        for (Rule r : rules) {
            if (!r.matches(p)) continue;
            if (best == null || r.path.length() > best.path.length()
                    || (r.path.length() == best.path.length() && r.allow && !best.allow)) {
                best = r;
            }
        }
        return best == null || best.allow;
    }

    public List<String> sitemaps() {
        return new ArrayList<>(sitemaps);
    }

    public int crawlDelaySeconds() {
        return crawlDelaySeconds;
    }

    /** Readable view: sitemaps first, then a compact rule summary. */
    public String toText() {
        StringBuilder out = new StringBuilder();
        for (String sm : sitemaps) {
            if (out.length() > 0) out.append('\n');
            out.append("Sitemap: ").append(sm);
        }
        if (crawlDelaySeconds > 0) {
            if (out.length() > 0) out.append('\n');
            out.append("Crawl-delay: ").append(crawlDelaySeconds);
        }
        int denied = 0;
        for (Rule r : rules) {
            if (!r.allow && !r.path.isEmpty()) denied++;
        }
        if (denied > 0) {
            if (out.length() > 0) out.append('\n');
            out.append(denied).append(" path(s) disallowed");
        }
        return out.toString();
    }

    public static List<String> locsFrom(String xml) {
        List<String> out = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return out;
        Matcher m = LOC.matcher(xml);
        while (m.find() && out.size() < 80) {
            String loc = m.group(1).trim();
            if ((loc.startsWith("http://") || loc.startsWith("https://"))
                    && !out.contains(loc)) {
                out.add(loc);
            }
        }
        return out;
    }

    public static List<String> locsMatching(String xml, String query, int max) {
        int cap = max < 1 ? 4 : Math.min(max, 12);
        List<String> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        for (String loc : locsFrom(xml)) {
            if (TitleMatch.matches(loc, query)) {
                out.add(loc);
                if (out.size() >= cap) break;
            }
        }
        return out;
    }

    public static String sitemapToText(String xml) {
        List<String> locs = locsFrom(xml);
        if (locs.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int n = Math.min(locs.size(), 30);
        for (int i = 0; i < n; i++) {
            if (i > 0) out.append('\n');
            out.append(i + 1).append(". ").append(locs.get(i));
        }
        return out.toString();
    }

    private static Group pickGroup(List<Group> groups) {
        Group star = null;
        String me = USER_AGENT.toLowerCase(Locale.ROOT);
        for (Group g : groups) {
            for (String a : g.agents) {
                if (a.equals(me) || me.startsWith(a) || a.startsWith(me)) return g;
                if ("*".equals(a) && star == null) star = g;
            }
        }
        return star;
    }

    private static final class Group {
        final List<String> agents = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        int crawlDelay;
        boolean closed;
    }

    private static final class Rule {
        final boolean allow;
        final String path;

        Rule(boolean allow, String path) {
            this.allow = allow;
            this.path = path == null ? "" : path;
        }

        boolean matches(String candidate) {
            if (path.isEmpty()) return false;
            return candidate.startsWith(path);
        }
    }
}
