package com.mrnobody.browser.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orbot liveness, and the cache that keeps it affordable.
 *
 * <p>{@code isAvailable()} is consulted on every outbound connection, and the
 * only honest way to answer "is Orbot running" is to try the socket. Probing
 * on each call would add a connect to every request, so the answer is cached —
 * briefly, because the cost of a stale "yes" is a request that fails, while
 * the cost of a stale "no" is a browser that refuses to work.
 *
 * <p>The probe and the clock are injected so none of this needs a real socket
 * or a real wait.
 */
public class OrbotTorRouteTest {

    /** A probe that counts calls and answers however the test says. */
    private static final class FakeProbe implements OrbotTorRoute.Probe {
        boolean listening;
        final AtomicInteger calls = new AtomicInteger();

        FakeProbe(boolean listening) {
            this.listening = listening;
        }

        @Override
        public boolean isListening(String host, int port, int timeoutMs) {
            calls.incrementAndGet();
            return listening;
        }
    }

    /** A clock the test moves by hand. */
    private static final class FakeClock implements OrbotTorRoute.Clock {
        long now = 1_000_000L;

        @Override
        public long now() {
            return now;
        }
    }

    // -------------------------------------------------------------- liveness

    @Test
    public void orbotRunningMeansAvailable() {
        OrbotTorRoute r = new OrbotTorRoute(new FakeProbe(true), new FakeClock());
        assertTrue(r.isAvailable());
    }

    @Test
    public void orbotStoppedMeansUnavailableAndFailClosed() {
        OrbotTorRoute r = new OrbotTorRoute(new FakeProbe(false), new FakeClock());
        assertFalse(r.isAvailable());
        assertTrue("Tor must never degrade to a direct connection", r.failClosed());
    }

    // ----------------------------------------------------------- the caching

    @Test
    public void repeatedChecksDoNotReprobe() {
        FakeProbe probe = new FakeProbe(true);
        OrbotTorRoute r = new OrbotTorRoute(probe, new FakeClock());

        for (int i = 0; i < 25; i++) r.isAvailable();

        assertEquals("a page load must not open 25 probe sockets", 1, probe.calls.get());
    }

    @Test
    public void theCacheExpires() {
        FakeProbe probe = new FakeProbe(true);
        FakeClock clock = new FakeClock();
        OrbotTorRoute r = new OrbotTorRoute(probe, clock);

        assertTrue(r.isAvailable());
        clock.now += OrbotTorRoute.CACHE_MS + 1;

        // Orbot stopped while the answer was cached.
        probe.listening = false;
        assertFalse("a stopped Orbot must be noticed", r.isAvailable());
        assertEquals(2, probe.calls.get());
    }

    @Test
    public void refreshDiscardsTheCachedAnswerImmediately() {
        FakeProbe probe = new FakeProbe(false);
        OrbotTorRoute r = new OrbotTorRoute(probe, new FakeClock());

        assertFalse(r.isAvailable());
        probe.listening = true;

        // Without refresh the stale "no" would stand; the user just started
        // Orbot and should not wait out the cache.
        r.refresh();
        assertTrue(r.isAvailable());
    }

    @Test
    public void aBackwardsClockDoesNotTrustTheFuture() {
        FakeProbe probe = new FakeProbe(true);
        FakeClock clock = new FakeClock();
        OrbotTorRoute r = new OrbotTorRoute(probe, clock);

        assertTrue(r.isAvailable());
        clock.now -= 60_000L; // NTP correction, or a user changing the clock

        probe.listening = false;
        assertFalse("must re-probe rather than trust a future timestamp", r.isAvailable());
    }

    // ------------------------------------------------------------- transport

    @Test
    public void itIsSocksOnOrbotsDefaultPort() {
        OrbotTorRoute r = new OrbotTorRoute(new FakeProbe(true), new FakeClock());

        assertEquals(Proxy.Type.SOCKS, r.proxy().type());
        assertEquals("socks://127.0.0.1:9050", r.webViewProxyRule());
        assertEquals(9050, OrbotTorRoute.PORT);
    }

    @Test
    public void itNamesItselfForTheUser() {
        OrbotTorRoute r = new OrbotTorRoute(new FakeProbe(true), new FakeClock());
        assertTrue(r.label(), r.label().contains("Tor"));
    }
}
