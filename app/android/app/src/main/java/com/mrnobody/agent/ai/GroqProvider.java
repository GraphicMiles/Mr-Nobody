package com.mrnobody.agent.ai;

/**
 * Groq (OpenAI-compatible API). Opt-in; used only once the user configures it.
 *
 * <p>The base URL is Groq's endpoint — that is what choosing "Groq" means, and
 * it is still editable. There is deliberately no default model: Groq retires
 * model ids (llama-3.3-70b-versatile went away and every install that had it
 * baked in started returning 404), so the list is fetched from the account.
 */
public final class GroqProvider extends OpenAiCompatibleProvider {

    /** Groq's API endpoint. Suggested, not enforced — the user can change it. */
    public static final String DEFAULT_BASE = "https://api.groq.com/openai/v1";

    public GroqProvider(String apiKey) {
        this(DEFAULT_BASE, "", apiKey);
    }

    public GroqProvider(String baseUrl, String model, String apiKey) {
        super("groq", "Groq",
                (baseUrl == null || baseUrl.trim().isEmpty()) ? DEFAULT_BASE : baseUrl,
                model,
                apiKey);
    }
}
