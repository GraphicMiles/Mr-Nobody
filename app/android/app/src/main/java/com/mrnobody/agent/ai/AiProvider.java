package com.mrnobody.agent.ai;

import java.util.List;

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

    /**
     * Ask the provider which models the user's key can actually use.
     *
     * <p>Model names are the most perishable thing in this whole system —
     * providers retire them without notice, and a hardcoded id turns into a
     * 404 that reads like a bug in the app. Nothing here ships a model list:
     * we ask, the user picks.
     */
    default void listModels(ModelsCallback callback) {
        callback.onModels(List.of());
    }

    interface CompletionCallback {
        void onResult(String text);

        void onError(String error);
    }

    interface ModelsCallback {
        void onModels(List<String> modelIds);

        default void onError(String error) { }
    }
}
