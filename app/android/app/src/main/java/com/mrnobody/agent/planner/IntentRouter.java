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
            "buy", "order", "look up", "lookup",
            // "research …" / "read …" / "use google search to …" were observed
            // on-device routing to a raw results page instead of the agent.
            "research", "read", "use", "look for", "browse for");

    // Question openers: a natural-language question is an agent task, not a raw
    // search. "What is X's age" must not land on a results page.
    private static final List<String> QUESTIONS = Arrays.asList(
            "what is", "what's", "what are", "what was", "what were", "what about",
            "who is", "who's", "who are", "who was",
            "how old", "how much", "how many", "how to", "how do", "how does",
            "how is", "how are", "how long", "how tall",
            "when is", "when was", "when did", "when does", "when will",
            "where is", "where can", "where do", "where are",
            "why is", "why does", "why are", "why do",
            "which is", "which one", "which are",
            "tell me", "explain", "define", "is", "are", "does", "did", "can you");

    // Trailing fact-words: "hrithik roshan age", "bitcoin price today".
    private static final List<String> FACT_WORDS = Arrays.asList(
            "age", "height", "birthday", "birthdate", "net worth", "worth", "price",
            "salary", "girlfriend", "wife", "husband", "nationality", "religion",
            "instagram", "twitter", "meaning", "definition", "capital", "population");

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

        // Slash commands are explicit user overrides: heuristics will always
        // miss some phrasing, so "/agent …" must be deterministic.
        IntentType slash = slashCommand(s);
        if (slash != null) return slash;

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

        // a question → task
        for (String q : QUESTIONS) {
            if (lower.equals(q) || lower.startsWith(q + " ")) return IntentType.TASK;
        }

        // a vague/partial fact lookup ("hrithik roshan age") → task
        String[] words = lower.split("\\s+");
        if (words.length >= 2 && FACT_WORDS.contains(words[words.length - 1])) {
            return IntentType.TASK;
        }

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

    /**
     * Explicit slash-command routing: {@code /agent} and {@code /task} force
     * the agent, {@code /download} forces a download task, {@code /search}
     * forces a plain browser search, {@code /open} forces URL handling.
     *
     * @return the forced type, or null when input is not a slash command
     */
    public static IntentType slashCommand(String input) {
        if (input == null) return null;
        String lower = input.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("/agent ") || lower.startsWith("/task ")
                || lower.startsWith("/download ") || lower.startsWith("/dl ")) {
            return IntentType.TASK;
        }
        if (lower.startsWith("/search ")) return IntentType.SEARCH;
        if (lower.startsWith("/open ")) return IntentType.URL;
        return null;
    }

    /**
     * The text a slash command carries: {@code /agent why is the sky blue} →
     * {@code why is the sky blue}; {@code /download <url>} →
     * {@code download <url>} so the existing download routing applies.
     * Non-command input returns unchanged (trimmed).
     */
    public static String payload(String input) {
        if (input == null) return "";
        String s = input.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        for (String prefix : Arrays.asList("/agent ", "/task ", "/search ", "/open ")) {
            if (lower.startsWith(prefix)) return s.substring(prefix.length()).trim();
        }
        if (lower.startsWith("/download ")) {
            return "download " + s.substring("/download ".length()).trim();
        }
        if (lower.startsWith("/dl ")) {
            return "download " + s.substring("/dl ".length()).trim();
        }
        return s;
    }
}
