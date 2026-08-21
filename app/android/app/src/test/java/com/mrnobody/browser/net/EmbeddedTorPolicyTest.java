package com.mrnobody.browser.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The bundled-Tor decisions, on a JVM, before any Android is involved. */
public class EmbeddedTorPolicyTest {

    @Test
    public void orbotAlwaysWins() {
        // Something already serves the SOCKS port: never race it.
        assertFalse(EmbeddedTorPolicy.shouldStart(true, true, true));
    }

    @Test
    public void bundledTorStartsOnlyForTheTorRoute() {
        assertFalse("a proxy or direct route must not start Tor",
                EmbeddedTorPolicy.shouldStart(false, false, true));
        assertTrue(EmbeddedTorPolicy.shouldStart(true, false, true));
    }

    @Test
    public void aBuildWithoutTheBinaryNeverTriesToStartIt() {
        assertFalse(EmbeddedTorPolicy.shouldStart(true, false, false));
    }

    @Test
    public void statusStringsAreReadTheWayTorServiceWritesThem() {
        // Values pinned to org.torproject.jni.TorService's contract.
        assertTrue(EmbeddedTorPolicy.statusMeansReady("ON"));
        assertFalse(EmbeddedTorPolicy.statusMeansReady("STARTING"));
        assertFalse(EmbeddedTorPolicy.statusMeansReady(null));
        assertTrue(EmbeddedTorPolicy.statusMeansStopped("OFF"));
        assertTrue(EmbeddedTorPolicy.statusMeansStopped("STOPPING"));
        assertFalse(EmbeddedTorPolicy.statusMeansStopped("ON"));
    }

    @Test
    public void theApplyWaitStaysUnderTheAnrThreshold() {
        // apply() runs on the platform channel thread; 5s is where Android
        // starts drawing conclusions.
        assertTrue(EmbeddedTorPolicy.APPLY_WAIT_MS < 5_000L);
        assertTrue(EmbeddedTorPolicy.PROBE_INTERVAL_MS < EmbeddedTorPolicy.APPLY_WAIT_MS);
    }

    @Test
    public void refusalsExplainTheActualSituation() {
        assertTrue(EmbeddedTorPolicy.stillStartingMessage().contains("Try Nobody again"));
        assertTrue(EmbeddedTorPolicy.unavailableMessage(false).contains("Orbot"));
        assertFalse("with a bundled Tor, installing Orbot is not the fix",
                EmbeddedTorPolicy.unavailableMessage(true).contains("Install Orbot"));
    }

    @Test
    public void embeddedTorConstantsMatchTorServicesDocumentedContract() {
        assertEquals("org.torproject.jni.TorService", EmbeddedTor.SERVICE_CLASS);
        assertEquals("org.torproject.android.intent.action.STATUS", EmbeddedTor.ACTION_STATUS);
        assertEquals("org.torproject.android.intent.extra.STATUS", EmbeddedTor.EXTRA_STATUS);
    }

    @Test
    public void withoutTheAarOnTheClasspathBundledIsSimplyFalse() {
        // The JVM harness has no tor-android jar, which is exactly the
        // degraded build this must survive: no exception, no start.
        assertFalse(EmbeddedTor.isBundled());
    }
}
