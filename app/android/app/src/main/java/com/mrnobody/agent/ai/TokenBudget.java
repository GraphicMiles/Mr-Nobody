package com.mrnobody.agent.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Context budgeting: does this fit, and what to drop when it does not.
 *
 * <p>Input tokens cannot be known before a call, so the prompt side is
 * <em>estimated</em> with a chars-per-token heuristic; the completion side is
 * authoritative (see {@link TokenUsage}). The two jobs here are (1) estimate
 * how much a prompt will cost, and (2) trim the autonomous loop's growing
 * transcript when it approaches the model's context window — drop the oldest,
 * lowest-value lines, keep the newest, and never overflow mid-task.
 *
 * <p>Pure and JVM-tested: the window sizes are per-model-family figures, and
 * the compaction decision is a deterministic function of a list of strings.
 */
public final class TokenBudget {

    /** Rough English chars per token. Conservative (under-estimates tokens). */
    private static final double CHARS_PER_TOKEN = 3.7;

    private TokenBudget() {
    }

    /** Estimate the token count of a string. Cheap, not exact. */
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return estimateTokens(text.length());
    }

    /** Estimate the token count of {@code chars} characters. Cheap, not exact. */
    public static long estimateTokens(long chars) {
        if (chars <= 0) return 0;
        return Math.max(1, Math.round(chars / CHARS_PER_TOKEN));
    }

    /** Rough context-window size per model family. */
    public static long contextWindow(String modelId) {
        String m = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        if (m.contains("gemini")) {
            if (m.contains("pro")) return 2_000_000L;
            return 1_000_000L; // flash
        }
        if (m.contains("gpt-oss") || m.contains("gpt-4.1") || m.contains("gpt-5")) {
            return 1_000_000L;
        }
        // Groq Llama-family and generic OpenAI-compatible: assume 128k.
        return 128_000L;
    }

    /**
     * How much of the window the working transcript may use, leaving room for
     * the system prompt, the grounded sources, and the answer itself.
     */
    public static long transcriptBudget(String modelId) {
        return contextWindow(modelId) / 2;
    }

    /**
     * Trim {@code lines} to fit within {@code maxTokens} by dropping the oldest
     * lines first (newest are the most relevant to what the agent is doing
     * now). Always keeps at least one line, so the model never loses all
     * context. Returns a new list; the input is untouched.
     */
    public static List<String> compact(List<String> lines, long maxTokens) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        out.addAll(lines);

        long total = 0;
        for (String s : out) total += estimateTokens(s);

        while (out.size() > 1 && total > maxTokens) {
            total -= estimateTokens(out.remove(0));
        }
        return out;
    }
}
