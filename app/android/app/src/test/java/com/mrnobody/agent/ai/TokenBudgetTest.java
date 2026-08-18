package com.mrnobody.agent.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Token usage, pricing, and the context budget. The invariant that matters
 * most: compaction never overflows and never drops everything, and a token
 * count is always positive for non-empty text.
 */
public class TokenBudgetTest {

    // ------------------------------------------------------------- TokenUsage

    @Test
    public void usageAccumulates() {
        TokenUsage a = new TokenUsage(100, 50);
        TokenUsage b = new TokenUsage(20, 10);
        TokenUsage sum = a.add(b);
        assertEquals(120, sum.promptTokens);
        assertEquals(60, sum.completionTokens);
        assertEquals(180, sum.totalTokens());
    }

    @Test
    public void usageClampsNegatives() {
        assertEquals(0, new TokenUsage(-5, -5).totalTokens());
    }

    @Test
    public void usageDescribesWithUsd() {
        TokenUsage u = new TokenUsage(1_000_000, 1_000_000);
        ModelPricing.Price p = new ModelPricing.Price(0.10, 0.40);
        assertEquals(0.10 + 0.40, u.estimateUsd(p), 0.0001);
        String d = u.describe(p);
        assertTrue(d.contains("1,000,000"));
        assertTrue(d.contains("$0.5000"));
    }

    @Test
    public void zeroUsageDescribesEmpty() {
        assertEquals("", TokenUsage.ZERO.describe(new ModelPricing.Price(1, 1)));
    }

    // ------------------------------------------------------------ ModelPricing

    @Test
    public void pricingMatchesModelFamilies() {
        // Gemini flash vs pro differ.
        assertTrue(ModelPricing.forModel("gemini-2.5-pro").outputUsdPerMillion
                > ModelPricing.forModel("gemini-2.0-flash").outputUsdPerMillion);
        // Unknown falls back to the generic price (never null).
        ModelPricing.Price unknown = ModelPricing.forModel("some-unknown-model");
        assertTrue(unknown.inputUsdPerMillion > 0);
    }

    // ------------------------------------------------------------ TokenBudget

    @Test
    public void estimateIsMonotonicAndPositive() {
        assertEquals(0, TokenBudget.estimateTokens(null));
        assertEquals(0, TokenBudget.estimateTokens(""));
        assertTrue(TokenBudget.estimateTokens("hello world") > 0);
        assertTrue(TokenBudget.estimateTokens("a very long string") 
                > TokenBudget.estimateTokens("short"));
    }

    @Test
    public void contextWindowKnowsFamilies() {
        assertTrue(TokenBudget.contextWindow("gemini-2.5-pro")
                > TokenBudget.contextWindow("gemini-2.0-flash"));
        assertTrue(TokenBudget.contextWindow("llama-3.3-70b-versatile") == 128_000);
    }

    @Test
    public void compactDropsOldestFirst() {
        List<String> lines = new ArrayList<>();
        // Enough lines to clearly exceed a small budget.
        for (int i = 0; i < 10; i++) {
            lines.add("line " + i + " " + repeat("x", 100));
        }
        List<String> trimmed = TokenBudget.compact(lines, TokenBudget.estimateTokens(
                "line 9 " + repeat("x", 100)) + 10);

        assertTrue(trimmed.size() < lines.size());
        // The newest line survives; the oldest is dropped.
        assertTrue(trimmed.get(trimmed.size() - 1).startsWith("line 9"));
        assertTrue(!trimmed.get(0).startsWith("line 0"));
    }

    @Test
    public void compactAlwaysKeepsAtLeastOneLine() {
        List<String> lines = Arrays.asList("only line " + repeat("x", 5000));
        List<String> trimmed = TokenBudget.compact(lines, 10);
        assertEquals(1, trimmed.size());
    }

    @Test
    public void compactIsSafeOnEmpty() {
        assertEquals(0, TokenBudget.compact(null, 100).size());
        assertEquals(0, TokenBudget.compact(new ArrayList<>(), 100).size());
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
