package com.mrnobody.agent.ai;

/**
 * On-device "AI" — V1's deterministic provider. It performs no real reasoning
 * and makes no network call; it echoes the instruction so the vertical slice
 * works end-to-end without any external dependency. V2 can replace this with an
 * on-device model behind the same interface.
 */
public final class LocalProvider implements AiProvider {

    @Override
    public String id() {
        return "local";
    }

    @Override
    public String displayName() {
        return "Local (on-device)";
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
