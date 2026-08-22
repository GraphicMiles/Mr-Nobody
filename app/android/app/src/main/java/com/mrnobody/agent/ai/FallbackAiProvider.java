package com.mrnobody.agent.ai;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.resilience.FailureClassifier;
import com.mrnobody.agent.resilience.OperationFailure;
import com.mrnobody.agent.resilience.RetryPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A run-pinned, explicitly consented remote-provider chain.
 *
 * <p>Fallback is confined to model calls. Tool execution stays in the same run
 * and reuses the execution ledger, so changing the brain never replays an
 * already committed external effect.
 */
public final class FallbackAiProvider implements AiProvider {

    private final List<AiProvider> providers;
    private final AtomicReference<String> lastProvider = new AtomicReference<>("");

    public FallbackAiProvider(List<AiProvider> providers) {
        List<AiProvider> copy = new ArrayList<>();
        if (providers != null) {
            for (AiProvider provider : providers) {
                if (provider != null && provider.isRemote()) copy.add(provider);
            }
        }
        if (copy.isEmpty()) throw new IllegalArgumentException("remote provider chain is empty");
        this.providers = Collections.unmodifiableList(copy);
        lastProvider.set(copy.get(0).id());
    }

    @Override public String id() { return providers.get(0).id(); }
    @Override public String displayName() { return providers.get(0).displayName(); }
    @Override public boolean isRemote() { return true; }
    @Override public String modelId() { return providers.get(0).modelId(); }

    public String lastProviderId() { return lastProvider.get(); }
    public List<AiProvider> providers() { return providers; }

    @Override
    public void complete(String systemPrompt, String userMessage, CompletionCallback callback) {
        completeAt(0, 0, systemPrompt, userMessage, callback, new ArrayList<>());
    }

    private void completeAt(int providerIndex, int attempt, String system, String user,
                            CompletionCallback callback, List<String> errors) {
        if (providerIndex >= providers.size()) {
            callback.onError(summary(errors));
            return;
        }
        AiProvider provider = providers.get(providerIndex);
        lastProvider.set(provider.id());
        provider.complete(system, user, new CompletionCallback() {
            @Override public void onResult(String text) {
                callback.onResult(text);
            }

            @Override public void onError(String error) {
                errors.add(provider.displayName() + ": " + safe(error));
                OperationFailure failure = FailureClassifier.fromMessage(error);
                if (RetryPolicy.shouldRetry(failure, attempt, Tier.READ, true)
                        && !failure.ambiguous) {
                    waitThen(RetryPolicy.delayMs(failure, attempt),
                            () -> completeAt(providerIndex, attempt + 1,
                                    system, user, callback, errors));
                } else {
                    completeAt(providerIndex + 1, 0, system, user, callback, errors);
                }
            }

            @Override public void onUsage(TokenUsage usage) {
                callback.onUsage(usage);
            }
        });
    }

    @Override
    public RequestHandle streamCancellable(String systemPrompt, String userMessage,
                                           StreamCallback callback) {
        StreamAttempt state = new StreamAttempt(systemPrompt, userMessage, callback);
        state.start(0, 0);
        return state::cancel;
    }

    @Override
    public void stream(String systemPrompt, String userMessage, StreamCallback callback) {
        streamCancellable(systemPrompt, userMessage, callback);
    }

    @Override
    public void listModels(ModelsCallback callback) {
        providers.get(0).listModels(callback);
    }

    private final class StreamAttempt {
        final String system;
        final String user;
        final StreamCallback callback;
        final List<String> errors = new ArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean emitted = new AtomicBoolean();
        final AtomicReference<RequestHandle> active = new AtomicReference<>(RequestHandle.NONE);

        StreamAttempt(String system, String user, StreamCallback callback) {
            this.system = system;
            this.user = user;
            this.callback = callback;
        }

        void start(int providerIndex, int attempt) {
            if (cancelled.get()) return;
            if (providerIndex >= providers.size()) {
                callback.onError(summary(errors));
                return;
            }
            AiProvider provider = providers.get(providerIndex);
            lastProvider.set(provider.id());
            RequestHandle handle = provider.streamCancellable(system, user,
                    new StreamCallback() {
                @Override public void onToken(String token) {
                    if (cancelled.get()) return;
                    emitted.set(true);
                    callback.onToken(token);
                }

                @Override public void onDone(String fullText) {
                    if (!cancelled.get()) callback.onDone(fullText);
                }

                @Override public void onError(String error) {
                    if (cancelled.get()) return;
                    errors.add(provider.displayName() + ": " + safe(error));
                    // Once any token reached the user, another provider would
                    // splice two different answers into one stream.
                    if (emitted.get()) {
                        callback.onError(error);
                        return;
                    }
                    OperationFailure failure = FailureClassifier.fromMessage(error);
                    if (RetryPolicy.shouldRetry(failure, attempt, Tier.READ, true)
                            && !failure.ambiguous) {
                        waitThen(RetryPolicy.delayMs(failure, attempt),
                                () -> start(providerIndex, attempt + 1));
                    } else {
                        start(providerIndex + 1, 0);
                    }
                }

                @Override public void onUsage(TokenUsage usage) {
                    if (!cancelled.get()) callback.onUsage(usage);
                }
            });
            active.set(handle == null ? RequestHandle.NONE : handle);
            if (cancelled.get()) active.get().cancel();
        }

        void cancel() {
            if (cancelled.compareAndSet(false, true)) active.get().cancel();
        }
    }

    private static void waitThen(long delayMs, Runnable next) {
        new Thread(() -> {
            try { Thread.sleep(Math.max(0L, delayMs)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            next.run();
        }, "provider-fallback").start();
    }

    private static String summary(List<String> errors) {
        if (errors == null || errors.isEmpty()) return "Every configured AI provider failed.";
        String joined = String.join("; ", errors);
        return joined.length() <= 700 ? joined : joined.substring(0, 700) + "…";
    }

    private static String safe(String error) {
        if (error == null || error.trim().isEmpty()) return "unknown failure";
        String clean = error.trim().replaceAll("\\s+", " ");
        return clean.length() <= 240 ? clean : clean.substring(0, 240) + "…";
    }
}
