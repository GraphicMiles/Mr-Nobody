package com.mrnobody.agent.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CanvaMcpConfigTest {
    @Test
    public void liveMcpFailsClosedWithoutHostedCimd() {
        String previous = System.getProperty("mrnobody.canva.clientId");
        try {
            System.clearProperty("mrnobody.canva.clientId");
            assertFalse(CanvaMcpConfig.isBuildConfigured());
            System.setProperty("mrnobody.canva.clientId",
                    "https://example.com/oauth/mrnobody-client.json");
            assertTrue(CanvaMcpConfig.isBuildConfigured());
            assertEquals("https://mcp.canva.com/mcp", CanvaMcpConfig.ENDPOINT);
        } finally {
            if (previous == null) System.clearProperty("mrnobody.canva.clientId");
            else System.setProperty("mrnobody.canva.clientId", previous);
        }
    }
}
