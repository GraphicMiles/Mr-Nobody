package com.mrnobody.agent.mcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class CanvaMcpWiringTest {
    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/" + path)),
                StandardCharsets.UTF_8);
    }

    @Test public void transportUsesNetworkGateAndOfficialHttpsEndpoint() throws Exception {
        String transport = source("com/mrnobody/agent/mcp/StreamableHttpMcpTransport.java");
        assertTrue(transport.contains("NetworkGate.openHttp(endpoint)"));
        assertTrue(CanvaMcpConfig.ENDPOINT.startsWith("https://mcp.canva.com/"));
    }

    @Test public void oauthCallbackNeverEntersTaskDeepLinks() throws Exception {
        String activity = source("com/mrnobody/browser/MainActivity.java");
        String oauth = source("com/mrnobody/agent/mcp/CanvaOAuthManager.java");
        assertTrue(activity.contains("consumeCanvaCallback(uri)"));
        assertTrue(activity.contains("OAuth code natively"));
        assertTrue(oauth.contains("code_challenge_method\", \"S256"));
        assertTrue(oauth.contains("constantTime(pending.state"));
        assertTrue(oauth.contains("EncryptedPreferences"));
    }

    @Test public void signedExportsAreScrubbedAfterImmediateDownload() throws Exception {
        String adapter = source("com/mrnobody/agent/mcp/CanvaMcpDesignAdapter.java");
        assertTrue(adapter.contains("done.url = \"canva-export-consumed\""));
        assertFalse(adapter.contains("setApiKey"));
        assertFalse(adapter.contains("apiKey("));
    }

    @Test public void buildContainsNoCanvaSecret() throws Exception {
        String gradle = new String(Files.readAllBytes(Paths.get("build.gradle")),
                StandardCharsets.UTF_8);
        assertTrue(gradle.contains("MRNOBODY_CANVA_MCP_CLIENT_ID"));
        assertTrue("custom BuildConfig fields require the AGP feature",
                gradle.contains("buildConfig = true"));
        assertFalse(gradle.toLowerCase().contains("canva_client_secret"));
    }
}
