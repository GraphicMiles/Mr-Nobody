package com.mrnobody.browser.blocking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Filter-list integrity.
 *
 * <p>The blocklist decides what is blocked, so whoever can change it can
 * silently unblock their own trackers. Bundled lists are covered by the APK
 * signature; the moment a list can arrive over the network that stops being
 * true, which is why the check exists before the fetching does.
 */
public class FilterIntegrityTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // --------------------------------------------------------------- digest

    @Test
    public void theSameContentAlwaysDigestsTheSame() {
        assertEquals(FilterIntegrity.digest(bytes("||ads.test^")),
                FilterIntegrity.digest(bytes("||ads.test^")));
    }

    @Test
    public void oneChangedByteChangesTheDigest() {
        assertFalse(FilterIntegrity.digest(bytes("||ads.test^"))
                .equals(FilterIntegrity.digest(bytes("||ads.tesT^"))));
    }

    @Test
    public void theDigestIsLowercaseHexSha256() {
        String d = FilterIntegrity.digest(bytes("x"));
        assertEquals(64, d.length());
        assertEquals(d.toLowerCase(), d);
    }

    // --------------------------------------------------------------- verify

    @Test
    public void matchingContentVerifies() {
        byte[] list = bytes("[ADS]\n||ads.test^\n");
        FilterIntegrity.Result r = FilterIntegrity.verify(list, FilterIntegrity.digest(list));
        assertTrue(r.reason, r.ok);
    }

    @Test
    public void tamperedContentIsRejected() {
        byte[] original = bytes("[ADS]\n||ads.test^\n");
        String expected = FilterIntegrity.digest(original);

        // An attacker removes a rule so their tracker stops being blocked.
        byte[] tampered = bytes("[ADS]\n");
        FilterIntegrity.Result r = FilterIntegrity.verify(tampered, expected);

        assertFalse("a shortened list must not verify", r.ok);
        assertTrue(r.reason, r.reason.contains("mismatch"));
    }

    /**
     * The failure mode that produced the dead-tree privacy audit: a check that
     * passes when it was never configured reports safety it never established.
     */
    @Test
    public void anUnconfiguredExpectationFailsRatherThanPasses() {
        byte[] list = bytes("anything");
        assertFalse(FilterIntegrity.verify(list, null).ok);
        assertFalse(FilterIntegrity.verify(list, "").ok);
        assertFalse(FilterIntegrity.verify(list, "   ").ok);
    }

    @Test
    public void theFailureSaysWhatWasExpected() {
        FilterIntegrity.Result r = FilterIntegrity.verify(bytes("a"), "0".repeat(64));
        assertNotNull(r.reason);
        assertNotNull(r.actualDigest);
    }

    // ------------------------------------------------------------- rollback

    @Test
    public void anewerVerifiedListIsAccepted() {
        byte[] v2 = bytes("[ADS]\n||new.test^\n");
        FilterIntegrity.Result r = FilterIntegrity.canReplace(
                1, 2, v2, FilterIntegrity.digest(v2));
        assertTrue(r.reason, r.ok);
    }

    @Test
    public void anolderListIsRefusedEvenWhenGenuine() {
        // Replaying a real, correctly-digested older list would reopen every
        // hole a later list closed.
        byte[] old = bytes("[ADS]\n||old.test^\n");
        FilterIntegrity.Result r = FilterIntegrity.canReplace(
                5, 2, old, FilterIntegrity.digest(old));

        assertFalse("a genuine but stale list is still a rollback", r.ok);
        assertTrue(r.reason, r.reason.contains("roll back"));
    }

    @Test
    public void thesameVersionWithAWrongDigestIsRefused() {
        byte[] list = bytes("[ADS]\n||x.test^\n");
        FilterIntegrity.Result r = FilterIntegrity.canReplace(3, 3, list, "0".repeat(64));
        assertFalse(r.ok);
    }

    // --------------------------------------------------------------- bounds

    @Test
    public void readingIsBounded() {
        byte[] big = new byte[1024];
        try {
            FilterIntegrity.readBounded(new ByteArrayInputStream(big), 100);
            org.junit.Assert.fail("a hostile source must not be read without limit");
        } catch (Exception expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("exceeds"));
        }
    }

    @Test
    public void contentWithinTheLimitReadsFully() throws Exception {
        byte[] list = bytes("[ADS]\n||ads.test^\n");
        byte[] read = FilterIntegrity.readBounded(new ByteArrayInputStream(list), 1024);
        assertEquals(FilterIntegrity.digest(list), FilterIntegrity.digest(read));
    }
}
