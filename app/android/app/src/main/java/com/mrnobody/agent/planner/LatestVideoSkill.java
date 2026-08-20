package com.mrnobody.agent.planner;

import com.mrnobody.agent.util.Hosts;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded path for "latest video on YouTube from [channel]" requests. */
public final class LatestVideoSkill {

    private static final Pattern CHANNEL = Pattern.compile(
            "(?i)\\bfrom\\s+(.+?)(?:\\s+youtube)?\\s+channel\\b");

    public static final class Match {
        public final String channel;
        public final String title;
        public final String url;
        public final String snippet;

        Match(String channel, String title, String url, String snippet) {
            this.channel = channel;
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }

        public String answer() {
            StringBuilder out = new StringBuilder();
            out.append("# Latest matching video from ").append(capitalise(channel)).append("\n\n");
            out.append("The latest matching YouTube result I found is **")
                    .append(title).append("**.\n\n").append(url);
            if (!snippet.isEmpty()) out.append("\n\n").append(snippet);
            out.append("\n\nThis uses the current search listing because a YouTube watch page is not "
                    + "treated as readable article text.");
            return out.toString();
        }
    }

    private LatestVideoSkill() {
    }

    public static boolean matches(String instruction) {
        if (instruction == null) return false;
        String t = instruction.toLowerCase(Locale.ROOT);
        return t.contains("youtube") && t.contains("video")
                && (t.contains("latest") || t.contains("newest") || t.contains("most recent"));
    }

    /** Search only video results for the named channel instead of the open web. */
    public static String searchQuery(String instruction) {
        if (!matches(instruction)) return instruction == null ? "" : instruction.trim();
        String channel = channel(instruction);
        if (channel.isEmpty()) return instruction.trim() + " site:youtube.com/watch";
        return "\"" + channel + "\" latest video site:youtube.com/watch";
    }

    public static Match find(String instruction, List<Map<String, Object>> results) {
        if (!matches(instruction) || results == null) return null;
        String wanted = channel(instruction);
        Match fallback = null;
        for (Map<String, Object> row : results) {
            String url = string(row.get("url"));
            String host = Hosts.firstIn(url);
            if (host == null || !(host.endsWith("youtube.com") || host.equals("youtu.be"))) {
                continue;
            }
            if (!(url.contains("/watch") || url.contains("youtu.be/")
                    || url.contains("/shorts/"))) {
                continue;
            }
            String title = string(row.get("title"));
            if (title.isEmpty()) continue;
            Match candidate = new Match(wanted.isEmpty() ? "YouTube" : wanted,
                    title, url, string(row.get("snippet")));
            if (fallback == null) fallback = candidate;
            if (wanted.isEmpty() || title.toLowerCase(Locale.ROOT)
                    .contains(wanted.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return fallback;
    }

    static String channel(String instruction) {
        if (instruction == null) return "";
        Matcher m = CHANNEL.matcher(instruction.trim());
        if (!m.find()) return "";
        return m.group(1).replaceAll("(?i)\\b(the|official)\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String string(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value).replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    private static String capitalise(String value) {
        if (value == null || value.isEmpty()) return "YouTube";
        StringBuilder out = new StringBuilder();
        for (String part : value.split("\\s+")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
