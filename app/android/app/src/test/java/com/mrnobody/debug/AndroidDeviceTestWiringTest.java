package com.mrnobody.debug;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/** Source wiring for the hosted Android-runtime gate. */
public final class AndroidDeviceTestWiringTest {

    @Test
    public void gradleDeclaresRunnerAndUiAutomatorAsTestOnly() throws Exception {
        String gradle = read("build.gradle");
        assertTrue(gradle.contains("testInstrumentationRunner = \"androidx.test.runner.AndroidJUnitRunner\""));
        assertTrue(gradle.contains("androidTestImplementation \"androidx.test.uiautomator:uiautomator"));
        assertTrue(!gradle.contains("implementation \"androidx.test.uiautomator:uiautomator"));
    }

    @Test
    public void smokeCrossesTheProductionTaskPath() throws Exception {
        String test = read("src/androidTest/java/com/mrnobody/browser/AppDeviceSmokeTest.java");
        assertTrue(test.contains("mrnobody://task?instruction=hi"));
        assertTrue(test.contains("Hi. What would you like me to do next?"));
        assertTrue(test.contains("Understanding the request"));
        assertTrue(test.contains("device.takeScreenshot"));
    }

    @Test
    public void workflowRunsTwoApisAndAlwaysCollectsEvidence() throws Exception {
        String workflow = read("../../../.github/workflows/android-emulator.yml");
        assertTrue(workflow.contains("api-level: [31, 34]"));
        assertTrue(workflow.contains("connectedDebugAndroidTest"));
        assertTrue(workflow.contains("if: always()"));
        assertTrue(workflow.contains("logcat.txt"));
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relative)), StandardCharsets.UTF_8);
    }
}
