package com.mrnobody.agent.planner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A structured shortlist the next turn can address by index or "it".
 *
 * <p>"Open the second one" and "where can I watch it" failed because the
 * three laptops lived only in prose. This is data: number, title, url.
 */
public final class TaskArtifact {

    public final int index;
    public final String title;
    public final String url;
    public final String note;

    public TaskArtifact(int index, String title, String url, String note) {
        this.index = index;
        this.title = title == null ? "" : title;
        this.url = url == null ? "" : url;
        this.note = note == null ? "" : note;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("n", index);
            o.put("title", title);
            o.put("url", url);
            o.put("note", note);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static TaskArtifact fromJson(JSONObject o) {
        if (o == null) return null;
        return new TaskArtifact(
                o.optInt("n", 0),
                o.optString("title", ""),
                o.optString("url", ""),
                o.optString("note", ""));
    }

    public static String encode(List<TaskArtifact> items) {
        JSONArray arr = new JSONArray();
        if (items != null) {
            for (TaskArtifact a : items) arr.put(a.toJson());
        }
        return arr.toString();
    }

    public static List<TaskArtifact> decode(String json) {
        List<TaskArtifact> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                TaskArtifact a = fromJson(arr.optJSONObject(i));
                if (a != null && (!a.title.isEmpty() || !a.url.isEmpty())) out.add(a);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * Which item a follow-up is talking about. "the second one", "it",
     * "#3", or a title fragment. Null when the message is not pointing.
     */
    public static TaskArtifact resolve(String followUp, List<TaskArtifact> items) {
        if (items == null || items.isEmpty() || followUp == null) return null;
        String t = followUp.toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) return null;

        Matcher hash = Pattern.compile("(?:#|no\\.?\\s*|number\\s+)(\\d+)").matcher(t);
        if (hash.find()) {
            return at(items, Integer.parseInt(hash.group(1)));
        }
        if (t.contains("first") || t.contains("1st")) return at(items, 1);
        if (t.contains("second") || t.contains("2nd")) return at(items, 2);
        if (t.contains("third") || t.contains("3rd")) return at(items, 3);
        if (t.contains("fourth") || t.contains("4th")) return at(items, 4);
        if (t.contains("fifth") || t.contains("5th")) return at(items, 5);

        for (TaskArtifact a : items) {
            if (!a.title.isEmpty() && t.contains(a.title.toLowerCase(Locale.ROOT))) {
                return a;
            }
        }
        // Bare pronoun / "that one" / "the one" → the last (most recent) item.
        if (t.matches(".*\\b(it|that|this|that one|the one|this one)\\b.*")) {
            return items.get(items.size() - 1);
        }
        return null;
    }

    private static TaskArtifact at(List<TaskArtifact> items, int n) {
        for (TaskArtifact a : items) {
            if (a.index == n) return a;
        }
        if (n >= 1 && n <= items.size()) return items.get(n - 1);
        return null;
    }

    public static List<TaskArtifact> fromSearch(List<java.util.Map<String, Object>> results) {
        List<TaskArtifact> out = new ArrayList<>();
        if (results == null) return out;
        int n = 1;
        for (java.util.Map<String, Object> row : results) {
            if (n > 8) break;
            String title = String.valueOf(row.getOrDefault("title", ""));
            String url = String.valueOf(row.getOrDefault("url", ""));
            String snippet = String.valueOf(row.getOrDefault("snippet", ""));
            if (url.isEmpty() || "null".equals(url)) continue;
            out.add(new TaskArtifact(n++, title, url, snippet));
        }
        return out;
    }
}
