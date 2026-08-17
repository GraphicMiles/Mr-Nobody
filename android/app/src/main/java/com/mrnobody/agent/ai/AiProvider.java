package com.mrnobody.agent.ai;

/**
 * Abstraction over where the "brain" runs. V1 ships a {@link LocalProvider}
 * (deterministic, no network) plus remote providers behind this interface;
 * remote providers are optional and opt-in. Basic browsing never requires AI.
 */
public interface AiProvider {

    /** Stable id: "local", "gemini", "groq", "openai-compatible", ... */
    String id();

    /** Human-readable name. */
    String displayName();

    /** True if this provider sends data off-device. */
    boolean isRemote();

    /**
     * Produce a completion. Implementations must run off the UI thread and
     * return via the callback. Never called unless the user enabled this
     * provider explicitly.
     */
    void complete(String systemPrompt, String userMessage, CompletionCallback callback);

    interface CompletionCallback {
        void onResult(String text);
        void onError(String error);
    }
}
