package com.mrnobody.agent.planner;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Source-wiring pins for the harness batch: tool scope, the blocking answer
 * gate, and the outcome check. Behaviour is proven by
 * {@code DeterministicEngineScopeTest} and the pure tests; this keeps a
 * refactor from silently disconnecting any of the three.
 */
public class HarnessWiringTest {

    private static String engine() throws IOException {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/mrnobody/agent/planner/DeterministicEngine.java")),
                StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ tool scope

    @Test
    public void everyRunPathSetsItsScopeBeforeItsFirstToolCall() throws IOException {
        String src = engine();
        assertTrue("routed actions scope to their one tool",
                src.contains("runScope = ToolScope.routed(plan.steps().get(0).tool);"));
        int deterministic = src.indexOf("executeResearch(context, task, plan,");
        int scopeBefore = src.lastIndexOf("runScope = ToolScope.research(", deterministic);
        assertTrue("research scope is set before the cascade runs",
                scopeBefore > 0 && scopeBefore < deterministic);
        assertTrue("a fresh run clears any previous scope",
                src.contains("runScope = null;"));
    }

    @Test
    public void theAutonomousPlannerIsOnlyShownTheScope() throws IOException {
        assertTrue(engine().contains("planner.nextStep(asked, transcript, runScope)"));
    }

    @Test
    public void allRunPathToolCallsGoThroughTheScopedSeam() throws IOException {
        String src = engine();
        // The only remaining direct pipeline entry is the public callTool
        // pair the host uses; every run-path site says callScoped.
        int scoped = count(src, "callScoped(context,");
        assertTrue("expected the run paths to use the scoped seam, found "
                + scoped, scoped >= 9);
        assertTrue(src.contains("ToolScope.deniedMessage(name)"));
    }

    // ------------------------------------------------------ blocking verifier

    @Test
    public void verificationBlocksRetriesOnceThenFallsBackToExtraction() throws IOException {
        String src = engine();
        int gate = src.indexOf("AnswerGate.decide(report.hasProblems() || figures.hasProblems()");
        assertTrue("the gate drives the verify step", gate > 0);
        int retryAsk = src.indexOf("AnswerGate.correction(", gate);
        int fallback = src.indexOf("AnswerGate.fallbackNote()", gate);
        int extractive = src.indexOf("ExtractiveAnswer.compose(", gate);
        assertTrue("the corrective re-ask quotes the findings", retryAsk > gate);
        assertTrue("the fallback is the extractive answer, which cannot hallucinate",
                extractive > gate && fallback > extractive);
        assertTrue("the retry respects the spend cap",
                src.contains("r.cap == null || r.cap.check(r.usage, r.lastPrompt.length()) == null"));
        assertTrue("the prompt is kept for the re-ask", src.contains("r.lastPrompt = prompt;"));
    }

    // ---------------------------------------------------------- outcome check

    @Test
    public void everyAnswerPassesTheOutcomeCheckAndMismatchesAreLogged() throws IOException {
        String src = engine();
        int append = src.indexOf("OutcomeCheck.note(");
        int logged = src.indexOf("outcome mismatch", append);
        assertTrue(append > 0);
        assertTrue("a mismatch is evidence, so it is recorded", logged > append);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            n++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return n;
    }
}
