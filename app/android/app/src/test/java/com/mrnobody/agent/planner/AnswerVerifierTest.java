package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The check that would have caught the answer the user got: five restaurants,
 * four cited publications, none of them among the pages that were read.
 */
public class AnswerVerifierTest {

    private static final List<String> SOURCES = Arrays.asList(
            "https://www.tripadvisor.com/Restaurants-g304026-c14-Lagos.html",
            "https://guardian.ng/life/food/lagos-chinese-restaurants/",
            "https://www.pulse.ng/lifestyle/food-travel/chinese-restaurants-lagos/abc123");

    @Test
    public void theAnswerThatStartedThisIsFlagged() {
        String hallucinated = "| 1 | Dragon City | Victoria Island | authentic dim sum | "
                + "SabiAbuja – Top 10 Chinese Restaurants in Lagos |\n"
                + "| 2 | Golden Dragon | Ikeja | Peking duck | NigerianFact.com |\n"
                + "Sources: wanderlog.com, sabiabuja.com";

        AnswerVerifier.Report report = AnswerVerifier.check(hallucinated, SOURCES);

        assertFalse("it cites nothing that was read", report.grounded);
        assertTrue(report.unsupportedHosts.contains("wanderlog.com"));
        assertTrue(report.unsupportedHosts.contains("sabiabuja.com"));
        assertTrue(report.hasProblems());
    }

    @Test
    public void aCitedAnswerPasses() {
        String grounded = "Two places come up repeatedly: Dragon City [1] and Golden Gate [2]. "
                + "Neither source ranks them.";
        AnswerVerifier.Report report = AnswerVerifier.check(grounded, SOURCES);

        assertTrue(report.grounded);
        assertEquals(Arrays.asList(1, 2), report.citations);
        assertTrue(report.unsupportedHosts.isEmpty());
        assertFalse(report.hasProblems());
    }

    @Test
    public void namingASourceThatWasReadCountsAsGrounded() {
        AnswerVerifier.Report report = AnswerVerifier.check(
                "According to tripadvisor.com the list changes monthly.", SOURCES);
        assertTrue(report.grounded);
        assertTrue(report.unsupportedHosts.isEmpty());
    }

    @Test
    public void aCitationOutsideTheSourceListIsNotCounted() {
        // "[7]" when three sources were read is not a citation, it is a number.
        AnswerVerifier.Report report = AnswerVerifier.check("As shown in [7].", SOURCES);
        assertTrue(report.citations.isEmpty());
        assertFalse(report.grounded);
    }

    @Test
    public void anEmptyAnswerIsNotGrounded() {
        assertFalse(AnswerVerifier.check("", SOURCES).grounded);
        assertFalse(AnswerVerifier.check(null, SOURCES).grounded);
    }

    @Test
    public void theNoteTellsTheReaderWhatWasActuallyRead() {
        AnswerVerifier.Report report = AnswerVerifier.check("Dragon City is best.", SOURCES);
        String note = AnswerVerifier.note(report, SOURCES);

        assertTrue(note, note.contains("does not cite"));
        assertTrue(note, note.contains("Sources actually read"));
        assertTrue(note, note.contains("guardian.ng"));
        assertTrue(note, note.contains("[1]"));
    }

    @Test
    public void aCleanAnswerGetsSourcesButNoWarning() {
        AnswerVerifier.Report report = AnswerVerifier.check("Dragon City [1].", SOURCES);
        String note = AnswerVerifier.note(report, SOURCES);

        assertFalse(note, note.contains("⚠︎"));
        assertTrue(note, note.contains("Sources actually read"));
    }

    @Test
    public void withNoSourcesEverythingIsUnverifiable() {
        AnswerVerifier.Report report = AnswerVerifier.check("Anything at all.", Collections.emptyList());
        assertFalse(report.grounded);
        assertTrue(AnswerVerifier.note(report, Collections.emptyList()).contains("does not cite"));
    }

    @Test
    public void thePromptForbidsTheBehaviourWeSaw() {
        String prompt = GroundedPrompt.build(
                "best chinese restaurants in lagos", "\n[1] Guardian\nhttps://guardian.ng\ntext", true);

        assertTrue(prompt, prompt.contains("ONLY"));
        assertTrue(prompt, prompt.contains("Cite every claim"));
        assertTrue(prompt, prompt.contains("Do not name a source that is not listed"));
        assertTrue(prompt, prompt.contains("guardian.ng"));
    }

    @Test
    public void thePromptSaysWhenOnlySnippetsWereAvailable() {
        String prompt = GroundedPrompt.build("q", "[1] x", false);
        assertTrue(prompt, prompt.contains("could not be read"));
    }
}
