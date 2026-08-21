package com.mrnobody.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Pins cross-language/lifecycle fixes that the plain JVM cannot execute directly. */
public class MediumLowFixWiringTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String java(String rel) throws Exception {
        return read("src/main/java/com/mrnobody/" + rel);
    }

    @Test
    public void coldDeepLinkIsPulledAfterDartRegisters() throws Exception {
        String activity = java("browser/MainActivity.java");
        String dart = read("../../lib/main.dart");
        assertTrue(activity.contains("pendingDeepLink"));
        assertTrue(activity.contains("\"getInitialLink\""));
        assertTrue(dart.contains("setMethodCallHandler"));
        assertTrue(dart.contains("invokeMethod<String>('getInitialLink')"));
    }

    @Test
    public void remoteStreamCannotOverwriteATerminalTask() throws Exception {
        String worker = java("agent/dispatcher/RemoteWorker.java");
        assertTrue(worker.contains("completeIf(Task.Status.RUNNING"));
        assertTrue(worker.contains("failIf(Task.Status.RUNNING"));
        assertTrue(worker.contains("result stream ended before a terminal event"));
        String client = java("remote/RemoteClient.java");
        assertTrue(client.contains("throw new TerminalEvent()"));
    }

    @Test
    public void memoryEraseWaitsForEverySchedule() throws Exception {
        String activity = java("browser/MainActivity.java");
        String scheduler = java("agent/tasks/WorkManagerTaskScheduler.java");
        assertTrue(activity.contains("cancelAllTaskSchedules()"));
        assertTrue(activity.contains("cancelAndAwait("));
        assertTrue(scheduler.contains("oneShot.getResult().get"));
        assertTrue(scheduler.contains("repeating.getResult().get"));
    }

    @Test
    public void unsupportedSiteSensorsAreLeastPrivilegeAndExplicitlyDenied() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        assertFalse(manifest.contains("android.permission.CAMERA"));
        assertFalse(manifest.contains("android.permission.RECORD_AUDIO"));
        assertFalse(manifest.contains("android.permission.ACCESS_FINE_LOCATION"));
        String webView = java("browser/webview/MrNobodyWebView.java");
        assertTrue(webView.contains("onPermissionRequest"));
        assertTrue(webView.contains("request.deny()"));
        assertTrue(webView.contains("onGeolocationPermissionsShowPrompt"));
    }

    @Test
    public void cleartextPolicyAndCredentialEndpointsAreExplicit() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""));
        assertFalse(manifest.contains("<data android:scheme=\"http\" />"));
        String router = read("../../lib/router/intent_router.dart");
        assertTrue(router.contains("startsWith('http://')"));
        assertTrue(router.contains("return 'https://"));
        String provider = java("agent/ai/OpenAiCompatibleProvider.java");
        String remote = java("remote/RemoteClient.java");
        assertTrue(provider.contains("EndpointPolicy.secureBaseReason(baseUrl)"));
        assertTrue(remote.contains("EndpointPolicy.requireSecureBase(baseUrl)"));
    }

    @Test
    public void staleApprovalAndEvictedTabTargetsAreInvalidated() throws Exception {
        String approval = java("browser/ApprovalPrompt.java");
        assertTrue(approval.contains("AtomicReference<Session> ACTIVE"));
        assertTrue(approval.contains("expireFor(activity)"));
        assertTrue(approval.contains("dialog.dismiss()"));
        String tabs = java("browser/webview/TabWebViews.java");
        int eviction = tabs.indexOf("private static void evictBeyondLimit");
        assertTrue(tabs.indexOf("MrNobodyWebView.releaseChannel(oldest)", eviction) > eviction);
    }

    @Test
    public void UnscopedMemoryToolIsNotAdvertisedOrRegistered() throws Exception {
        String app = java("browser/MrNobodyApp.java");
        String planner = java("agent/planner/AutonomousPlanner.java");
        assertFalse(app.contains("registerTool(new com.mrnobody.agent.tools.MemoryTool())"));
        assertFalse(planner.contains("memory takes {q}"));
    }
}
