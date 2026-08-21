package com.mrnobody.browser;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.tasks.TaskEventStore;
import com.mrnobody.browser.core.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Android-runtime half of the hosted smoke suite.
 *
 * <p>The Flutter integration test drives first launch, settings, conversation,
 * and Thought expansion with Flutter finders. This test crosses the OS boundary:
 * launcher intent, singleTop task deep link, Home/background, WorkManager,
 * SQLite/event restoration, foreground return, and device screenshot evidence.
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
        assertTrue("test preference reset",
                target.getSharedPreferences("mrnobody_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(Settings.KEY_FIRST_LAUNCH_DONE, true)
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
    public void providerKeyRoundTripsThroughAndroidKeystore() {
        Settings settings = new Settings(target);
        String synthetic = "android-keystore-test-only";
        settings.removeApiKey("openai");
        try {
            assertTrue("provider key should encrypt", settings.setApiKey("openai", synthetic));
            assertEquals("provider key should decrypt", synthetic, settings.apiKey("openai"));
        } finally {
            settings.removeApiKey("openai");
        }
    }

    @Test
    public void completeDeepLinkedLocalTaskWhileBackgroundedAndRestoreIt() throws Exception {
        launchMain();
        waitForMainActivity();

        // MainActivity is alive, so this exercises singleTop onNewIntent and
        // the production Flutter deep-link channel rather than a test-only path.
        Intent taskIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("mrnobody://task?instruction=hi"));
        taskIntent.setPackage(target.getPackageName());
        taskIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        target.startActivity(taskIntent);

        // External task links must be visible and explicitly confirmed before
        // any work is enqueued. Confirm through the production Flutter dialog.
        UiObject2 startTask = device.wait(Until.findObject(By.text("Start task")), 5_000L);
        assertNotNull("deep-link task confirmation", startTask);
        startTask.click();

        // Leave immediately: WorkManager/foreground execution, not a visible
        // Activity loop, must finish and persist the task.
        Thread.sleep(150);
        device.pressHome();
        Task task = waitForCompletedTask(TASK_TIMEOUT);
        assertNotNull("deep link should create and complete a durable task", task);
        assertTrue("local conversation result",
                "Hi. What would you like me to do next?".equals(task.result()));
        assertPipelineEvents(task.id());

        // Foreground the existing Activity/task route and retain visual proof.
        launchMain();
        waitForMainActivity();
        File dir = target.getExternalFilesDir(null);
        assertNotNull(dir);
        File screenshot = new File(dir, "device-smoke.png");
        assertTrue("restored task screenshot", device.takeScreenshot(screenshot));
        device.executeShellCommand(
                "cp " + screenshot.getAbsolutePath() + " /sdcard/device-smoke.png");
    }

    private void launchMain() {
        Intent launch = target.getPackageManager()
                .getLaunchIntentForPackage(target.getPackageName());
        assertNotNull("launch intent", launch);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        target.startActivity(launch);
        device.waitForIdle();
    }

    private void waitForMainActivity() throws InterruptedException {
        instrumentation.waitForIdleSync();
        Thread.sleep(4_000);
        final boolean[] resumed = {false};
        instrumentation.runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                resumed[0] |= activity instanceof MainActivity;
            }
        });
        assertTrue("Mr Nobody MainActivity should be resumed", resumed[0]);
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
}
