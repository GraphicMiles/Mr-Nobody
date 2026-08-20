package com.mrnobody.browser;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.mrnobody.browser.core.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stable end-to-end smoke coverage on a real Android runtime.
 *
 * <p>Network search is deliberately excluded: cloud search providers can block
 * CI addresses, and that would test their anti-bot policy rather than this app.
 * The task uses the deterministic conversational route, which still crosses
 * Flutter → MethodChannel → SQLite → WorkManager → agent events → Flutter UI.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class AppDeviceSmokeTest {

    private static final long UI_TIMEOUT = 20_000L;
    private static final long TASK_TIMEOUT = 45_000L;

    private Instrumentation instrumentation;
    private Context target;
    private UiDevice device;

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        target = instrumentation.getTargetContext();
        device = UiDevice.getInstance(instrumentation);
        // Google APIs emulator images may restore backed-up preferences as the
        // APK is installed. Reset only the values this deterministic test owns
        // before MainActivity starts; production backup behavior remains intact.
        assertTrue("test preference reset",
                target.getSharedPreferences("mrnobody_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .remove(Settings.KEY_FIRST_LAUNCH_DONE)
                        .putBoolean(Settings.KEY_SUGGESTIONS_ENABLED, false)
                        .commit());
        try {
            instrumentation.getUiAutomation().grantRuntimePermission(
                    target.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        } catch (Throwable ignored) {
            // Android 12 has no runtime notification permission.
        }
    }

    @Test
    public void launchPersistSettingAndCompleteLocalTaskAcrossBackground() throws Exception {
        launchMain();
        requireText("Start browsing", UI_TIMEOUT).click();
        // The shell is ready when its persistent navigation is exposed. The
        // scrollable Home sections are deliberately not used as launch gates:
        // their accessibility visibility depends on emulator viewport height.
        requireText("Settings", UI_TIMEOUT).click();

        // UI → native settings write, then an Activity relaunch.
        requireText("Save browsing history", UI_TIMEOUT);
        requireText("Search suggestions", UI_TIMEOUT).click();
        assertNotNull("settings write should acknowledge ON",
                waitForVisibleText("Suggestions ON", true, 5_000));
        assertTrue("UI toggle should persist suggestions ON",
                waitForSuggestions(true, 5_000));
        device.pressHome();
        Thread.sleep(1_000);
        launchMain();
        requireText("Settings", UI_TIMEOUT);
        assertTrue("suggestions should remain ON after Activity relaunch",
                waitForSuggestions(true, 5_000));
        requireText("Search suggestions", UI_TIMEOUT).click(); // restore OFF
        assertNotNull("settings write should acknowledge OFF",
                waitForVisibleText("Suggestions OFF", true, 5_000));
        assertTrue("UI toggle should persist suggestions OFF",
                waitForSuggestions(false, 5_000));

        // MainActivity is alive, so this exercises singleTop onNewIntent and
        // the production deep-link channel rather than a test-only entry point.
        Intent task = new Intent(Intent.ACTION_VIEW,
                Uri.parse("mrnobody://task?instruction=hi"));
        task.setPackage(target.getPackageName());
        task.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        target.startActivity(task);

        requireText("hi", UI_TIMEOUT);
        requireText("Hi. What would you like me to do next?", TASK_TIMEOUT);
        UiObject2 thought = waitForVisibleText("Thought for", true, UI_TIMEOUT);
        assertNotNull("completed task should retain a Thought row", thought);
        thought.click();
        requireText("Understanding the request", UI_TIMEOUT);
        requireText("Responding", UI_TIMEOUT);

        // The finished conversation should still be visible after leaving and
        // returning to the app. Long-running background behavior has separate
        // WorkManager/foreground wiring tests and physical-device acceptance.
        device.pressHome();
        Thread.sleep(1_500);
        launchMain();
        requireText("Hi. What would you like me to do next?", UI_TIMEOUT);

        File dir = target.getExternalFilesDir(null);
        assertNotNull(dir);
        File screenshot = new File(dir, "device-smoke.png");
        assertTrue("screenshot should be captured", device.takeScreenshot(screenshot));
        // connectedDebugAndroidTest uninstalls both APKs before the workflow's
        // evidence step runs. Copy through the shell so the in-app proof
        // survives package removal.
        device.executeShellCommand("cp " + screenshot.getAbsolutePath()
                + " /sdcard/device-smoke.png");
    }

    private void launchMain() {
        Intent launch = target.getPackageManager()
                .getLaunchIntentForPackage(target.getPackageName());
        assertNotNull("launch intent", launch);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        target.startActivity(launch);
        device.waitForIdle();
    }

    private boolean waitForSuggestions(boolean expected, long timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;
        do {
            if (new Settings(target).areSuggestionsEnabled() == expected) return true;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private UiObject2 waitForVisibleText(String text, boolean contains, long timeout)
            throws InterruptedException {
        // Native Android text is exposed as text; Flutter's virtual semantics
        // nodes expose labels as content descriptions. Accept both without
        // weakening the expected user-visible wording.
        long deadline = System.currentTimeMillis() + timeout;
        do {
            UiObject2 object = device.findObject(
                    contains ? By.textContains(text) : By.text(text));
            if (object == null) {
                object = device.findObject(
                        contains ? By.descContains(text) : By.desc(text));
            }
            if (object != null) return object;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private UiObject2 requireText(String text, long timeout) throws Exception {
        UiObject2 object = waitForVisibleText(text, false, timeout);
        if (object == null) {
            // The Gradle task removes the package after a failure. Shell-owned
            // files survive that cleanup and make the actual failing frame and
            // accessibility tree available in the uploaded artifact.
            device.executeShellCommand(
                    "screencap -p /sdcard/device-smoke-failure.png");
        }
        assertNotNull("Expected visible text: " + text, object);
        return object;
    }
}
