package com.mrnobody.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A tool's declared output: the shape of its canonical value, and the pure
 * projections from that value to what a model reads and what the UI shows.
 *
 * <p>The point is that a tool returns <em>one</em> structured value and the two
 * audiences are derived from it. A tool that hand-writes model text can quietly
 * hand over a page scrape; a tool whose value must satisfy
 * {@code {results:[{title,url,snippet}]}} cannot.
 */
public final class OutputSpec {

    /** Pure projection: value → the text a model sees. No side effects. */
    public interface Renderer {
        String render(Map<String, Object> value);
    }

    private final List<String> requiredKeys;
    private final Renderer renderer;
    private final boolean allowMarkup;

    private OutputSpec(List<String> requiredKeys, Renderer renderer, boolean allowMarkup) {
        this.requiredKeys = requiredKeys == null ? Collections.emptyList() : requiredKeys;
        this.renderer = renderer;
        this.allowMarkup = allowMarkup;
    }

    public static OutputSpec of(Renderer renderer, String... requiredKeys) {
        return new OutputSpec(List.of(requiredKeys), renderer, false);
    }

    /**
     * For the rare tool whose job really is to carry markup. Nothing uses this
     * today, and adding a caller should require an argument.
     */
    public static OutputSpec markupAllowed(Renderer renderer, String... requiredKeys) {
        return new OutputSpec(List.of(requiredKeys), renderer, true);
    }

    public List<String> requiredKeys() {
        return requiredKeys;
    }

    /**
     * Check a canonical value before anyone sees it. Returns null when
     * acceptable, otherwise why not.
     */
    public String validate(Map<String, Object> value) {
        if (value == null) return "tool returned no value";
        for (String key : requiredKeys) {
            if (!value.containsKey(key)) return "result is missing \"" + key + "\"";
        }
        if (!allowMarkup) {
            String offender = findMarkup(value, 0);
            if (offender != null) {
                // The whole reason the contract exists: a tool whose purpose is
                // structured extraction must not smuggle the raw page through.
                return "result carries raw markup in \"" + offender
                        + "\" — extract structure instead of returning the page";
            }
        }
        return null;
    }

    public String render(Map<String, Object> value) {
        if (renderer == null) return String.valueOf(value);
        String text = renderer.render(value);
        return text == null ? "" : text;
    }

    // ------------------------------------------------------------- markup

    private static String findMarkup(Object node, int depth) {
        if (depth > 6) return null;
        if (node instanceof String) {
            return looksLikeMarkup((String) node) ? "(text)" : null;
        }
        if (node instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                String found = findMarkup(e.getValue(), depth + 1);
                if (found != null) {
                    return "(text)".equals(found) ? String.valueOf(e.getKey())
                            : e.getKey() + "." + found;
                }
            }
            return null;
        }
        if (node instanceof Iterable) {
            int i = 0;
            for (Object item : (Iterable<?>) node) {
                String found = findMarkup(item, depth + 1);
                if (found != null) {
                    return "(text)".equals(found) ? "[" + i + "]" : "[" + i + "]." + found;
                }
                i++;
            }
        }
        return null;
    }

    /**
     * Whether a string is a document rather than prose. Two signals, both
     * chosen to avoid firing on ordinary text that happens to mention a tag:
     * an explicit document header, or a high density of closing tags.
     */
    public static boolean looksLikeMarkup(String text) {
        if (text == null || text.length() < 32) return false;
        String head = text.substring(0, Math.min(text.length(), 4096)).toLowerCase();
        if (head.contains("<!doctype html") || head.contains("<html")) return true;
        if (head.contains("<script") || head.contains("<iframe")) return true;
        int closingTags = 0;
        for (int i = 0; i + 2 < text.length(); i++) {
            if (text.charAt(i) == '<' && text.charAt(i + 1) == '/'
                    && Character.isLetter(text.charAt(i + 2))) {
                if (++closingTags >= 10) return true;
            }
        }
        return false;
    }

    /** Default rendering for a value with no renderer: one line per entry. */
    public static String describe(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> e : value.entrySet()) {
            lines.add(e.getKey() + ": " + e.getValue());
        }
        return String.join("\n", lines);
    }
}
