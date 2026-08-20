package com.mrnobody.agent.planner;

import com.mrnobody.agent.util.Hosts;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extensible routing for common search jobs.
 *
 * <p>A skill changes query shape, provider preference and answer treatment; it
 * never bypasses ToolPipeline, approval, budgets or evidence policy.
 */
public final class SearchSkills {

    public enum Kind {
        GENERIC,
        YOUTUBE,
        LATEST_YOUTUBE,
        FACEBOOK_PUBLIC,
        MATERIAL,
        FRESH_INFORMATION
    }

    public static final class Skill {
        public final Kind kind;
        public final String id;
        public final String query;
        public final String provider;
        public final boolean listingOnly;
        public final String host;
        public final String heading;
        public final String note;

        Skill(Kind kind, String id, String query, String provider,
              boolean listingOnly, String host, String heading, String note) {
            this.kind = kind;
            this.id = id;
            this.query = query;
            this.provider = provider;
            this.listingOnly = listingOnly;
            this.host = host;
            this.heading = heading;
            this.note = note;
        }

        public boolean isGeneric() {
            return kind == Kind.GENERIC;
        }

        public String decision() {
            switch (kind) {
                case LATEST_YOUTUBE:
                    return "Use a YouTube-restricted latest-video listing; do not read watch-page code.";
                case YOUTUBE:
                    return "Search YouTube watch results and present public listing metadata.";
                case FACEBOOK_PUBLIC:
                    return "Search only public Facebook results indexed by the selected search provider.";
                case MATERIAL:
                    return provider.isEmpty()
                            ? "Search for directly usable learning material and document results."
                            : "Use the explicitly requested Google search path for learning material.";
                case FRESH_INFORMATION:
                    return "Bias the query to the current year, then read and verify fresh sources.";
                case GENERIC:
                default:
                    return "Use the general evidence-first web research path.";
            }
        }

        /** Keep only the host class the skill promised, when it has one. */
        public List<Map<String, Object>> filter(List<Map<String, Object>> results) {
            if (host.isEmpty() || results == null) return results;
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : results) {
                String url = string(row.get("url"));
                String h = Hosts.firstIn(url);
                if (h != null && (h.equals(host) || h.endsWith("." + host))) out.add(row);
            }
            return out;
        }

        /** Honest answer for app shells, login walls and document listings. */
        public String listingAnswer(List<Map<String, Object>> results) {
            StringBuilder out = new StringBuilder();
            out.append("# ").append(heading).append("\n\n");
            out.append("I found these matching public results:\n");
            int n = 0;
            if (results != null) {
                for (Map<String, Object> row : results) {
                    if (n >= 5) break;
                    String title = clean(row.get("title"));
                    String url = string(row.get("url"));
                    String snippet = clean(row.get("snippet"));
                    if (title.isEmpty() || url.isEmpty()) continue;
                    n++;
                    out.append("\n").append(n).append(". **").append(title)
                            .append("**\n").append(url);
                    if (!snippet.isEmpty()) out.append("\n").append(trim(snippet, 240));
                }
            }
            if (n == 0) return "No matching public results were found.";
            if (!note.isEmpty()) out.append("\n\n").append(note);
            return out.toString();
        }
    }

    private SearchSkills() {
    }

    public static Skill route(String instruction) {
        String raw = instruction == null ? "" : instruction.trim();
        String t = raw.toLowerCase(Locale.ROOT);

        if (LatestVideoSkill.matches(raw)) {
            return new Skill(Kind.LATEST_YOUTUBE, "youtube.latest",
                    LatestVideoSkill.searchQuery(raw), "", true, "youtube.com",
                    "Latest matching YouTube video",
                    "YouTube watch pages are treated as application shells, not article text.");
        }

        if (t.contains("youtube") && containsAny(t,
                "search", "find", "video", "channel", "watch")) {
            String subject = topic(raw, "youtube");
            String query = (subject.isEmpty() ? raw : subject) + " site:youtube.com/watch";
            return new Skill(Kind.YOUTUBE, "youtube.search", query, "", true,
                    "youtube.com", "YouTube search results",
                    "Results use public search metadata; private, deleted and age-restricted videos may not appear.");
        }

        if (t.contains("facebook") && containsAny(t,
                "search", "find", "page", "post", "profile")) {
            String subject = topic(raw, "facebook");
            String query = (subject.isEmpty() ? raw : subject) + " site:facebook.com";
            return new Skill(Kind.FACEBOOK_PUBLIC, "facebook.public_search",
                    query, "", true, "facebook.com", "Public Facebook results",
                    "Only publicly indexed Facebook pages and posts are searched. Private profiles, groups and login-only content are not accessed.");
        }

        if (isMaterialRequest(t)) {
            boolean google = t.contains("google");
            String subject = topic(raw, google ? "google" : "");
            String query = subject.isEmpty() ? raw : subject;
            if (containsAny(t, "pdf", "paper", "manual", "document", "slides")) {
                query += " filetype:pdf";
            }
            return new Skill(Kind.MATERIAL, "material.search", query,
                    google ? "google" : "", true, "", "Learning materials",
                    "These are search results for materials; availability, licensing and download safety still need to be checked.");
        }

        if (containsAny(t, "latest", "newest", "most recent", "today", "current", "breaking")) {
            String year = new SimpleDateFormat("yyyy", Locale.US).format(new Date());
            String query = t.contains(year) ? raw : raw + " " + year;
            return new Skill(Kind.FRESH_INFORMATION, "information.latest",
                    query, "", false, "", "Latest information", "");
        }

        return new Skill(Kind.GENERIC, "web.general", raw, "", false, "",
                "Web research", "");
    }

    private static boolean isMaterialRequest(String t) {
        return containsAny(t, "material", "resource", "pdf", "paper", "manual",
                "tutorial", "document", "slides", "textbook", "course notes")
                && containsAny(t, "find", "search", "google", "get", "look for");
    }

    private static String topic(String raw, String platform) {
        String q = raw.replaceAll("(?i)\\b(search|find|look for|show me|get|using google search)\\b", " ");
        if (platform != null && !platform.isEmpty()) {
            q = q.replaceAll("(?i)\\b(?:on|from|using)?\\s*" + java.util.regex.Pattern.quote(platform)
                    + "(?:\\s+search)?\\b", " ");
        }
        q = q.replaceAll("(?i)\\b(public|page|pages|post|posts|profile|profiles|using)\\b", " ");
        q = q.replaceAll("(?i)^\\s*(?:the\\s+)?(?:for\\s+)?", " ");
        return q.replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private static String clean(Object value) {
        return string(value).replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private static String string(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value).trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
