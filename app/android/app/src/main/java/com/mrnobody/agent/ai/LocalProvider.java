package com.mrnobody.agent.ai;

/**
 * The no-model provider. It performs no reasoning and makes no network call.
 *
 * <p>Research answers on this path are extractive ({@code ExtractiveAnswer}),
 * not this echo. The echo exists so a caller that hits {@code complete()}
 * still gets a string instead of hanging. Do not describe this as an AI agent.
 */
public final class LocalProvider implements AiProvider {

    @Override
    public String id() {
        return "local";
    }

    @Override
    public String displayName() {
        return "Local (no model)";
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    @Override
    public void complete(String systemPrompt, String userMessage, CompletionCallback callback) {
        // Deterministic echo — proves the interface without a network call.
        callback.onResult("Local provider: " + userMessage);
    }
}
