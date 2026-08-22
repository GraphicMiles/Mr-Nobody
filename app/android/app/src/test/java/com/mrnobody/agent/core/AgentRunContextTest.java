package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import com.mrnobody.agent.ai.LocalProvider;
import com.mrnobody.agent.ai.ProviderSnapshot;

import org.junit.Test;

import java.util.Collections;

public class AgentRunContextTest {
    @Test
    public void eachRunOwnsIndependentGuardsAndScope() {
        AgentRunContext first = context(1L, "run-a");
        AgentRunContext second = context(2L, "run-b");
        first.setToolScope(Collections.singleton("search"));
        second.setToolScope(Collections.singleton("download"));

        assertNotSame(first.repeatGuard, second.repeatGuard);
        assertNotSame(first.budgetGuard, second.budgetGuard);
        assertEquals(Collections.singleton("search"), first.toolScope());
        assertEquals(Collections.singleton("download"), second.toolScope());
    }

    @Test
    public void pooledThreadPropagationRestoresPriorContext() throws Exception {
        AgentRunContext first = context(1L, "run-a");
        AgentRunContext second = context(2L, "run-b");
        AgentRunContext.bind(first);
        try {
            long seen = AgentRunContext.callAs(second,
                    () -> AgentRunContext.current().taskId);
            assertEquals(2L, seen);
            assertEquals(1L, AgentRunContext.current().taskId);
        } finally {
            AgentRunContext.clear();
        }
    }

    private static AgentRunContext context(long taskId, String runId) {
        ProviderSnapshot local = new ProviderSnapshot("local", "", "");
        return new AgentRunContext(taskId, runId, local, Collections.emptyList(),
                new LocalProvider(), "local-device");
    }
}
