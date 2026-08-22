package com.mrnobody.agent.dispatcher;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.execution.ExecutionLedger;

import org.junit.Test;

public class LocalWorkerLaneTest {
    @Test
    public void productionDefaultIsTwoFairLanes() {
        LocalWorker worker = new LocalWorker(new NoopEngine(), ExecutionLedger.NONE);
        assertEquals(2, worker.availableLanes());
    }

    private static final class NoopEngine implements AgentEngine {
        @Override public void run(Context context, Task task, Cancellation cancellation) { }
        @Override public ToolResult callTool(Context c, String n, ToolRequest r) {
            return ToolResult.fail("unused");
        }
    }
}
