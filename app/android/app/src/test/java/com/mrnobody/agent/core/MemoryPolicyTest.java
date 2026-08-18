package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What the agent may remember between tasks.
 *
 * <p>Long-term memory was last on the roadmap because it is the feature most
 * able to turn a local-first browser into a profile of its user. A browser
 * that quietly accumulates what someone searched for and asked about has
 * rebuilt the thing this product exists to avoid — using their own data, on
 * their own device, which feels harmless until the phone is lost.
 */
public class MemoryPolicyTest {

    @Test
    public void memoryIsOffByDefaultAndRefusesWhenOff() {
        // History is already off by default; memory defaulting on would be
        // history under another name.
        MemoryPolicy.Verdict v = MemoryPolicy.consider("the user likes trains", false);

        assertFalse(v.allowed);
        assertEquals("memory is off", v.reason);
        assertNull(v.value);
    }

    @Test
    public void ordinaryFactsAreKeptWhenMemoryIsOn() {
        MemoryPolicy.Verdict v = MemoryPolicy.consider("prefers metric units", true);

        assertTrue(v.reason, v.allowed);
        assertEquals("prefers metric units", v.value);
    }

    // ------------------------------------------------- the floor under consent

    @Test
    public void credentialsAreRefusedEvenWithMemoryOn() {
        // Enabling memory was not consent to store secrets.
        String[] secrets = {
                "api_key sk_live_abcdefghijklmnop1234",
                "password: hunter2correct",
                "token bearer_abcdefghijklmnopqrstuvwx",
                "card 4111 1111 1111 1111",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N",
        };
        for (String s : secrets) {
            MemoryPolicy.Verdict v = MemoryPolicy.consider(s, true);
            assertFalse("should have been refused: " + s, v.allowed);
        }
    }

    @Test
    public void emailAddressesAreTreatedAsPersonal() {
        assertFalse(MemoryPolicy.consider("contact me at a.person@example.com", true).allowed);
    }

    @Test
    public void theRefusalDoesNotEchoTheSecretBack() {
        // An error message that quotes the secret has written it somewhere too.
        MemoryPolicy.Verdict v = MemoryPolicy.consider("password: hunter2correct", true);

        assertNotNull(v.reason);
        assertFalse(v.reason, v.reason.contains("hunter2correct"));
        assertNull(v.value);
    }

    // ---------------------------------------------------------------- bounds

    @Test
    public void longEntriesAreTruncatedRatherThanRefused() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("word ");

        MemoryPolicy.Verdict v = MemoryPolicy.consider(sb.toString(), true);
        assertTrue(v.allowed);
        assertTrue(v.value.length() <= MemoryPolicy.MAX_ENTRY_CHARS);
    }

    @Test
    public void whitespaceIsNormalised() {
        MemoryPolicy.Verdict v = MemoryPolicy.consider("  likes    trains\n\n", true);
        assertEquals("likes trains", v.value);
    }

    @Test
    public void thestoreCannotGrowWithoutBound() {
        assertFalse(MemoryPolicy.isFull(MemoryPolicy.MAX_ENTRIES - 1));
        assertTrue(MemoryPolicy.isFull(MemoryPolicy.MAX_ENTRIES));
    }

    @Test
    public void emptyInputIsNotRemembered() {
        assertFalse(MemoryPolicy.consider(null, true).allowed);
        assertFalse(MemoryPolicy.consider("", true).allowed);
        assertFalse(MemoryPolicy.consider("   ", true).allowed);
    }

    @Test
    public void keysAreNormalisedSoLookupsAreStable() {
        assertEquals("favourite-colour", MemoryPolicy.normaliseKey("  Favourite Colour "));
        assertEquals("", MemoryPolicy.normaliseKey(null));
    }

    @Test
    public void theSettingExplainsTheTradeOff() {
        String d = MemoryPolicy.description();
        assertTrue(d, d.contains("Off by default"));
        assertTrue(d, d.contains("never stored"));
        assertTrue(d, d.contains("leaves this device"));
    }
}
