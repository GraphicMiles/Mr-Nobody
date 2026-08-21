package com.mrnobody.browser.net;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Source-wiring proof for the bundled Tor — the pieces that only a device
 * can execute (service start, broadcasts, the Gradle packaging) are pinned
 * against their sources, per the harness's throwing-stub rule.
 */
public class EmbeddedTorWiringTest {

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(Paths.get(rel)), StandardCharsets.UTF_8);
    }

    private static String java(String rel) throws IOException {
        return read("src/main/java/com/mrnobody/" + rel);
    }

    @Test
    public void torServiceIsAddressedByNameNeverByImport() throws IOException {
        String src = java("browser/net/EmbeddedTor.java");
        assertTrue(src.contains("setClassName(app, SERVICE_CLASS)"));
        assertTrue("no compile-time coupling to the AAR",
                !src.contains("import org.torproject"));
    }

    @Test
    public void bothBroadcastChannelsAndThePortProbeAreWired() throws IOException {
        String src = java("browser/net/EmbeddedTor.java");
        assertTrue("package-scoped system broadcast",
                src.contains("registerStatusReceiver"));
        assertTrue("NOT_EXPORTED on 33+",
                src.contains("Context.RECEIVER_NOT_EXPORTED"));
        assertTrue("LocalBroadcastManager by reflection",
                src.contains("androidx.localbroadcastmanager.content.LocalBroadcastManager"));
        assertTrue("a missed broadcast cannot wedge the wait: the port is probed too",
                src.contains("socksListening()"));
        assertTrue("the probe targets the port OrbotTorRoute already trusts",
                src.contains("OrbotTorRoute.PORT"));
    }

    @Test
    public void privacyControllerStartsEmbeddedTorOnlyAfterOrbotDeclined() throws IOException {
        String src = java("browser/net/PrivacyController.java");
        int gate = src.indexOf("EmbeddedTorPolicy.shouldStart(true, portUp && !oursStarting,");
        int start = src.indexOf("EmbeddedTor.requestStart(context);");
        assertTrue("a foreign 9050 listener (Orbot) suppresses the bundled start; "
                        + "our own mid-bootstrap listener does not count as Orbot",
                gate > 0 && start > gate);
        assertTrue("the apply thread never blocks — TorService.onCreate needs it",
                !src.contains("EmbeddedTor.startAndAwait(context, EmbeddedTorPolicy.APPLY_WAIT_MS)"));
        assertTrue("the context-less overload keeps the old Orbot-only behaviour",
                src.contains("return apply(mode, settings, null);"));
    }

    @Test
    public void torUseIsDisclosedAndAttributed() throws IOException {
        // BSD-3 permits commercial embedding but requires notice retention;
        // the Tor trademark forbids implied endorsement; and the user must
        // know their traffic enters the Tor network at all.
        String settings = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("../../lib/screens/settings_screen.dart")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("the mode picker names Tor plainly",
                settings.contains("built-in Tor / Orbot / proxy"));
        assertTrue("About carries the BSD attribution",
                settings.contains("BSD-3-Clause"));
        assertTrue("About disclaims Tor Project affiliation",
                settings.contains("not affiliated with or"));
    }

    @Test
    public void bootstrapAutoAppliesInsteadOfDemandingARetry() throws IOException {
        // The auto-apply UX (2026-08-21): no manual re-toggle. The apply
        // reports pending, a background waiter holds the request open for
        // the real bootstrap budget, and the mode applies itself at the
        // first circuit — still fail-closed the whole time.
        String src = java("browser/net/PrivacyController.java");
        assertTrue("a still-bootstrapping Tor is pending, not a refusal",
                src.contains("return new Result(mode, current, false, false, null, true);"));
        assertTrue("the waiter gets the full bootstrap budget",
                src.contains("EmbeddedTorPolicy.BOOTSTRAP_WAIT_MS"));
        assertTrue("a Tor still reporting STARTING is waited on, not refused",
                src.contains("while (!up && EmbeddedTor.isStarting()"));
        assertTrue("only a hopeless bootstrap ends at the hard cap",
                src.contains("EmbeddedTorPolicy.BOOTSTRAP_HARD_CAP_MS"));
        assertTrue("the cap failure explains itself",
                src.contains("EmbeddedTorPolicy.slowNetworkMessage()"));
        assertTrue("a newer user decision supersedes the waiter",
                src.contains("if (generation != PENDING_GENERATION.get()) return;"));
        assertTrue("the waiter's second attempt cannot recurse into pending",
                src.contains("applyInternal(mode, settings, context, false, generation)"));
        assertTrue("what was achieved is persisted, like the toggle path",
                src.contains("settings.setPrivacyMode(result.effective.name())"));
        assertTrue("async failures surface exactly once",
                src.contains("consumePendingProblem"));
    }

    @Test
    public void theChannelAndTheDartUiCarryThePendingState() throws IOException {
        String activity = java("browser/MainActivity.java");
        assertTrue(activity.contains("m.put(\"pending\", r.pending);"));
        assertTrue(activity.contains("case \"privacyStatus\":"));
        assertTrue(activity.contains("PrivacyController.consumePendingProblem()"));

        String bridge = read("../../lib/bridge/native_bridge.dart");
        assertTrue(bridge.contains("privacyStatus"));

        String state = read("../../lib/state/app_state.dart");
        assertTrue("the UI watches for the auto-applied flip",
                state.contains("_watchTorStartup()"));
        assertTrue("the user is told what is happening, not refused",
                state.contains("Starting built-in Tor"));
        assertTrue("the poll is bounded and covers the extended bootstrap waits",
                state.contains("> 240"));
    }

    @Test
    public void theAppHandsItsContextToBothApplyPaths() throws IOException {
        String src = java("browser/MrNobodyApp.java");
        assertTrue(src.contains("PrivacyController.apply(mode, settings, appInstance)"));
        assertTrue("the startup NOBODY restore can also start the bundled Tor",
                src.contains("PrivacyMode.fromName(settings.privacyMode()), settings, this)"));
    }

    @Test
    public void readinessIsStatusOnNotAListeningPort() throws IOException {
        // Device-observed 2026-08-21: TorService binds 9050 BEFORE the first
        // circuit; the port probe alone applied Nobody against a Tor that
        // could not carry traffic, and check.torproject.org timed out.
        String tor = java("browser/net/EmbeddedTor.java");
        assertTrue("readiness prefers TorService's own status word",
                tor.contains("if (status != null) return EmbeddedTorPolicy.statusMeansReady(status);"));
        assertTrue("the status read never runs for a Tor we did not start",
                tor.contains("if (!startRequested) return null;"));

        String pc = java("browser/net/PrivacyController.java");
        assertTrue("a listener owned by our STARTING Tor is not availability",
                pc.contains("boolean oursStarting = route instanceof OrbotTorRoute && EmbeddedTor.isStarting();"));
        assertTrue("the final gate refuses a mid-bootstrap listener",
                pc.contains("if (route instanceof OrbotTorRoute && EmbeddedTor.isStarting())"));
    }

    @Test
    public void theTorBenchmarkKnowsAboutTheBundledTor() throws IOException {
        // "Orbot not reachable" was a stale ❌ on a build whose Tor starts
        // on demand. The check now fails only when no Tor of any kind exists.
        String diag = java("debug/Diagnostics.java");
        assertTrue(diag.contains("built-in Tor starts on demand"));
        assertTrue(diag.contains("EmbeddedTor.isBundled()"));
    }

    @Test
    public void theRouteIsAimedAtTheRealSocksPortOnEveryApply() throws IOException {
        String pc = java("browser/net/PrivacyController.java");
        int aims = pc.split("OrbotTorRoute.setActivePort\\(EmbeddedTor.readySocksPort\\(\\)\\)", -1).length - 1;
        assertTrue("apply must re-aim the port before availability is judged; found " + aims,
                aims >= 2);
    }

    @Test
    public void agentDownloadsSendARefererAndABrowserClientString() throws IOException {
        // A resolved icon URL answered 403: hotlink-protecting CDNs refuse a
        // bare "MrNobody/1.0" with no Referer (device, 2026-08-21).
        String tool = java("agent/tools/DownloadTool.java");
        assertTrue(tool.contains("BROWSER_UA"));
        assertTrue(tool.contains("ParamSpec.url(\"referer\""));
        assertTrue(tool.contains("engine.enqueue(url, name, null, BROWSER_UA,"));
    }

    @Test
    public void theManifestDeclaresTheServiceUnexported() throws IOException {
        String manifest = read("src/main/AndroidManifest.xml");
        int service = manifest.indexOf("org.torproject.jni.TorService");
        assertTrue(service > 0);
        int exported = manifest.indexOf("android:exported=\"false\"", service);
        assertTrue("only Mr Nobody may drive its Tor",
                exported > service && exported - service < 200);
    }

    @Test
    public void gradlePackagesTorFromMavenCentral() throws IOException {
        String gradle = read("build.gradle");
        assertTrue(gradle.contains("info.guardianproject:tor-android:0.4.7.14"));
        assertTrue("the compileSdk-37 constraint that forbids 0.4.9.x is documented",
                gradle.contains("compileSdk-37 requirement"));
        assertTrue(gradle.contains("info.guardianproject:jtorctl:0.4.5.7"));
        // ABI selection belongs to the CI's --split-per-abi build, whose
        // splits conflict with ndk.abiFilters when both are present.
        assertTrue("abiFilters must stay out of the release path",
                !gradle.contains("abiFilters \""));
    }

    @Test
    public void torServicesOwnRuntimeDependencyIsPackagedExplicitly() throws IOException {
        // tor-android's POM declares no dependencies; forgetting this line
        // crashed the app on the first Nobody toggle (device, 2026-08-21).
        String gradle = read("build.gradle");
        assertTrue(gradle.contains(
                "androidx.localbroadcastmanager:localbroadcastmanager:1.1.0"));
    }

    @Test
    public void jniKeepRulesProtectTorServiceFromR8() throws IOException {
        // Release builds minify; libtor.so resolves TorService's fields by
        // JNI at runtime. Without these rules the release APK dies with
        // NoSuchFieldError("torConfiguration") — device, 2026-08-21. The
        // 0.4.7.14 AAR ships no consumer rules, so the app carries them.
        String rules = read("proguard-rules.pro");
        assertTrue(rules.contains("-keep class org.torproject.jni.** { *; }"));
        assertTrue(rules.contains("-keep class net.freehaven.tor.control.** { *; }"));
        String gradle = read("build.gradle");
        assertTrue("the rules file must actually be applied to release",
                gradle.contains("proguardFile \"proguard-rules.pro\""));
    }

    @Test
    public void theAvailabilityCheckNeitherLoadsNativeCodeNorTrustsAnUnrunnableService() throws IOException {
        String src = java("browser/net/EmbeddedTor.java");
        assertTrue("Class.forName must not run TorService's native-loading static init",
                src.contains("Class.forName(SERVICE_CLASS, false,"));
        assertTrue("a build missing TorService's runtime dep reads as not-bundled, not a crash",
                src.contains("Class.forName(\"androidx.localbroadcastmanager.content.LocalBroadcastManager\","));
    }
}
