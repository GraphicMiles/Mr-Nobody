package com.mrnobody.agent.ai;

import java.util.Locale;

/**
 * The CFO circuit breaker: a hard ceiling on what a single run may spend.
 *
 * <p>Context budgeting (see {@link TokenBudget}) stops a prompt overflowing the
 * model's window; this stops a run emptying the user's wallet. It is a per-run
 * dollar cap: before the loop makes another call, it asks whether the run's
 * spend-so-far <em>plus an estimate of the next call</em> still fits under the
 * ceiling. When it would not, the run stops with a clear reason — exactly as
 * Sovereign-OS's CFO refuses a task it cannot afford.
 *
 * <p>The spend-so-far is the run's accumulated {@link TokenUsage} (the
 * provider's authoritative count); the next call is estimated from its prompt
 * length with the chars-per-token heuristic. Stateless and pure, so the one
 * source of truth for spend stays in the engine's {@code Research.usage} — this
 * class only decides, it never accumulates.
 */
public final class SpendCap {

    /** A never-triggering cap (the default when no ceiling is configured). */
    public static final double NO_LIMIT = Double.MAX_VALUE;

    /**
     * Per-run dollar ceiling. ~3× a typical hosted research task, so a
     * normal browse does not trip it and a runaway loop still cannot
     * empty a key.
     */
    public static final double DEFAULT_RUN_USD = 3.00;

    private final double capUsd;
    private final ModelPricing.Price price;

    public SpendCap(double capUsd, ModelPricing.Price price) {
        this.capUsd = capUsd <= 0 ? NO_LIMIT : capUsd;
        this.price = price == null ? new ModelPricing.Price(0, 0) : price;
    }

    public double capUsd() {
        return capUsd;
    }

    /**
     * May the run make another call whose prompt is {@code promptChars}
     * characters, given it has already spent {@code spent}? Returns a reason
     * string when refused, null when allowed.
     */
    public String check(TokenUsage spent, long promptChars) {
        double spentUsd = spent.estimateUsd(price);
        double estUsd = estimateCost(promptChars);
        if (spentUsd + estUsd > capUsd) {
            return String.format(Locale.ROOT,
                    "Spend cap reached: this run has used ~$%.4f and the next call "
                            + "would push it past the ~$%.4f ceiling.",
                    spentUsd, capUsd);
        }
        return null;
    }

    /** The estimated USD cost of a prompt of {@code chars} characters. */
    public double estimateCost(long promptChars) {
        long tokens = TokenBudget.estimateTokens(promptChars);
        return tokens * price.inputUsdPerMillion / 1_000_000.0;
    }
}
