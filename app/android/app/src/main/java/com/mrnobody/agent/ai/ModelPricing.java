package com.mrnobody.agent.ai;

import java.util.Locale;

/**
 * Rough per-model USD pricing, used only to put a cost figure on a run.
 *
 * <p>These are approximate list prices, matched by model-name substring, and
 * they will drift — which is exactly why the README-level claim is "rough
 * estimate", never a bill. The provider's own invoice is authoritative. Model
 * ids are the most perishable thing in this system (they are fetched, not
 * baked in), so the lookup is deliberately lenient: it matches on a few stable
 * signals and falls back to a generic figure rather than failing.
 */
public final class ModelPricing {

    /** USD per one million tokens, input and output. */
    public static final class Price {
        public final double inputUsdPerMillion;
        public final double outputUsdPerMillion;

        public Price(double inputUsdPerMillion, double outputUsdPerMillion) {
            this.inputUsdPerMillion = inputUsdPerMillion;
            this.outputUsdPerMillion = outputUsdPerMillion;
        }
    }

    /** Generic fallback for any unknown OpenAI-compatible model. */
    private static final Price DEFAULT = new Price(1.00, 3.00);

    private ModelPricing() {
    }

    /** Best-effort price for a model id, never null. */
    public static Price forModel(String modelId) {
        String m = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);

        if (m.contains("gemini")) {
            if (m.contains("flash-lite")) return new Price(0.025, 0.10);
            if (m.contains("flash")) return new Price(0.10, 0.40);
            if (m.contains("pro")) return new Price(1.25, 10.00);
            return new Price(0.30, 2.50);
        }
        if (m.contains("groq")) {
            // Groq bills per token; common Llama-family prices.
            if (m.contains("8b") || m.contains("3.1") || m.contains("3.2")) {
                return new Price(0.05, 0.08);
            }
            if (m.contains("70b")) return new Price(0.59, 0.79);
            return new Price(0.20, 0.40);
        }
        if (m.contains("gpt-oss") || m.contains("gpt-4.1") || m.contains("gpt-5")) {
            return new Price(0.50, 1.50);
        }
        if (m.contains("qwen")) return new Price(0.20, 0.60);
        if (m.contains("allam")) return new Price(0.10, 0.30);
        return DEFAULT;
    }
}
