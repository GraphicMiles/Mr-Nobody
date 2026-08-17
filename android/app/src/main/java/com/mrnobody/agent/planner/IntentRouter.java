package com.mrnobody.agent.planner;

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
    // Any scheme, e.g. https://, http://, mailto:, intent:// — pure Java so the
    // router stays JVM-testable (no android.webkit dependency).
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");

    private IntentRouter() {
    }

    /** Classify one line of input. Never throws. */
    public static IntentType route(String input) {
        if (input == null) return IntentType.SEARCH;
        String s = input.trim();
        if (s.isEmpty()) return IntentType.SEARCH;

        // explicit scheme → URL
        if (SCHEME.matcher(s).matches()) return IntentType.URL;

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
        // isolate the host: strip any path, then any port
        String host = s;
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);

        int dot = host.indexOf('.');
        if (dot <= 0 || dot == host.length() - 1) return false;
        // has a dot and a plausible TLD (letters only, no spaces)
        String tld = host.substring(host.lastIndexOf('.') + 1);
        return tld.matches("[a-zA-Z]{2,}");
    }
}
