package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Scope turns "the planner would not use that tool" into "it cannot". */
public class ToolScopeTest {

    private static final Set<String> ALL = new LinkedHashSet<>(Arrays.asList(
            "search", "http", "browser", "download", "terminal"));

    @Test
    public void aQuestionGetsReadingToolsOnly() {
        Set<String> scope = ToolScope.research(false, ALL);
        assertTrue(scope.contains("search"));
        assertTrue(scope.contains("http"));
        assertTrue(scope.contains("browser"));
        assertFalse("a question is not a download", scope.contains("download"));
        assertFalse("the terminal is never research equipment", scope.contains("terminal"));
    }

    @Test
    public void aDownloadInstructionAddsExactlyTheDownloadTool() {
        Set<String> scope = ToolScope.research(true, ALL);
        assertTrue(scope.contains("download"));
        assertFalse(scope.contains("terminal"));
        assertEquals(4, scope.size());
    }

    @Test
    public void scopeNeverInventsAnUnregisteredTool() {
        Set<String> scope = ToolScope.research(true,
                new LinkedHashSet<>(Arrays.asList("search", "http")));
        assertEquals(new LinkedHashSet<>(Arrays.asList("search", "http")), scope);
    }

    @Test
    public void aRoutedActionIsScopedToItsOneTool() {
        assertEquals(java.util.Collections.singleton("terminal"), ToolScope.routed("terminal"));
        assertTrue(ToolScope.routed(null).isEmpty());
        assertTrue(ToolScope.routed("").isEmpty());
    }

    @Test
    public void theDenialNamesTheToolAndTheReason() {
        String msg = ToolScope.deniedMessage("terminal");
        assertTrue(msg.contains("terminal"));
        assertTrue(msg.contains("not in scope"));
    }
}
