package com.mrnobody.browser.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.net.Proxy;
import java.net.URL;

/**
 * The test that matters: a fail-closed route must refuse, not downgrade.
 *
 * <p>Before {@link NetworkGate} existed, five places in this app opened
 * sockets with no proxy — downloads, the HTTP tool, search, and two AI
 * providers. Turning on a privacy route would have moved the browser's traffic
 * and left those five in the clear, which is strictly worse than no privacy
 * route: the user is told they are covered while the agent narrates their
 * activity to a search engine.
 *
 * <p>So the assertions below are mostly about what does <em>not</em> happen.
 */
public class NetworkGateTest {

    @After
    public void reset() {
        NetworkGate.setRoute(new DirectRoute());
    }

    /** An unavailable, fail-closed route. */
    private static NetworkRoute dead(String label) {
        return new NetworkRoute() {
            @Override public String id() { return "dead"; }
            @Override public String label() { return label; }
            @Override public Proxy proxy() { return Proxy.NO_PROXY; }
            @Override public boolean failClosed() { return true; }
            @Override public boolean isAvailable() { return false; }
            @Override public void refresh() { }
            @Override public String webViewProxyRule() { return "socks://127.0.0.1:9050"; }
        };
    }

    // ------------------------------------------------------------ fail closed

    @Test
    public void anUnavailablePrivacyRouteRefusesToConnect() throws Exception {
        NetworkGate.setRoute(dead("Tor (Orbot)"));

        assertFalse("must not connect", NetworkGate.canConnect());
        try {
            NetworkGate.open(new URL("https://example.com"));
            fail("connected anyway — this is the leak the gate exists to stop");
        } catch (NetworkGate.RouteUnavailableException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Tor"));
        }
    }

    @Test
    public void theRefusalExplainsItselfWithoutBlamingTheUser() {
        NetworkGate.setRoute(dead("Tor (Orbot)"));
        String reason = NetworkGate.blockedReason();

        assertNotNull(reason);
        assertTrue(reason, reason.contains("Nothing was sent"));
        assertTrue(reason, reason.contains("fail closed"));
    }

    @Test
    public void aRouteThatIsNotFailClosedStillConnectsWhenUnavailable() {
        // Only fail-closed routes refuse. A best-effort route degrades.
        NetworkGate.setRoute(new NetworkRoute() {
            @Override public String id() { return "lax"; }
            @Override public String label() { return "Lax"; }
            @Override public Proxy proxy() { return Proxy.NO_PROXY; }
            @Override public boolean failClosed() { return false; }
            @Override public boolean isAvailable() { return false; }
            @Override public void refresh() { }
            @Override public String webViewProxyRule() { return null; }
        });

        assertTrue(NetworkGate.canConnect());
        assertNull(NetworkGate.blockedReason());
    }

    // --------------------------------------------------------------- defaults

    @Test
    public void theDefaultRouteIsDirectAndAllowsTraffic() {
        assertTrue(NetworkGate.route() instanceof DirectRoute);
        assertTrue(NetworkGate.canConnect());
        assertNull(NetworkGate.blockedReason());
    }

    @Test
    public void aNullRouteFallsBackToDirectRatherThanCrashing() {
        NetworkGate.setRoute(null);
        assertTrue(NetworkGate.route() instanceof DirectRoute);
        assertTrue(NetworkGate.canConnect());
    }

    @Test
    public void nonHttpUrlsAreRejectedClearly() {
        try {
            NetworkGate.openHttp("ftp://example.com/file");
            fail("should not have returned an HttpURLConnection");
        } catch (Exception e) {
            assertNotNull(e.getMessage());
        }
    }

    // ----------------------------------------------------------- DirectRoute

    @Test
    public void directRouteUsesNoProxyAndNeverBlocks() {
        DirectRoute d = new DirectRoute();
        assertSame(Proxy.NO_PROXY, d.proxy());
        assertFalse(d.failClosed());
        assertTrue(d.isAvailable());
        assertNull("no override — the WebView connects normally", d.webViewProxyRule());
    }

    // ------------------------------------------------------------ ProxyRoute

    @Test
    public void aConfiguredSocksProxyProducesBothTransports() {
        ProxyRoute p = new ProxyRoute(ProxyRoute.Kind.SOCKS, "10.0.0.5", 1080);

        assertEquals(Proxy.Type.SOCKS, p.proxy().type());
        assertEquals("socks://10.0.0.5:1080", p.webViewProxyRule());
        assertTrue(p.isAvailable());
        assertTrue("a proxy the user asked for is a requirement", p.failClosed());
    }

    @Test
    public void anHttpProxyIsAnHttpProxy() {
        ProxyRoute p = new ProxyRoute(ProxyRoute.Kind.HTTP, "proxy.example", 3128);
        assertEquals(Proxy.Type.HTTP, p.proxy().type());
        assertEquals("http://proxy.example:3128", p.webViewProxyRule());
    }

    @Test
    public void anUnconfiguredProxyBlocksInsteadOfGoingDirect() throws Exception {
        ProxyRoute p = new ProxyRoute(ProxyRoute.Kind.HTTP, "   ", 8080);
        NetworkGate.setRoute(p);

        assertFalse(p.isConfigured());
        assertFalse(NetworkGate.canConnect());
        try {
            NetworkGate.open(new URL("https://example.com"));
            fail("an unconfigured proxy must not silently mean 'no proxy'");
        } catch (NetworkGate.RouteUnavailableException expected) {
            // correct
        }
    }

    @Test
    public void portsFallBackToTheSchemeDefault() {
        assertEquals(1080, new ProxyRoute(ProxyRoute.Kind.SOCKS, "h", 0).port());
        assertEquals(8080, new ProxyRoute(ProxyRoute.Kind.HTTP, "h", -1).port());
        assertEquals(8080, new ProxyRoute(ProxyRoute.Kind.HTTP, "h", 70000).port());
    }

    @Test
    public void unknownProxyKindDefaultsToHttp() {
        assertEquals(ProxyRoute.Kind.HTTP, ProxyRoute.Kind.fromName("nonsense"));
        assertEquals(ProxyRoute.Kind.HTTP, ProxyRoute.Kind.fromName(null));
        assertEquals(ProxyRoute.Kind.SOCKS, ProxyRoute.Kind.fromName(" SOCKS "));
    }
}
