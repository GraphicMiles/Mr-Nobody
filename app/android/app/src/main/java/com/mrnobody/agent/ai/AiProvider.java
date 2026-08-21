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
     * The model this provider is configured for, or "" when unknown (e.g. the
     * local provider has no model). Used for context-window budgeting and
     * rough pricing; the empty string means "use a conservative default".
     */
    default String modelId() {
        return "";
    }

    /**
     * Produce a completion. Implementations must run off the UI thread and
     * return via the callback. Never called unless the user enabled this
     * provider explicitly.
     */
    void complete(String systemPrompt, String userMessage, CompletionCallback callback);

    /**
     * Produce a completion as a stream of tokens, emitted as they arrive.
     *
     * <p>This is the real-time counterpart of {@link #complete}: where
     * complete() reads the whole response and returns it once, stream() emits
     * each piece the moment the provider sends it. The default implementation
     * is the honest fallback — it calls {@link #complete} and surfaces the
     * finished text as a single token — so a provider that cannot stream still
     * works through this interface and a caller never has to branch on
     * capability. Implementations must run off the UI thread, as complete()
     * does.
     */
    default void stream(String systemPrompt, String userMessage, StreamCallback callback) {
        complete(systemPrompt, userMessage, new CompletionCallback() {
            @Override public void onResult(String text) {
                callback.onToken(text);
                callback.onDone(text);
            }
            @Override public void onError(String error) {
                callback.onError(error);
            }
            @Override public void onUsage(TokenUsage usage) {
                callback.onUsage(usage);
            }
        });
    }

    /** Handle for aborting an in-flight provider request. */
    interface RequestHandle {
        RequestHandle NONE = () -> { };
        void cancel();
    }

    /**
     * Start a stream and return something that can abort its socket/thread.
     * Providers that have not implemented cancellation keep working through
     * the legacy method, but remote HTTP providers must override this.
     */
    default RequestHandle streamCancellable(String systemPrompt, String userMessage,
                                            StreamCallback callback) {
        stream(systemPrompt, userMessage, callback);
        return RequestHandle.NONE;
    }

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

        /**
         * The provider's reported token usage for this call. Called (when the
         * provider can report it) before {@link #onResult}. Default ignores it,
         * so an implementation that does not capture usage needs no change.
         */
        default void onUsage(TokenUsage usage) { }
    }

    /**
     * Receives a streamed completion. {@link #onToken} is called once per
     * piece (a word, a line, a fragment — whatever granularity the provider
     * chose), then exactly one of {@link #onDone} or {@link #onError} closes
     * the stream. {@code fullText} on {@code onDone} is the whole accumulated
     * answer, so a caller that only wanted the finished string needs no other
     * bookkeeping.
     */
    interface StreamCallback {
        void onToken(String token);

        void onDone(String fullText);

        void onError(String error);

        /** The provider's reported usage, delivered alongside {@link #onDone}. */
        default void onUsage(TokenUsage usage) { }
    }

    interface ModelsCallback {
        void onModels(List<String> modelIds);

        default void onError(String error) { }
    }
}
