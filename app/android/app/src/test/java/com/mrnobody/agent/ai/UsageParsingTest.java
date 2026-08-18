package com.mrnobody.agent.ai;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The provider usage parsers: the token count that used to be thrown away is
 * now read back out of the response, in both providers' wire shapes.
 */
public class UsageParsingTest {

    @Test
    public void openAiUsageIsParsed() {
        JSONObject root = new JSONObject()
                .put("choices", new org.json.JSONArray())
                .put("usage", new JSONObject()
                        .put("prompt_tokens", 3200)
                        .put("completion_tokens", 400));
        TokenUsage u = OpenAiCompatibleProvider.usageOf(root);
        assertEquals(3200, u.promptTokens);
        assertEquals(400, u.completionTokens);
    }

    @Test
    public void openAiMissingUsageIsZero() {
        assertEquals(0, OpenAiCompatibleProvider.usageOf(new JSONObject()).totalTokens());
    }

    @Test
    public void geminiUsageMetadataIsParsed() {
        JSONObject root = new JSONObject()
                .put("usageMetadata", new JSONObject()
                        .put("promptTokenCount", 1500)
                        .put("candidatesTokenCount", 220));
        TokenUsage u = GeminiProvider.usageOf(root);
        assertEquals(1500, u.promptTokens);
        assertEquals(220, u.completionTokens);
    }

    @Test
    public void geminiMissingUsageIsZero() {
        assertEquals(0, GeminiProvider.usageOf(new JSONObject()).totalTokens());
    }
}
