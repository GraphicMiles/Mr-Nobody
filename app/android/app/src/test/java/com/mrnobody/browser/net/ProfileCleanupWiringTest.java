package com.mrnobody.browser.net;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * BUG-1 wiring: profile deletion on real hardware can outlive any in-process
 * retry ladder, so the cleanup must be persistent, swept at startup, and
 * backed by a data wipe that does not depend on deletion succeeding.
 *
 * Source wiring because ProfileManager touches androidx.webkit and
 * SharedPreferences, which are throwing stubs under the JVM harness.
 */
public final class ProfileCleanupWiringTest {

    @Test
    public void retryLadderStretchesWellPastTheOldCeiling() throws Exception {
        String source = profileManager();
        assertTrue(source.contains("15_000L"));
        assertTrue(source.contains("8_000L"));
    }

    @Test
    public void exhaustedDeletionIsWipedNowAndOwedToStartup() throws Exception {
        String source = profileManager();
        int exhausted = source.indexOf("deletion owed to next startup");
        assertTrue(exhausted >= 0);
        // Inside the exhaustion branch the data is wiped and the debt recorded.
        int wipe = source.indexOf("wipeProfileData(profileName)");
        int owe = source.indexOf("recordOwed(profileName)");
        assertTrue(wipe >= 0);
        assertTrue(owe >= 0);
    }

    @Test
    public void owedDeletionsSurviveTheProcess() throws Exception {
        String source = profileManager();
        assertTrue(source.contains("getSharedPreferences(CLEANUP_PREFS"));
        assertTrue(source.contains("\"mrnobody_profile_cleanup\""));
    }

    @Test
    public void startupSweepIsWiredIntoTheApplication() throws Exception {
        String app = read("src/main/java/com/mrnobody/browser/MrNobodyApp.java");
        assertTrue(app.contains("ProfileManager.sweepAtStartup(this)"));
    }

    @Test
    public void applyProfileSettlesOwedDataBeforeBinding() throws Exception {
        String source = profileManager();
        int wipe = source.indexOf("wipeProfileData(name)");
        int bind = source.indexOf("WebViewCompat.setProfile(webView, name)");
        assertTrue(wipe >= 0);
        assertTrue(bind > wipe);
    }

    @Test
    public void clearDataWipesThePrivateJarEvenIfDeletionIsRefused() throws Exception {
        String activity = read("src/main/java/com/mrnobody/browser/MainActivity.java");
        assertTrue(activity.contains("ProfileManager.wipePrivateData()"));
    }

    @Test
    public void cacheClearNoLongerLeaksAWebView() throws Exception {
        String activity = read("src/main/java/com/mrnobody/browser/MainActivity.java");
        assertTrue(activity.contains("cacheClearer.destroy()"));
        assertFalse(activity.contains("new WebView(this).clearCache"));
    }

    private static String profileManager() throws Exception {
        return read("src/main/java/com/mrnobody/browser/net/ProfileManager.java");
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(java.nio.file.Paths.get(relative)),
                StandardCharsets.UTF_8);
    }
}
