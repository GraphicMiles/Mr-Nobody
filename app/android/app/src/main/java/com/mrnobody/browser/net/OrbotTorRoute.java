package com.mrnobody.browser.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

/**
 * Tor, by way of Orbot's local SOCKS port.
 *
 * <p>We ship no Tor binary. Orbot is the user's app, listening on
 * {@code 127.0.0.1:9050}, and this route is the ordinary SOCKS path pointed at
 * it. That is deliberate: embedding Tor means carrying a native library per
 * ABI, and the Tor Project describes binary size as an unsolved problem for
 * Arti. Here the cost is zero bytes.
 *
 * <p>DNS comes along for free. Under SOCKS5 Chromium resolves names at the
 * proxy, so hostnames are not leaked to the local resolver — the DNS item is a
 * consequence of this route rather than a separate feature.
 *
 * <p><b>Availability is probed, and the answer is cached.</b> A liveness check
 * runs on a real socket connect to the SOCKS port, because "is Orbot running"
 * has no cheaper honest answer. {@link #isAvailable()} is consulted on every
 * outbound connection, so probing on each call would add a connect to every
 * request. The cache is short enough that stopping Orbot is noticed quickly and
 * long enough that a page load does not probe dozens of times.
 */
public final class OrbotTorRoute implements NetworkRoute {

    public static final String ID = "tor-orbot";

    /** Orbot's default SOCKS5 listener. */
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 9050;

    /**
     * The port this route actually targets. 9050 unless the bundled Tor
     * reported binding an auto port (TorService falls back when 9050 is
     * unavailable at its configuration time — seen on-device after a process
     * restart left 9050 in TIME_WAIT). Static: every instance of this route
     * must agree, and {@code PrivacyController} refreshes it on each apply.
     */
    private static volatile int activePort = PORT;

    /** Point the route at the bundled Tor's real port; any value <= 0 resets to 9050. */
    public static void setActivePort(int port) {
        activePort = port > 0 ? port : PORT;
    }

    /** The port currently probed and proxied to. */
    public static int activePort() {
        return activePort;
    }

    /** How long a liveness answer is trusted. */
    static final long CACHE_MS = 3_000L;

    /** Deliberately short: this is a loopback connect, not a network round trip. */
    private static final int PROBE_TIMEOUT_MS = 400;

    /** Seam for tests — the probe is the only part that touches a socket. */
    interface Probe {
        boolean isListening(String host, int port, int timeoutMs);
    }

    /** Seam for tests, so cache expiry does not need real sleeping. */
    interface Clock {
        long now();
    }

    private final Probe probe;
    private final Clock clock;

    private volatile boolean lastResult;
    private volatile long lastCheckedAt = Long.MIN_VALUE;

    public OrbotTorRoute() {
        this(OrbotTorRoute::socketProbe, System::currentTimeMillis);
    }

    OrbotTorRoute(Probe probe, Clock clock) {
        this.probe = probe;
        this.clock = clock;
    }

    private static boolean socketProbe(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Tor (Orbot)";
    }

    @Override
    public Proxy proxy() {
        return new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(HOST, activePort));
    }

    @Override
    public boolean failClosed() {
        // The entire purpose of this route. Falling back to direct would
        // deanonymise the user at the exact moment they believed otherwise.
        return true;
    }

    @Override
    public boolean isAvailable() {
        long now = clock.now();
        // Guard against a clock that moved backwards: treat it as expired
        // rather than trusting a cache entry from the future.
        long age = now - lastCheckedAt;
        if (lastCheckedAt != Long.MIN_VALUE && age >= 0 && age < CACHE_MS) {
            return lastResult;
        }
        boolean listening = probe.isListening(HOST, activePort, PROBE_TIMEOUT_MS);
        lastResult = listening;
        lastCheckedAt = now;
        return listening;
    }

    @Override
    public void refresh() {
        lastCheckedAt = Long.MIN_VALUE;
    }

    @Override
    public String webViewProxyRule() {
        return "socks://" + HOST + ":" + activePort;
    }
}
