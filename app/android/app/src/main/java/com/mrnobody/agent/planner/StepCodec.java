package com.mrnobody.agent.planner;

import com.mrnobody.agent.core.ToolRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a model's JSON into executable tool steps — the one place a model's
 * parameter names, aliases and URLs are normalised.
 *
 * <p>Shared by {@link LlmPlanner} (plan-once) and the autonomous planner
 * (step-by-step) so the two can never drift: a model that writes {@code query}
 * instead of {@code q}, or a bare domain without a scheme, is normalised the
 * same way on both paths. A step whose required argument is unusable is
 * dropped — a step the pipeline is guaranteed to refuse is a failed step by
 * another name.
 */
final class StepCodec {

    private StepCodec() {
    }

    /**
     * Parse a whole plan (a JSON array of steps). Returns null on any parse,
     * shape or unknown-tool error — the caller falls back.
     */
    static List<Plan.Step> parseSteps(String response, Collection<String> availableTools) {
        try {
            JSONObject root = new JSONObject(response.trim());
            JSONArray arr = root.optJSONArray("steps");
            if (arr == null) return null;

            List<Plan.Step> steps = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) return null;
                Plan.Step step = parseOne(s, availableTools);
                if (step == null) {
                    // An unusable single step is skipped, not fatal.
                    if (s.has("tool")) continue;
                    return null;
                }
                steps.add(step);
            }
            return steps;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse a single-step decision. Returns null when the model signalled it is
     * done ({@code {"done": true}}), when the step is unusable, or on any error
     * — null always means "stop", never "loop forever", which is what keeps a
     * misbehaving model from spinning the autonomous loop.
     */
    static Plan.Step parseOneStep(String response, Collection<String> availableTools) {
        try {
            JSONObject root = new JSONObject(response.trim());
            if (root.optBoolean("done", false)) return null;
            if (!root.has("tool")) return null;
            return parseOne(root, availableTools);
        } catch (Exception e) {
            return null;
        }
    }

    /** One step object → a validated Plan.Step, or null when unusable. */
    private static Plan.Step parseOne(JSONObject s, Collection<String> availableTools) {
        String tool = s.optString("tool", "").trim();
        if (tool.isEmpty()) return null;
        if (!availableTools.contains(tool)) return null;

        JSONObject args = s.optJSONObject("args");
        Map<String, String> params = new LinkedHashMap<>();
        if (args != null) {
            // Android's JSONObject exposes keys(), not keySet().
            for (Iterator<String> it = args.keys(); it.hasNext(); ) {
                String key = it.next();
                if (!args.isNull(key)) params.put(key, String.valueOf(args.opt(key)));
            }
        }

        normalizeAliases(tool, params);
        if (!usableParams(tool, params)) return null;

        String action;
        if ("browser".equals(tool)) {
            // The browser's action is part of the request, not a param.
            action = params.containsKey("action") ? params.remove("action") : "open";
        } else {
            action = actionFor(tool);
        }
        return Plan.Step.tool(tool, tool, new ToolRequest(action, params), "planned by model");
    }

    /** "query" → "q", "link" → "url". */
    private static void normalizeAliases(String tool, Map<String, String> params) {
        if ("search".equals(tool)) {
            if (!params.containsKey("q") && params.containsKey("query")) {
                params.put("q", params.remove("query"));
            }
        }
        if ("http".equals(tool) || "download".equals(tool)) {
            if (!params.containsKey("url") && params.containsKey("link")) {
                params.put("url", params.remove("link"));
            }
        }
    }

    /**
     * True when the step's arguments are worth trying. A URL that is not
     * http(s) — a bare filename, a relative path — is refused; a bare domain
     * gets its scheme filled in.
     */
    private static boolean usableParams(String tool, Map<String, String> params) {
        if ("http".equals(tool) || "download".equals(tool) || "browser".equals(tool)) {
            String url = params.get("url");
            if (url == null || url.trim().isEmpty()) return true; // spec will refuse clearly
            String fixed = normaliseUrl(url);
            if (fixed == null) return false;
            params.put("url", fixed);
        }
        if ("search".equals(tool)) {
            String q = params.get("q");
            if (q == null || q.trim().isEmpty()) return false;
        }
        return true;
    }

    /** A scheme-qualified http(s) URL from a model's best effort, or null. */
    private static String normaliseUrl(String url) {
        String u = url.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (u.contains(" ") || u.isEmpty()) return null;
        String host = u;
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        int dot = host.indexOf('.');
        if (dot <= 0 || dot == host.length() - 1) return null;
        return "https://" + u;
    }

    /** The ToolRequest action each tool expects, matching the deterministic planner. */
    private static String actionFor(String tool) {
        switch (tool) {
            case "search":
                return "search";
            case "http":
                return "fetch";
            case "download":
                return "download";
            default:
                return tool;
        }
    }
}
