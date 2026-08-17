package com.mrnobody.agent.ai;

/**
 * Groq (OpenAI-compatible API). Pass the API key from settings; the provider
 * is opt-in and only used when the user enables it.
 */
public final class GroqProvider extends OpenAiCompatibleProvider {

    public GroqProvider(String apiKey) {
        super("groq", "Groq", "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile", apiKey);
    }
}
