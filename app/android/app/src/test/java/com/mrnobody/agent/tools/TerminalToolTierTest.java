package com.mrnobody.agent.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.policy.PolicyGate;

import org.junit.Test;

public class TerminalToolTierTest {

    @Test
    public void aReadOnlyWorkspaceCommandIsReadTier() {
        TerminalTool tool = new TerminalTool(new PolicyGate());
        assertEquals(Tier.READ, tool.tierFor(ToolRequest.of("terminal", "cmd", "ls")));
        assertEquals(Tier.READ, tool.tierFor(ToolRequest.of("terminal", "cmd", "cat note.txt")));
        assertEquals(Tier.READ, tool.tierFor(ToolRequest.of("terminal", "cmd", "sha256 a.pdf")));
    }

    @Test
    public void anUnknownCommandStaysExecSoItConfirms() {
        TerminalTool tool = new TerminalTool(new PolicyGate());
        assertEquals(Tier.EXEC, tool.tierFor(ToolRequest.of("terminal", "cmd", "touch x")));
    }

    @Test
    public void aHardDenyIsStillRefusedInsideTheTool() {
        TerminalTool tool = new TerminalTool(new PolicyGate());
        ToolResult r = tool.execute(null, ToolRequest.of("terminal", "cmd", "rm -rf /"));
        assertFalse(r.isSuccess());
        assertFalse(r.needsApproval());
    }
}
