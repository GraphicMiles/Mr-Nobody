package com.mrnobody.browser.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The proxy picker must match the route the controller actually builds.
 * "Direct" landing on Orbot was how Nobody stayed required after the user
 * asked for no proxy.
 */
public class PrivacyControllerRouteTest {

    @Test
    public void directMeansDirect() {
        NetworkRoute r = PrivacyController.routeFor("direct", "http", "", 0);
        assertTrue(r instanceof DirectRoute);
        assertEquals(DirectRoute.ID, r.id());
    }

    @Test
    public void proxyMeansTheHostTheUserTyped() {
        NetworkRoute r = PrivacyController.routeFor("proxy", "http", "10.0.0.1", 3128);
        assertTrue(r instanceof ProxyRoute);
        assertEquals("http://10.0.0.1:3128", r.webViewProxyRule());
    }

    @Test
    public void anythingElseIsOrbot() {
        NetworkRoute r = PrivacyController.routeFor("tor-orbot", "http", "", 0);
        assertTrue(r instanceof OrbotTorRoute);
        assertEquals(OrbotTorRoute.ID, r.id());
    }
}
