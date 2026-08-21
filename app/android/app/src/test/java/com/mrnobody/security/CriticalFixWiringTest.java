package com.mrnobody.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Pins Android/Dart seams that cannot be exercised by the plain-JVM harness. */
public class CriticalFixWiringTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String java(String rel) throws Exception {
        return read("src/main/java/com/mrnobody/" + rel);
    }

    @Test
    public void remotePromptsDoNotAutoInjectTaskHistory() throws Exception {
        String engine = java("agent/planner/DeterministicEngine.java");
        assertFalse(engine.contains("MemoryDigest.digest("));
        assertFalse(engine.contains("MrNobodyApp.tasks().recent(50)"));
    }

    @Test
    public void downloadRiskIsRecheckedAfterFinalHeaders() throws Exception {
        String engine = java("browser/download/DownloadEngine.java");
        int metadata = engine.indexOf("refineMetadata(conn, connected.url)");
        int assess = engine.indexOf("DownloadRisk.assess(", metadata);
        int sink = engine.indexOf("createSink()", assess);
        assertTrue(metadata > 0 && assess > metadata && sink > assess);
        assertTrue(engine.contains("!record.riskyApproved"));
    }

    @Test
    public void providerCancellationReachesTheSocket() throws Exception {
        String planner = java("agent/planner/DeterministicEngine.java");
        assertTrue(planner.contains("provider.streamCancellable("));
        assertTrue(planner.contains("request.cancel()"));
        String openAi = java("agent/ai/OpenAiCompatibleProvider.java");
        String gemini = java("agent/ai/GeminiProvider.java");
        assertTrue(openAi.contains("request.bind(conn)"));
        assertTrue(gemini.contains("request.bind(conn)"));
    }

    @Test
    public void taskCancellationStopsAgentDownloads() throws Exception {
        String pipeline = java("agent/core/ToolPipeline.java");
        String tool = java("agent/tools/DownloadTool.java");
        String engine = java("browser/download/DownloadEngine.java");
        assertTrue(pipeline.contains("tool.execute(context, request,"));
        assertTrue(tool.contains("Cancellation cancellation"));
        assertTrue(tool.contains("cancellation.isCancelled()"));
        assertTrue(engine.contains("cancel(id);"));
    }

    @Test
    public void autonomousFetchesRevalidateRedirects() throws Exception {
        String http = java("agent/tools/HttpTool.java");
        assertTrue(http.contains("openFollowingRedirects"));
        assertTrue(http.contains("NetworkTargetPolicy.requirePublic(current"));
        assertTrue(http.contains("setInstanceFollowRedirects(false)"));
        String downloads = java("browser/download/DownloadEngine.java");
        assertTrue(downloads.contains("NetworkTargetPolicy.requirePublic(current"));
        assertTrue(downloads.contains("headerForUrl(current)"));
    }

    @Test
    public void externalTaskLinkRequiresAVisibleDecision() throws Exception {
        String dart = read("../../lib/main.dart");
        assertTrue(dart.contains("_confirmDeepLinkedTask(instruction)"));
        assertTrue(dart.contains("Start this agent task?"));
        assertFalse(dart.contains("if (instruction.isNotEmpty) _runTask(instruction)"));
        String manifest = read("src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android:scheme=\"mrnobody\" android:host=\"task\""));
        assertFalse(manifest.contains("<data android:scheme=\"mrnobody\" />"));
    }
}
