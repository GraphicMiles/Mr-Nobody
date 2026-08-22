package com.mrnobody.agent.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One conservative text edit derived from an explicit user instruction. */
final class CanvaEditPlan {
    final String transactionId;
    final int pageIndex;
    final String elementId;
    final String oldText;
    final String newText;
    final boolean responsive;
    final String preview;

    CanvaEditPlan(String transactionId, int pageIndex, String elementId,
                  String oldText, String newText, boolean responsive, String preview) {
        this.transactionId = transactionId;
        this.pageIndex = pageIndex;
        this.elementId = elementId;
        this.oldText = oldText;
        this.newText = newText;
        this.responsive = responsive;
        this.preview = preview;
    }

    static CanvaEditPlan from(JSONObject opened, String instruction) {
        if (opened == null) return null;
        JSONObject transaction = opened.optJSONObject("transaction");
        String transactionId = transaction == null ? ""
                : transaction.optString("transaction_id", transaction.optString("id", ""));
        if (transactionId.isEmpty()) return null;
        Pair pair = Pair.parse(instruction);
        JSONArray richtexts = opened.optJSONArray("richtexts");
        if (richtexts == null || richtexts.length() == 0 || pair == null) return null;
        JSONObject selected = null;
        String selectedText = "";
        for (int i = 0; i < richtexts.length(); i++) {
            JSONObject candidate = richtexts.optJSONObject(i);
            String text = textOf(candidate);
            if (selected == null) { selected = candidate; selectedText = text; }
            if (!pair.oldText.isEmpty() && text.toLowerCase(Locale.ROOT)
                    .contains(pair.oldText.toLowerCase(Locale.ROOT))) {
                selected = candidate; selectedText = text; break;
            }
        }
        if (selected == null) return null;
        String element = selected.optString("element_id", "");
        if (element.isEmpty()) return null;
        int page = Math.max(1, selected.optInt("page_index", 1));
        boolean responsive = false;
        JSONArray pages = opened.optJSONArray("pages");
        if (pages != null) {
            for (int i = 0; i < pages.length(); i++) {
                JSONObject p = pages.optJSONObject(i);
                if (p != null && p.optInt("page_number", p.optInt("page_index", 0)) == page) {
                    responsive = p.optBoolean("is_responsive", false); break;
                }
            }
        }
        String old = pair.oldText.isEmpty() ? selectedText : pair.oldText;
        return new CanvaEditPlan(transactionId, page, element, old,
                pair.newText, responsive, thumbnail(opened));
    }

    private static String textOf(JSONObject richtext) {
        if (richtext == null) return "";
        StringBuilder out = new StringBuilder();
        JSONArray regions = richtext.optJSONArray("regions");
        if (regions != null) {
            for (int i = 0; i < regions.length(); i++) {
                JSONObject region = regions.optJSONObject(i);
                if (region != null) out.append(region.optString("text", ""));
            }
        }
        return out.toString();
    }

    private static String thumbnail(JSONObject value) {
        JSONArray thumbs = value.optJSONArray("thumbnails");
        JSONObject first = thumbs == null ? null : thumbs.optJSONObject(0);
        return first == null ? "" : first.optString("url", "");
    }

    private static final class Pair {
        final String oldText;
        final String newText;
        Pair(String oldText, String newText) { this.oldText = oldText; this.newText = newText; }

        static Pair parse(String instruction) {
            String text = instruction == null ? "" : instruction.trim();
            Pattern quoted = Pattern.compile(
                    "(?i)(?:change|replace)\\s+[\\\"']([^\\\"']+)[\\\"']\\s+(?:to|with)\\s+[\\\"']([^\\\"']+)[\\\"']");
            Matcher q = quoted.matcher(text);
            if (q.find()) return new Pair(q.group(1).trim(), q.group(2).trim());
            return null; // never guess which element “headline” means
        }
    }
}
