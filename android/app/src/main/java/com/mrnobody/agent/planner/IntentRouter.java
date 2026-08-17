package com.mrnobody.agent.planner;

import android.webkit.URLUtil;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic intent routing for the unified input. A simple request should
 * never need an LLM: URLs and obvious searches are classified locally. Only
 * ambiguous or instruction-like input becomes a task.
 *
 * This is pure Java and unit-testable.
 */
public final class IntentRouter {

    // Instruction-like leading verbs: "find", "get", "download", "open … and",
    // "summarize", "compare", "check", "monitor", "every", etc.
    private static final List<String> VERBS = Arrays.asList(
            "find", "get", "fetch", "download", "summarize", "summarise",
            "compare", "check", "monitor", "track", "extract", "collect",
            "search for", "open and", "every", "watch", "scrape", "send",
            "buy", "order", "look up", "lookup");

    private static final Pattern IP = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$");
    private static final Pattern LOCALHOST = Pattern.compile("^localhost(:\\d+)?$");

    private IntentRouter() {
    }

    /** Classify one line of input. Never throws. */
    public static IntentType route(String input) {
        if (input == null) return IntentType.SEARCH;
        String s = input.trim();
        if (s.isEmpty()) return IntentType.SEARCH;

        // explicit scheme → URL
        if (URLUtil.isValidUrl(s)) return IntentType.URL;

        // bare hostname / IP / localhost → URL
        if (IP.matcher(s).matches() || LOCALHOST.matcher(s).matches()) return IntentType.URL;
        if (looksLikeDomain(s)) return IntentType.URL;

        // instruction-like → task
        String lower = s.toLowerCase(Locale.ROOT);
        for (String v : VERBS) {
            if (lower.startsWith(v + " ") || lower.equals(v)) return IntentType.TASK;
        }
        // "open <url> and download" style compound instructions
        if (lower.contains(" and ")) return IntentType.TASK;

        // otherwise a search
        return IntentType.SEARCH;
    }

    private static boolean looksLikeDomain(String s) {
        if (s.contains(" ")) return false;
        int dot = s.indexOf('.');
        if (dot <= 0 || dot == s.length() - 1) return false;
        // no spaces, has a dot, and a plausible TLD
        String tld = s.substring(s.lastIndexOf('.') + 1);
        return tld.matches("[a-zA-Z]{2,}");
    }
}
