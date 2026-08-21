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
        String src = java("browser/net/PrivacyControllerRoute.java".replace(
                "PrivacyControllerRoute", "PrivacyController"));
        int available = src.indexOf("if (!route.isAvailable() && route instanceof OrbotTorRoute");
        int start = src.indexOf("EmbeddedTor.startAndAwait(context, EmbeddedTorPolicy.APPLY_WAIT_MS)");
        int refuseStarting = src.indexOf("EmbeddedTorPolicy.stillStartingMessage()");
        assertTrue("start only when the port has no listener (Orbot priority)",
                available > 0 && start > available);
        assertTrue("still-bootstrapping is a fail-closed refusal, not a downgrade",
                refuseStarting > start);
        assertTrue("the context-less overload keeps the old Orbot-only behaviour",
                src.contains("return apply(mode, settings, null);"));
    }

    @Test
    public void theAppHandsItsContextToBothApplyPaths() throws IOException {
        String src = java("browser/MrNobodyApp.java");
        assertTrue(src.contains("PrivacyController.apply(mode, settings, appInstance)"));
        assertTrue("the startup NOBODY restore can also start the bundled Tor",
                src.contains("PrivacyMode.fromName(settings.privacyMode()), settings, this)"));
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
    public void gradlePackagesTorFromMavenCentralForTheShippedAbis() throws IOException {
        String gradle = read("build.gradle");
        assertTrue(gradle.contains("info.guardianproject:tor-android:0.4.9.11"));
        assertTrue(gradle.contains("info.guardianproject:jtorctl:0.4.5.7"));
        assertTrue("CI emulators are x86_64 — it must stay",
                gradle.contains("\"x86_64\""));
        assertTrue(gradle.contains("\"arm64-v8a\""));
        assertTrue(gradle.contains("\"armeabi-v7a\""));
        assertTrue("32-bit x86 ships nowhere and pays ~5 MB",
                !gradle.contains("\"x86\""));
    }
}
