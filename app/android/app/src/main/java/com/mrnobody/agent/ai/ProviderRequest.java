package com.mrnobody.agent.ai;

import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One cancellable provider thread plus the connection it currently owns. */
final class ProviderRequest implements AiProvider.RequestHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
    private final AtomicReference<Thread> thread = new AtomicReference<>();

    void start(String name, Runnable work) {
        Thread t = new Thread(() -> {
            try {
                if (!cancelled.get()) work.run();
            } finally {
                connection.set(null);
                thread.set(null);
            }
        }, name);
        t.setDaemon(true);
        thread.set(t);
        if (cancelled.get()) {
            thread.compareAndSet(t, null);
            return;
        }
        t.start();
    }

    void bind(HttpURLConnection conn) {
        connection.set(conn);
        if (cancelled.get() && connection.compareAndSet(conn, null)) {
            conn.disconnect();
        }
    }

    void unbind(HttpURLConnection conn) {
        connection.compareAndSet(conn, null);
    }

    boolean isCancelled() { return cancelled.get(); }

    @Override
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        HttpURLConnection conn = connection.getAndSet(null);
        if (conn != null) {
            try { conn.disconnect(); } catch (Throwable ignored) { }
        }
        Thread t = thread.getAndSet(null);
        if (t != null) t.interrupt();
    }
}
