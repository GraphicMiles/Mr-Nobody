package com.mrnobody.agent.execution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionIdentityTest {

    private static Map<String, String> params() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("url", "https://example.com/design");
        p.put("format", "png");
        return p;
    }

    @Test
    public void identicalRunStepAndEffectProduceTheSameKey() {
        ExecutionIdentity a = ExecutionIdentity.of(7, "run-a", "export", 0,
                "design", "export", params());
        ExecutionIdentity b = ExecutionIdentity.of(7, "run-a", "export", 0,
                "design", "export", params());

        assertEquals(a.idempotencyKey(), b.idempotencyKey());
        assertEquals(a.operationFingerprint(), b.operationFingerprint());
        assertTrue(a.isDurable());
    }

    @Test
    public void reusedTaskGetsANewKeyForANewRun() {
        ExecutionIdentity first = ExecutionIdentity.of(7, "run-a", "export", 0,
                "design", "export", params());
        ExecutionIdentity next = ExecutionIdentity.of(7, "run-b", "export", 0,
                "design", "export", params());

        assertNotEquals(first.idempotencyKey(), next.idempotencyKey());
    }

    @Test
    public void effectSlotSeparatesTwoEffectsInsideOneLogicalStep() {
        ExecutionIdentity first = ExecutionIdentity.of(7, "run-a", "export", 0,
                "design", "export", params());
        ExecutionIdentity second = ExecutionIdentity.of(7, "run-a", "export", 1,
                "design", "export", params());

        assertNotEquals(first.idempotencyKey(), second.idempotencyKey());
    }

    @Test
    public void keyAndFingerprintDoNotContainRawArguments() {
        ExecutionIdentity identity = ExecutionIdentity.of(7, "run-a", "export", 0,
                "design", "export", params());

        assertFalse(identity.idempotencyKey().contains("example.com"));
        assertFalse(identity.operationFingerprint().contains("example.com"));
        assertEquals(64, identity.idempotencyKey().length());
    }
}
