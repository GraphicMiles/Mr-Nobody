package com.mrnobody.browser.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The modes, and the wording they promise.
 *
 * <p>The wording assertions are not decoration. A privacy product's most
 * likely failure is not a broken proxy — it is a true sentence that a user
 * reads as a stronger claim than it is. PRIVATE isolates storage and does
 * nothing about the network; NOBODY changes the route and still is not
 * anonymity. Both facts are asserted here so that softening them later breaks
 * a test rather than quietly shipping.
 */
public class PrivacyModeTest {

    @Test
    public void normalMakesNoPrivacyClaim() {
        assertFalse(PrivacyMode.NORMAL.needsIsolatedProfile());
        assertFalse(PrivacyMode.NORMAL.needsPrivacyRoute());
        assertFalse(PrivacyMode.NORMAL.needsFingerprintDefence());
    }

    @Test
    public void privateIsolatesStorageOnly() {
        assertTrue(PrivacyMode.PRIVATE.needsIsolatedProfile());
        assertFalse("private is not a network claim",
                PrivacyMode.PRIVATE.needsPrivacyRoute());
    }

    @Test
    public void nobodyIsEverything() {
        assertTrue(PrivacyMode.NOBODY.needsIsolatedProfile());
        assertTrue(PrivacyMode.NOBODY.needsPrivacyRoute());
        assertTrue(PrivacyMode.NOBODY.needsFingerprintDefence());
    }

    @Test
    public void privateAdmitsTheIpIsUnchanged() {
        String d = PrivacyMode.PRIVATE.description();
        assertTrue(d, d.contains("IP address is unchanged"));
    }

    @Test
    public void nobodyDoesNotPromiseAnonymity() {
        String d = PrivacyMode.NOBODY.description();
        assertTrue(d, d.contains("does not guarantee anonymity"));
    }

    @Test
    public void everyModeExplainsItself() {
        for (PrivacyMode m : PrivacyMode.values()) {
            assertFalse(m.name(), m.label().isEmpty());
            assertFalse(m.name(), m.description().isEmpty());
        }
    }

    @Test
    public void unknownNamesFallBackToNormal() {
        assertEquals(PrivacyMode.NORMAL, PrivacyMode.fromName(null));
        assertEquals(PrivacyMode.NORMAL, PrivacyMode.fromName(""));
        assertEquals(PrivacyMode.NORMAL, PrivacyMode.fromName("supersecret"));
    }

    @Test
    public void namesRoundTrip() {
        for (PrivacyMode m : PrivacyMode.values()) {
            assertEquals(m, PrivacyMode.fromName(m.name()));
            assertEquals(m, PrivacyMode.fromName(m.name().toLowerCase()));
            assertEquals(m, PrivacyMode.fromName(" " + m.name() + " "));
        }
    }
}
