package com.mrnobody.agent.ai;

/**
 * Groq (OpenAI-compatible API). Pass the API key from settings; the provider
 * is opt-in and only used when the user enables it.
 *
 * Defaults point at Groq's free tier: https://api.groq.com/openai/v1
 */
public final class GroqProvider extends OpenAiCompatibleProvider {

    /** Free-tier default (Groq). */
    public static final String DEFAULT_BASE = "https://api.groq.com/openai/v1";
    public static final String DEFAULT_MODEL = "llama-3.3-70b-versatile";

    public GroqProvider(String apiKey) {
        this(DEFAULT_BASE, DEFAULT_MODEL, apiKey);
    }

    public GroqProvider(String baseUrl, String model, String apiKey) {
        super("groq", "Groq",
                (baseUrl == null || baseUrl.trim().isEmpty()) ? DEFAULT_BASE : baseUrl,
                (model == null || model.trim().isEmpty()) ? DEFAULT_MODEL : model,
                apiKey);
    }
}
