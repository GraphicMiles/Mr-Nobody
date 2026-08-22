package com.mrnobody.agent.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class ProviderSnapshotTest {
    @Test
    public void snapshotRoundTripsWithoutCredentials() {
        ProviderSnapshot original = new ProviderSnapshot(
                "openai-compatible", "https://example.com/v1", "model-x");
        ProviderSnapshot restored = ProviderSnapshot.decode(original.encode());
        assertEquals(original, restored);
        assertFalse(original.encode().contains("secret"));
    }
}
