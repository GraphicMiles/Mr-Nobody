package com.mrnobody.browser.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The exact map the MethodChannel returns, computed by the pure
 * {@link UpdateStatus#compute}. These pin the UX contract: when the
 * badge shows, when a dismissal sticks (and stops sticking), and how a
 * failed network call leaves the cache intact.
 */
public class UpdateStatusTest {

    private static final String INSTALLED = "1.0.0";
    private static final long TS = 1_753_200_000_000L;

    private static String release(String version, boolean required) {
        return "{"
                + "\"latestVersion\": \"" + version + "\","
                + "\"releaseNotes\": \"Faster tabs\","
                + "\"downloadUrl\": \"https://cdn.example.com/mr-nobody-" + version + ".apk\","
                + "\"required\": " + required + ","
                + "\"sha256\": \"\","
                + "\"signature\": \"\","
                + "\"publishedAt\": \"2026-08-22T00:00:00Z\"}";
    }

    private static boolean flag(java.util.Map<String, Object> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    @Test
    public void neverCheckedMeansNoUpdateAndNoBadge() {
        java.util.Map<String, Object> m =
                UpdateStatus.compute(INSTALLED, "", 0L, "", "none", false);
        assertEquals("none", m.get("source"));
        assertEquals("", m.get("latestVersion"));
        assertFalse(flag(m, "updateAvailable"));
        assertFalse(flag(m, "dismissed"));
        assertFalse(flag(m, "networkFailed"));
    }

    @Test
    public void aNewerCachedReleaseShowsTheBadge() {
        java.util.Map<String, Object> m = UpdateStatus.compute(
                INSTALLED, release("1.1.0", false), TS, "", "cache", false);
        assertEquals("cache", m.get("source"));
        assertEquals(TS, m.get("lastCheckedAt"));
        assertEquals("1.1.0", m.get("latestVersion"));
        assertTrue(flag(m, "updateAvailable"));
        assertFalse(flag(m, "required"));
        assertFalse(flag(m, "dismissed"));
        assertEquals("Faster tabs", m.get("releaseNotes"));
    }

    @Test
    public void theSameVersionIsNotAnUpdate() {
        java.util.Map<String, Object> m = UpdateStatus.compute(
                INSTALLED, release("1.0.0", false), TS, "", "cache", false);
        assertFalse(flag(m, "updateAvailable"));
    }

    @Test
    public void anOlderPublishedVersionIsNotAnUpdate() {
        java.util.Map<String, Object> m = UpdateStatus.compute(
                INSTALLED, release("0.9.0", false), TS, "", "cache", false);
        assertFalse(flag(m, "updateAvailable"));
    }

    @Test
    public void dismissalSuppressesTheBadgeForThatVersionOnly() {
        java.util.Map<String, Object> m = UpdateStatus.compute(
                INSTALLED, release("1.1.0", false), TS, "1.1.0", "cache", false);
        assertTrue(flag(m, "updateAvailable"));
        assertTrue(flag(m, "dismissed"));

        // A newer release reappears: the dismissal was for 1.1.0, not
        // "updates in general".
        java.util.Map<String, Object> later = UpdateStatus.compute(
                INSTALLED, release("1.2.0", false), TS, "1.1.0", "cache", false);
        assertTrue(flag(later, "updateAvailable"));
        assertFalse(flag(later, "dismissed"));
    }

    @Test
    public void requiredOnlyMeansSomethingWhileAnUpdateIsOnOffer() {
        java.util.Map<String, Object> m = UpdateStatus.compute(
                INSTALLED, release("1.1.0", true), TS, "", "cache", false);
        assertTrue(flag(m, "required"));

        java.util.Map<String, Object> upToDate = UpdateStatus.compute(
                INSTALLED, release("1.0.0", true), TS, "", "cache", false);
        assertFalse(flag(upToDate, "required"));
    }

    @Test
    public void aFailedCheckKeepsTheCacheAndFlagsTheFailure() {
        // The cache still says 1.1.0 is out; the network did not answer.
        java.util.Map<String, Object> m = UpdateStatus.compute(
                INSTALLED, release("1.1.0", false), TS, "", "cache", true);
        assertTrue(flag(m, "updateAvailable"));
        assertTrue(flag(m, "networkFailed"));
        assertEquals(TS, m.get("lastCheckedAt"));
    }

    @Test
    public void aFailedFirstCheckReportsNothingRatherThanGuessing() {
        java.util.Map<String, Object> m =
                UpdateStatus.compute(INSTALLED, "", 0L, "", "none", true);
        assertFalse(flag(m, "updateAvailable"));
        assertTrue(flag(m, "networkFailed"));
    }

    @Test
    public void aMalformedCacheIsTreatedAsNeverChecked() {
        java.util.Map<String, Object> m = UpdateStatus.compute(
                INSTALLED, "garbage", TS, "", "cache", false);
        assertEquals("none", m.get("source"));
        assertFalse(flag(m, "updateAvailable"));
    }
}
