package com.mrnobody.agent.ai;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProviderRequestTest {

    private static final class FakeConnection extends HttpURLConnection {
        final AtomicBoolean disconnected = new AtomicBoolean(false);
        FakeConnection() throws Exception { super(new URL("https://example.com")); }
        @Override public void disconnect() { disconnected.set(true); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    @Test
    public void cancelDisconnectsTheBoundSocket() throws Exception {
        ProviderRequest request = new ProviderRequest();
        FakeConnection connection = new FakeConnection();
        request.bind(connection);
        request.cancel();
        assertTrue(connection.disconnected.get());
        assertTrue(request.isCancelled());
    }

    @Test
    public void cancelInterruptsTheProviderThread() throws Exception {
        ProviderRequest request = new ProviderRequest();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        request.start("test-provider", () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        request.cancel();
        long deadline = System.currentTimeMillis() + 2_000;
        while (!interrupted.get() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(interrupted.get());
    }
}
