package com.mrnobody.agent.ai;

import java.util.Locale;

/**
 * The token usage a provider reported for one call — read from the API
 * response, never guessed.
 *
 * <p>Before this existed, the providers parsed the answer and threw away the
 * {@code usage} block the API already returned, so a run could spend tokens
 * with no record of how many. This is the authoritative count: prompt tokens
 * in, completion tokens out, as the provider measured them.
 */
public final class TokenUsage {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public final long promptTokens;
    public final long completionTokens;

    public TokenUsage(long promptTokens, long completionTokens) {
        this.promptTokens = Math.max(0, promptTokens);
        this.completionTokens = Math.max(0, completionTokens);
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public TokenUsage add(TokenUsage other) {
        if (other == null) return this;
        return new TokenUsage(promptTokens + other.promptTokens,
                completionTokens + other.completionTokens);
    }

    /**
     * A rough USD cost. "Rough" on purpose: prices are approximate per-model
     * figures (see {@link ModelPricing}), and the provider's own billing is
     * authoritative.
     */
    public double estimateUsd(ModelPricing.Price price) {
        if (price == null) return 0;
        return promptTokens * price.inputUsdPerMillion / 1_000_000.0
                + completionTokens * price.outputUsdPerMillion / 1_000_000.0;
    }

    /** A one-line summary for the task result. Empty when nothing was spent. */
    public String describe(ModelPricing.Price price) {
        if (totalTokens() == 0) return "";
        double usd = estimateUsd(price);
        return String.format(Locale.ROOT,
                "Used %,d prompt + %,d completion tokens (~$%.4f).",
                promptTokens, completionTokens, usd);
    }

    @Override
    public String toString() {
        return "TokenUsage{prompt=" + promptTokens + ", completion=" + completionTokens + "}";
    }
}
