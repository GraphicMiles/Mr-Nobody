package com.mrnobody.agent.planner;

import com.mrnobody.agent.core.Task;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FollowUpScopeTest {

    private static Task task(String followUp) {
        Task task = new Task(7, "Research why the sky appears blue");
        task.setFollowUp(followUp);
        return task;
    }

    @Test
    public void independentQuestionDoesNotInheritOriginalSearchTerms() {
        FollowUpScope.Decision d = FollowUpScope.decide(task("who created bitcoin"), false);
        assertEquals(FollowUpScope.Kind.STANDALONE, d.kind);
        assertEquals("who created bitcoin", d.instruction);
        assertFalse(d.instruction.toLowerCase().contains("sky"));
    }

    @Test
    public void explicitReferenceKeepsThreadContext() {
        FollowUpScope.Decision d = FollowUpScope.decide(task("why is it blue?"), false);
        assertEquals(FollowUpScope.Kind.CONTEXTUAL, d.kind);
        assertTrue(d.instruction.contains("Research why the sky appears blue"));
        assertTrue(d.instruction.contains("why is it blue?"));
    }

    @Test
    public void artifactPointerKeepsThreadContext() {
        FollowUpScope.Decision d = FollowUpScope.decide(task("open the second one"), true);
        assertEquals(FollowUpScope.Kind.CONTEXTUAL, d.kind);
        assertTrue(d.instruction.contains("Follow-up from the user"));
    }

    @Test
    public void sourceComparisonSuggestionKeepsContext() {
        FollowUpScope.Decision d = FollowUpScope.decide(
                task("Compare the sources on why the sky appears blue"), false);
        assertEquals(FollowUpScope.Kind.CONTEXTUAL, d.kind);
        assertTrue(d.instruction.contains("Research why the sky appears blue"));
    }

    @Test
    public void acknowledgementReturnsWithoutTools() {
        FollowUpScope.Decision d = FollowUpScope.decide(task("thanks"), false);
        assertEquals(FollowUpScope.Kind.CONVERSATIONAL, d.kind);
        assertTrue(d.isDirectReply());
        assertEquals("You're welcome.", d.directReply);
    }

    @Test
    public void initialGreetingInstructionAnswersLocally() {
        // Regression: the emulator smoke deep link creates a fresh task whose
        // whole instruction is "hi". That must be a direct conversational
        // reply, never a research run that reads a dictionary page.
        Task greeting = new Task(8, "hi");
        FollowUpScope.Decision d = FollowUpScope.decide(greeting, false);
        assertEquals(FollowUpScope.Kind.CONVERSATIONAL, d.kind);
        assertTrue(d.isDirectReply());
        assertEquals("Hi. What would you like me to do next?", d.directReply);
    }

    @Test
    public void initialQuestionInstructionStillRunsThePipeline() {
        Task question = new Task(9, "who created bitcoin");
        FollowUpScope.Decision d = FollowUpScope.decide(question, false);
        assertEquals(FollowUpScope.Kind.NONE, d.kind);
        assertFalse(d.isDirectReply());
        assertEquals("who created bitcoin", d.instruction);
    }

    @Test
    public void noFollowUpUsesOriginalInstruction() {
        FollowUpScope.Decision d = FollowUpScope.decide(task(""), false);
        assertEquals(FollowUpScope.Kind.NONE, d.kind);
        assertEquals("Research why the sky appears blue", d.instruction);
    }
}
