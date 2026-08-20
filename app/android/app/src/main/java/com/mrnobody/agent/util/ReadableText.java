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
}
