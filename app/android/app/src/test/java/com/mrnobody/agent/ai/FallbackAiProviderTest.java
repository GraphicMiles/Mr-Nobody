package com.mrnobody.agent.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FallbackAiProviderTest {

    @Test
    public void explicitlyConfiguredSecondProviderReceivesFailedCompletion() {
        FakeProvider first = new FakeProvider("first", "HTTP 401 bad key", null);
        FakeProvider second = new FakeProvider("second", null, "answer");
        FallbackAiProvider chain = new FallbackAiProvider(Arrays.asList(first, second));
        AtomicReference<String> answer = new AtomicReference<>();

        chain.complete("system", "user", new AiProvider.CompletionCallback() {
            @Override public void onResult(String text) { answer.set(text); }
            @Override public void onError(String error) { answer.set("error:" + error); }
        });

        assertEquals("answer", answer.get());
        assertEquals(1, first.calls.get());
        assertEquals(1, second.calls.get());
        assertEquals("second", chain.lastProviderId());
    }

    @Test
    public void streamedPartialAnswerIsNeverSplicedWithAnotherProvider() {
        StreamingFailure first = new StreamingFailure();
        FakeProvider second = new FakeProvider("second", null, "other");
        FallbackAiProvider chain = new FallbackAiProvider(Arrays.asList(first, second));
        StringBuilder seen = new StringBuilder();

        chain.stream("s", "u", new AiProvider.StreamCallback() {
            @Override public void onToken(String token) { seen.append(token); }
            @Override public void onDone(String fullText) { seen.append("done"); }
            @Override public void onError(String error) { seen.append("|error"); }
        });

        assertEquals("partial|error", seen.toString());
        assertEquals(0, second.calls.get());
    }

    @Test
    public void chainRejectsLocalEchoProvider() {
        boolean threw = false;
        try { new FallbackAiProvider(Arrays.asList(new LocalProvider())); }
        catch (IllegalArgumentException expected) { threw = true; }
        assertTrue(threw);
    }

    private static class FakeProvider implements AiProvider {
        final String id;
        final String error;
        final String answer;
        final AtomicInteger calls = new AtomicInteger();

        FakeProvider(String id, String error, String answer) {
            this.id = id;
            this.error = error;
            this.answer = answer;
        }
        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public boolean isRemote() { return true; }
        @Override public String modelId() { return "model"; }
        @Override public void complete(String s, String u, CompletionCallback callback) {
            calls.incrementAndGet();
            if (error != null) callback.onError(error); else callback.onResult(answer);
        }
    }

    private static final class StreamingFailure extends FakeProvider {
        StreamingFailure() { super("stream", null, null); }
        @Override
        public RequestHandle streamCancellable(String s, String u, StreamCallback callback) {
            callback.onToken("partial");
            callback.onError("connection reset");
            return RequestHandle.NONE;
        }
    }
}
