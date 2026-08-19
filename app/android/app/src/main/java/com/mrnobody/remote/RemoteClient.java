package com.mrnobody.remote;

import com.mrnobody.agent.ai.SseFrames;
import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.identity.DeviceIdentity;
import com.mrnobody.identity.SignedRequest;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The client half of remote execution: submits a signed task to the remote
 * worker and streams the result back.
 *
 * <p>This is the device side of the remote-worker contract in {@code README.md}.
 * It signs with the device identity (the private key never leaves the device),
 * posts the signed request, then reads the SSE result stream with the same
 * {@link SseFrames} parser the AI providers use, forwarding token / done /
 * error events upward.
 *
 * <p>Every outbound connection goes through the injected
 * {@link ConnectionFactory}, which in production is {@code NetworkGate} — so a
 * remote task rides whatever privacy route is active, exactly like a local one,
 * and the single-egress-chokepoint rule is preserved.
 */
public final class RemoteClient {

    /** Opens a connection to a URL. Production: {@code NetworkGate::openHttp}. */
    public interface ConnectionFactory {
        HttpURLConnection open(String url) throws IOException;
    }

    /** Receives one stream event. */
    public interface StreamListener {
        void onEvent(String type, String text);
    }

    private final String baseUrl;
    private final ConnectionFactory connections;

    public RemoteClient(String baseUrl, ConnectionFactory connections) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.connections = connections;
    }

    /**
     * Submit a task and return the remote task id.
     *
     * @throws IOException on any transport or rejection failure.
     */
    public long submit(DeviceIdentity identity, String nonce, String payload) throws Exception {
        SignedRequest req = SignedRequest.sign(identity, nonce, payload);

        JSONObject body = new JSONObject();
        body.put("identity", identity.identity());
        body.put("nonce", req.nonce());
        body.put("timestamp", req.timestamp());
        body.put("payload", req.payload());
        body.put("signature", Base64.getEncoder().encodeToString(req.signature()));

        HttpURLConnection conn = connections.open(baseUrl + "/tasks");
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        String response = readAll(conn.getInputStream());
        if (code < 200 || code >= 300) {
            throw new IOException("remote worker rejected the task: HTTP " + code + " " + response);
        }
        return new JSONObject(response).getLong("taskId");
    }

    /**
     * Stream a task's result, calling {@code listener} per event until the
     * stream ends or the task is cancelled.
     */
    public void stream(long taskId, StreamListener listener, Cancellation cancellation)
            throws Exception {
        HttpURLConnection conn = connections.open(baseUrl + "/tasks/" + taskId + "/stream");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        try (InputStream in = conn.getInputStream();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            SseFrames.read(reader, json -> {
                if (cancellation != null && cancellation.isCancelled()) {
                    throw new IOException("cancelled");
                }
                try {
                    JSONObject ev = new JSONObject(json);
                    listener.onEvent(ev.optString("type", ""), ev.optString("text", ""));
                } catch (org.json.JSONException e) {
                    // A malformed frame is skipped, not fatal to the stream.
                }
            });
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
