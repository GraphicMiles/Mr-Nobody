package com.mrnobody.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mrnobody.identity.DeviceIdentity;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * The device side of remote execution, against a fake connection. Proves the
 * client builds and signs the exact request the server verifies, and that it
 * reassembles the SSE result stream — the same contract the Node server's own
 * tests pin from the other side.
 */
public class RemoteClientTest {

    /** An HttpURLConnection that returns canned responses and captures its body. */
    private static final class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int code;
        final String response;
        boolean disconnected;

        FakeConnection(int code, String response) throws IOException {
            super(new URL("https://example.invalid/"));
            this.code = code;
            this.response = response;
        }

        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return code; }
        @Override public OutputStream getOutputStream() { return out; }
        @Override public InputStream getInputStream() throws IOException {
            if (code >= 400) throw new IOException("HTTP " + code);
            return bytes();
        }
        @Override public InputStream getErrorStream() {
            return code >= 400 ? bytes() : null;
        }
        private InputStream bytes() {
            return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static DeviceIdentity identity() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        return new DeviceIdentity(kp.getPublic(), kp.getPrivate(), "software");
    }

    @Test
    public void submitSignsAndPostsTheExactContract() throws Exception {
        DeviceIdentity id = identity();
        FakeConnection conn = new FakeConnection(202, "{\"taskId\":42}");
        RemoteClient client = new RemoteClient("https://worker.example",
                url -> conn);

        long taskId = client.submit(id, "nonce-1", "find laptops");

        assertEquals(42, taskId);
        JSONObject body = new JSONObject(conn.out.toString("UTF-8"));
        assertEquals(id.identity(), body.getString("identity"));
        assertEquals("nonce-1", body.getString("nonce"));
        assertEquals("find laptops", body.getString("payload"));
        assertTrue(body.has("timestamp"));
        assertTrue(body.has("signature"));
        assertTrue(conn.disconnected);
    }

    @Test
    public void streamReassemblesSseEventsInOrder() throws Exception {
        DeviceIdentity id = identity();
        String sse = "data: {\"taskId\":42,\"type\":\"token\",\"text\":\"Hello \"}\n\n"
                + "data: {\"taskId\":42,\"type\":\"token\",\"text\":\"world\"}\n\n"
                + "data: {\"taskId\":42,\"type\":\"done\",\"text\":\"Hello world\"}\n\n";
        FakeConnection conn = new FakeConnection(200, sse);
        RemoteClient client = new RemoteClient("https://worker.example",
                url -> conn);

        List<String[]> events = new ArrayList<>();
        client.stream(42, (type, text) -> events.add(new String[]{type, text}),
                com.mrnobody.agent.core.Cancellation.NONE);

        assertEquals(3, events.size());
        assertEquals("token", events.get(0)[0]);
        assertEquals("Hello ", events.get(0)[1]);
        assertEquals("done", events.get(2)[0]);
        assertEquals("Hello world", events.get(2)[1]);
        assertTrue(conn.disconnected);
    }

    @Test
    public void terminalEventStopsBeforeLateFrames() throws Exception {
        String sse = "data: {\"type\":\"done\",\"text\":\"answer\"}\n\n"
                + "data: {\"type\":\"error\",\"text\":\"late\"}\n\n";
        FakeConnection conn = new FakeConnection(200, sse);
        RemoteClient client = new RemoteClient("https://worker.example", url -> conn);
        List<String[]> events = new ArrayList<>();
        client.stream(42, (type, text) -> events.add(new String[]{type, text}),
                com.mrnobody.agent.core.Cancellation.NONE);
        assertEquals(1, events.size());
        assertEquals("done", events.get(0)[0]);
    }

    @Test
    public void standardDoneMarkerIsSurfacedAsATerminalEvent() throws Exception {
        String sse = "data: {\"type\":\"token\",\"text\":\"answer\"}\n\n"
                + "data: [DONE]\n\n";
        FakeConnection conn = new FakeConnection(200, sse);
        RemoteClient client = new RemoteClient("https://worker.example", url -> conn);
        List<String[]> events = new ArrayList<>();
        client.stream(42, (type, text) -> events.add(new String[]{type, text}),
                com.mrnobody.agent.core.Cancellation.NONE);
        assertEquals(2, events.size());
        assertEquals("done", events.get(1)[0]);
    }

    @Test
    public void cleartextWorkerIsRefusedBeforeAConnectionOpens() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean opened =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        RemoteClient client = new RemoteClient("http://worker.example", url -> {
            opened.set(true);
            return new FakeConnection(200, "");
        });
        try {
            client.stream(42, (type, text) -> { },
                    com.mrnobody.agent.core.Cancellation.NONE);
            org.junit.Assert.fail("expected IOException");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("HTTPS"));
        }
        org.junit.Assert.assertFalse(opened.get());
    }

    @Test
    public void aRejectedStreamIncludesTheServerError() throws Exception {
        FakeConnection conn = new FakeConnection(403, "{\"error\":\"expired\"}");
        RemoteClient client = new RemoteClient("https://worker.example", url -> conn);

        try {
            client.stream(42, (type, text) -> { },
                    com.mrnobody.agent.core.Cancellation.NONE);
            org.junit.Assert.fail("expected IOException");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("403"));
            assertTrue(expected.getMessage().contains("expired"));
        }
        assertTrue(conn.disconnected);
    }

    @Test
    public void aRejectedSubmitThrows() throws Exception {
        DeviceIdentity id = identity();
        FakeConnection conn = new FakeConnection(401, "{\"error\":\"replay\"}");
        RemoteClient client = new RemoteClient("https://worker.example",
                url -> conn);

        try {
            client.submit(id, "nonce-1", "find laptops");
            org.junit.Assert.fail("expected IOException");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("401"));
            assertTrue(expected.getMessage().contains("replay"));
        }
        assertTrue(conn.disconnected);
    }
}
