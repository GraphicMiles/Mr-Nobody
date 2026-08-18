package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The task: "search for reacher season 4 episode from nkiri.ink and download
 * it". The result cited Prime Video, Amazon and JustWatch, and the debug panel
 * said the answer could not be verified.
 *
 * <p>Two separate faults, which happened to hide each other:
 *
 * <ol>
 *   <li>The planner only opened a page the user named if they typed a scheme.
 *       "nkiri.ink" is not "https://nkiri.ink", so the one site the user asked
 *       about was the one site never read.
 *   <li>The verifier's host matcher accepted nine suffixes. {@code .ink} was
 *       not among them, so the answer could discuss nkiri.ink at length and be
 *       reported as naming nothing off-source.
 * </ol>
 *
 * <p>The warning the user saw was correct but understated: it said the answer
 * cited none of the pages read. It could not say that the answer's subject was
 * a site nobody had visited.
 */
public class NamedSiteTest {

    // ------------------------------------------------------- fault 1: planner

    @Test
    public void aBareHostnameIsNowFetchable() {
        assertEquals("https://nkiri.ink",
                DeterministicEngine.findUrl(
                        "search for reacher season 4 episode from nkiri.ink and download it"));
    }

    @Test
    public void anExplicitUrlIsStillUsedExactlyAsWritten() {
        assertEquals("https://nkiri.ink/reacher-s04e01/",
                DeterministicEngine.findUrl("get https://nkiri.ink/reacher-s04e01/ for me"));
    }

    @Test
    public void aTypedUrlWinsOverABareMentionElsewhere() {
        assertEquals("https://example.com/page",
                DeterministicEngine.findUrl("open https://example.com/page not other.com"));
    }

    @Test
    public void anInstructionNamingNoSiteStillHasNothingToOpen() {
        assertNull(DeterministicEngine.findUrl("summarize the news today"));
        assertNull(DeterministicEngine.findUrl("download Reacher.S04E01.1080p.mkv"));
    }

    // ------------------------------------------------------ fault 2: verifier

    private static final List<String> SOURCES = Arrays.asList(
            "https://www.primevideo.com/detail/0K16R3PLUFGC2JUE457C26O4OD",
            "https://www.amazon.com/Reacher-Season-4/dp/B0H8N9812R",
            "https://www.justwatch.com/us/tv-show/jack-reacher/season-4");

    @Test
    public void theAnswerTheUserGotNamesAnUnreadSite() {
        String answer = "The provided sources contain information about Reacher - Season 4 on "
                + "Prime Video, Amazon, and JustWatch, but none of them mention the site "
                + "nkiri.ink or provide any link or download instructions for an episode from "
                + "that site.";

        AnswerVerifier.Report report = AnswerVerifier.check(answer, SOURCES);

        assertTrue("nkiri.ink was invisible to the old suffix list",
                report.unsupportedHosts.contains("nkiri.ink"));
        assertTrue(report.hasProblems());
    }

    @Test
    public void theNoteNamesTheSiteThatWasNotRead() {
        String answer = "None of the sources mention nkiri.ink.";
        AnswerVerifier.Report report = AnswerVerifier.check(answer, SOURCES);
        String note = AnswerVerifier.note(report, SOURCES);

        assertTrue(note, note.contains("nkiri.ink"));
        assertTrue(note, note.contains("not among the pages read"));
    }

    @Test
    public void hostsOnSuffixesTheOldListMissedAreCaught() {
        for (String host : new String[]{"warez.to", "tracker.ru", "mirror.xyz", "cdn.download"}) {
            AnswerVerifier.Report report =
                    AnswerVerifier.check("Try " + host + " instead.", SOURCES);
            assertTrue(host + " should be reported as off-source",
                    report.unsupportedHosts.contains(host));
        }
    }

    @Test
    public void aSourceThatWasActuallyReadIsNotReportedAgainstTheAnswer() {
        AnswerVerifier.Report report = AnswerVerifier.check(
                "primevideo.com lists the season [1].", SOURCES);

        assertTrue(report.grounded);
        assertTrue(report.unsupportedHosts.toString(), report.unsupportedHosts.isEmpty());
        assertTrue(AnswerVerifier.note(report, SOURCES).indexOf('⚠') < 0);
    }

    @Test
    public void aFilenameInTheAnswerIsNotMistakenForASite() {
        // The likely shape of a correct answer to this very request. It must
        // not be decorated with a warning about a host that does not exist.
        AnswerVerifier.Report report = AnswerVerifier.check(
                "The file is Reacher.S04E01.1080p.WEB-DL.mkv, listed on primevideo.com [1].",
                SOURCES);

        assertTrue(report.unsupportedHosts.toString(), report.unsupportedHosts.isEmpty());
        assertTrue(report.grounded);
    }
}
