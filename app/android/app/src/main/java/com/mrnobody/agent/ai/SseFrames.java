package com.mrnobody.agent.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Minimal Server-Sent Events reader for the provider streaming endpoints.
 *
 * <p>Chat-completions streaming responses are SSE: a sequence of
 * blank-line-separated events, each a {@code data:} line carrying one JSON
 * frame, ended by a {@code data: [DONE]} marker. This reads exactly that shape
 * and nothing else — no event names, no {@code retry:}, no {@code id:} —
 * because that is all the two remote providers emit. It deliberately ignores
 * everything it does not understand rather than failing on it: a provider
 * adding a comment line or a keep-alive must not break a client that is only
 * interested in the payloads.
 *
 * <p>Kept separate from any provider so the frame parsing can be tested
 * against canned bytes without a network, which is how a malformed-frame bug
 * is caught in milliseconds instead of against a live, paying endpoint.
 */
public final class SseFrames {

    /** The marker both providers use to say the stream is over. */
    public static final String DONE = "[DONE]";

    private SseFrames() {
    }

    /** Receives each payload in order. May throw {@link IOException} to abort. */
    public interface FrameHandler {
        void onData(String json) throws IOException;
    }

    /**
     * Read frames until EOF or {@code [DONE]}. The return value exposes the
     * protocol terminator so callers can distinguish a complete stream from a
     * clean-looking premature EOF.
     *
     * @return true when a {@code [DONE]} marker ended the stream
     */
    public static boolean read(Reader in, FrameHandler handler) throws IOException {
        BufferedReader reader = new BufferedReader(in);
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty()) continue;
            if (DONE.equals(payload)) return true;
            handler.onData(payload);
        }
        return false;
    }
}
