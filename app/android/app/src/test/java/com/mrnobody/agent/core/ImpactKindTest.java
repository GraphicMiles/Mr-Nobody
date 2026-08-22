package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImpactKindTest {

    @Test
    public void deleteAndPayAlwaysConfirm() {
        assertTrue(ImpactKind.DELETE.alwaysConfirm());
        assertTrue(ImpactKind.PAY.alwaysConfirm());
        assertFalse(ImpactKind.DRAFT.alwaysConfirm());
        assertFalse(ImpactKind.OBSERVE.alwaysConfirm());
    }

    @Test
    public void designExportIsAnIndependentFinalizationGate() {
        ImpactKind kind = ImpactKind.of("design", "export", "format=pdf");
        assertEquals(ImpactKind.FINALIZE, kind);
        assertTrue(kind.alwaysConfirm());
    }

    @Test
    public void submitIsSend() {
        assertEquals(ImpactKind.SEND, ImpactKind.of("browser", "submit", ""));
        assertEquals(ImpactKind.DRAFT, ImpactKind.of("browser", "type", "hello"));
        assertEquals(ImpactKind.PAY, ImpactKind.of("browser", "click", "place order"));
        assertEquals(ImpactKind.DELETE, ImpactKind.of("browser", "click", "delete post"));
    }
}
