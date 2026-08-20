package com.mrnobody.agent.util;

import java.util.Locale;

/** Rejects script/configuration dumps before they become answer evidence. */
public final class ReadableText {

    private ReadableText() {
    }

    /** True when extracted page text contains enough prose to cite. */
    public static boolean usable(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < 40) return false;
        String lower = t.substring(0, Math.min(t.length(), 4_000))
                .toLowerCase(Locale.ROOT);
        if (lower.contains("experiment_flags")
                || lower.contains("window.ytplayer")
                || lower.contains("ytcfg.set(")
                || lower.contains("client_canary_state")
                || lower.contains("__next_data__")
                || lower.contains("webpackchunk")) {
            return false;
        }

        int punctuation = 0;
        int letters = 0;
        int braces = 0;
        int escaped = 0;
        int limit = Math.min(t.length(), 4_000);
        for (int i = 0; i < limit; i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) letters++;
            if (c == '{' || c == '}' || c == '[' || c == ']') braces++;
            if (c == '"' || c == ':' || c == ';' || c == '=') punctuation++;
            if (c == '\\' && i + 1 < limit && t.charAt(i + 1) == 'u') escaped++;
        }
        if (letters == 0) return false;
        if (braces > 12 && punctuation > letters / 6) return false;
        if (escaped > 8) return false;
        return true;
    }

    /** True for one code/config-shaped sentence inside an otherwise valid page. */
    public static boolean proseSentence(String sentence) {
        if (sentence == null) return false;
        String s = sentence.trim();
        if (s.length() < 20) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("function()") || lower.contains("window.")
                || lower.contains("experiment_flags") || lower.contains("ytcfg")
                || lower.contains("\\u003") || lower.contains("client_canary_state")) {
            return false;
        }
        if (boilerplateSentence(lower)) return false;
        int braces = 0;
        int quotes = 0;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) letters++;
            if (c == '{' || c == '}' || c == '[' || c == ']') braces++;
            if (c == '"') quotes++;
        }
        return letters > 12 && braces < 6 && quotes < Math.max(8, letters / 8);
    }

    /**
     * Anti-bot walls, consent chrome, reader comments and navigation rails are
     * page furniture, not page content. Quoting them as an answer — "Please
     * enable JavaScript or switch to a supported browser", a site's cookie
     * banner, or a spec's table of contents — was observed on-device and is
     * worse than saying nothing.
     *
     * @param lower the sentence, already lower-cased
     */
    static boolean boilerplateSentence(String lower) {
        if (lower.contains("enable javascript")
                || lower.contains("javascript is disabled")
                || lower.contains("javascript is required")
                || lower.contains("supported browser")
                || lower.contains("browser is out of date")
                || lower.contains("browser is not supported")
                || lower.contains("we use cookies")
                || lower.contains("accept all cookies")
                || lower.contains("cookie preferences")
                || lower.contains("cookie settings")
                || lower.contains("subscribe to our newsletter")
                || lower.contains("sign in to continue")
                || lower.contains("log in to continue")
                || lower.contains("full output was not retained")
                || lower.contains("characters omitted")) {
            return true;
        }
        // Navigation/table-of-contents runs: "Table of contents 1 Introduction
        // 2 Common infrastructure 3 Semantics …" — many bare-number tokens in
        // one "sentence" is a link rail, not prose.
        String[] tokens = lower.split("\\s+");
        if (tokens.length >= 8) {
            int numeric = 0;
            for (String token : tokens) {
                if (token.matches("\\d{1,3}")) numeric++;
            }
            if (numeric >= 4 && numeric * 4 >= tokens.length) return true;
        }
        return lower.contains("table of contents");
    }
}
