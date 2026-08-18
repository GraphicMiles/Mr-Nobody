package com.mrnobody.agent.ai;

import java.util.List;
import java.util.Locale;

/**
 * Pick a cheap chat model for a short reasoning step (classify, cancel)
 * and keep the user's chosen model for the answer.
 *
 * <p>OpenSquilla's router is a full tier ladder. Mr Nobody only needs the
 * first rung: the classify call is a JSON label, and spending a frontier
 * model on it is waste. If the catalogue has a flash/mini/lite/haiku id,
 * use that; otherwise stay on the model the user picked.
 */
public final class ModelRouter {

    private static final String[] CHEAP = {
            "flash", "mini", "lite", "haiku", "instant", "8b", "7b", "small",
    };

    private ModelRouter() {
    }

    public static boolean isCheap(String modelId) {
        if (modelId == null || modelId.isEmpty()) return false;
        if (!ModelCatalog.looksLikeChatModel(modelId)) return false;
        String m = modelId.toLowerCase(Locale.ROOT);
        // Token match, not substring: "mini" lives inside "gemini".
        String[] parts = m.split("[^a-z0-9]+");
        for (String part : parts) {
            for (String marker : CHEAP) {
                if (part.equals(marker)) return true;
            }
        }
        return false;
    }

    /**
     * A cheap chat id from {@code catalog}, or {@code current} when none
     * looks cheaper. Never returns a non-chat model.
     */
    public static String pickCheap(List<String> catalog, String current) {
        if (isCheap(current)) return current;
        if (catalog != null) {
            for (String id : catalog) {
                if (isCheap(id)) return id;
            }
        }
        return current == null ? "" : current;
    }
}
