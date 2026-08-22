package com.mrnobody.agent.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OAuthTokenTest {
    @Test public void encryptedStorePayloadRoundTripsExpiryAndRefresh() throws Exception {
        OAuthToken token = new OAuthToken("access-secret", "refresh-secret", "Bearer",
                "design:read design:write", 100_000L);
        OAuthToken restored = OAuthToken.decode(token.encode());
        assertEquals("access-secret", restored.accessToken);
        assertEquals("refresh-secret", restored.refreshToken);
        assertTrue(restored.usable(0L));
        assertFalse(restored.usable(50_000L));
    }
}
