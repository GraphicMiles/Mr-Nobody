package com.mrnobody.browser;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.tasks.TaskEventStore;
import com.mrnobody.browser.core.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

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
        MrNobodyApp.tasks().clear();
        MrNobodyApp.taskEvents().clearAll();
        try {
            instrumentation.getUiAutomation().grantRuntimePermission(
                    target.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        } catch (Throwable ignored) {
            // Android 12 has no runtime notification permission.
        }
    }

    @Test
    public void launchPersistSettingAndCompleteLocalTaskAcrossBackground() throws Exception {
        launchMain(false);
        waitForAppFrame();

        // Flutter renders into one Android view on these images, so its text is
        // not reliably enumerable through UiAutomator. Drive the two stable,
        // full-width controls by display-relative coordinates and prove each
        // action through the native state it is required to change.
        tap(0.50, 0.63); // Start browsing
        assertTrue("first-launch action should persist",
                waitForFirstLaunch(true, 5_000));
        tap(0.90, 0.94); // Settings destination
        Thread.sleep(700);
        tap(0.50, 0.25); // Search suggestions row
        assertTrue("UI toggle should persist suggestions ON",
                waitForSuggestions(true, 5_000));

        // Recreate the Activity without killing the instrumentation process.
        // The native preference must survive and the rebuilt Flutter shell must
        // still be navigable back to the same setting.
        device.pressHome();
        launchMain(true);
        waitForAppFrame();
        assertTrue("first launch should remain completed after Activity recreation",
                waitForFirstLaunch(true, 5_000));
        assertTrue("suggestions should remain ON after Activity recreation",
                waitForSuggestions(true, 5_000));
        tap(0.90, 0.94);
        Thread.sleep(700);
        tap(0.50, 0.25);
        assertTrue("UI toggle should persist suggestions OFF",
                waitForSuggestions(false, 5_000));

        // MainActivity is alive, so this exercises singleTop onNewIntent and
        // the production deep-link channel rather than a test-only entry point.
        Intent taskIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("mrnobody://task?instruction=hi"));
        taskIntent.setPackage(target.getPackageName());
        taskIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        target.startActivity(taskIntent);

        // Leave immediately: WorkManager/foreground execution, not a visible
        // Activity loop, must finish the deterministic local task.
        Thread.sleep(150);
        device.pressHome();
        Task task = waitForCompletedTask(TASK_TIMEOUT);
        assertNotNull("deep link should create and complete a durable task", task);
        assertTrue("local conversation result",
                "Hi. What would you like me to do next?".equals(task.result()));
        assertPipelineEvents(task.id());

        // Foreground the existing task route. Its collapsed Thought row lives
        // at a stable top-of-thread position; tapping it must expand and then
        // collapse to a visibly different frame.
        launchMain(false);
        waitForAppFrame();
        File dir = target.getExternalFilesDir(null);
        assertNotNull(dir);
        File collapsed = new File(dir, "device-smoke-collapsed.png");
        File expanded = new File(dir, "device-smoke-expanded.png");
        assertTrue("collapsed task screenshot", device.takeScreenshot(collapsed));
        tap(0.45, 0.17);
        Thread.sleep(700);
        assertTrue("expanded task screenshot", device.takeScreenshot(expanded));
        assertTrue("Thought tap should expand the pipeline", screenshotsDiffer(collapsed, expanded));
        tap(0.45, 0.17);
        Thread.sleep(500);
        File restored = new File(dir, "device-smoke.png");
        assertTrue("restored task screenshot", device.takeScreenshot(restored));
        assertTrue("second Thought tap should collapse the pipeline",
                screenshotsDiffer(expanded, restored));

        // connectedDebugAndroidTest removes both APKs before the workflow's
        // evidence step. Shell-owned copies survive package removal.
        copyForEvidence(collapsed, "/sdcard/device-smoke-collapsed.png");
        copyForEvidence(expanded, "/sdcard/device-smoke-expanded.png");
        copyForEvidence(restored, "/sdcard/device-smoke.png");
    }

    private void launchMain(boolean recreateTask) {
        Intent launch = target.getPackageManager()
                .getLaunchIntentForPackage(target.getPackageName());
        assertNotNull("launch intent", launch);
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
        if (recreateTask) flags |= Intent.FLAG_ACTIVITY_CLEAR_TASK;
        launch.addFlags(flags);
        target.startActivity(launch);
        device.waitForIdle();
    }

    private void waitForAppFrame() throws InterruptedException {
        // Cold Flutter frames on the hosted emulator take several seconds. A
        // fixed readiness delay avoids depending on the unavailable virtual
        // accessibility descendants while remaining well inside the job cap.
        Thread.sleep(4_000);
        assertTrue("Mr Nobody should own the foreground",
                target.getPackageName().equals(device.getCurrentPackageName()));
    }

    private void tap(double xFraction, double yFraction) {
        int x = (int) (device.getDisplayWidth() * xFraction);
        int y = (int) (device.getDisplayHeight() * yFraction);
        assertTrue("screen tap at " + xFraction + "," + yFraction, device.click(x, y));
    }

    private boolean waitForFirstLaunch(boolean expected, long timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;
        do {
            if (new Settings(target).isFirstLaunchDone() == expected) return true;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private boolean waitForSuggestions(boolean expected, long timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;
        do {
            if (new Settings(target).areSuggestionsEnabled() == expected) return true;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private Task waitForCompletedTask(long timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;
        do {
            List<Task> tasks = MrNobodyApp.tasks().recent(1);
            if (!tasks.isEmpty()) {
                Task task = tasks.get(0);
                if ("hi".equals(task.instruction())
                        && task.status() == Task.Status.COMPLETED) return task;
                if (task.status() == Task.Status.FAILED
                        || task.status() == Task.Status.CANCELLED) return task;
            }
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private void assertPipelineEvents(long taskId) {
        List<TaskEventStore.Event> events = MrNobodyApp.taskEvents().eventsFor(taskId);
        assertTrue("event sequence should be contiguous",
                MrNobodyApp.taskEvents().isContiguous(taskId));
        boolean understanding = false;
        boolean responding = false;
        boolean finished = false;
        for (TaskEventStore.Event event : events) {
            String detail = event.detail == null ? "" : event.detail;
            understanding |= detail.contains("Understanding the request");
            responding |= detail.contains("Responding");
            finished |= TaskEventStore.TASK_FINISHED.equals(event.type);
        }
        assertTrue("semantic Understanding stage", understanding);
        assertTrue("semantic Responding stage", responding);
        assertTrue("durable task-finished event", finished);
    }

    private boolean screenshotsDiffer(File first, File second) {
        Bitmap a = BitmapFactory.decodeFile(first.getAbsolutePath());
        Bitmap b = BitmapFactory.decodeFile(second.getAbsolutePath());
        if (a == null || b == null || a.getWidth() != b.getWidth()
                || a.getHeight() != b.getHeight()) return false;
        int changed = 0;
        for (int y = 0; y < a.getHeight(); y += 8) {
            for (int x = 0; x < a.getWidth(); x += 8) {
                if (a.getPixel(x, y) != b.getPixel(x, y) && ++changed > 100) return true;
            }
        }
        return false;
    }

    private void copyForEvidence(File source, String destination) throws Exception {
        device.executeShellCommand("cp " + source.getAbsolutePath() + " " + destination);
    }
}
