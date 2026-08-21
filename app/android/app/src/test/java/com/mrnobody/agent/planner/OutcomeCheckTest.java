package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The engine's two verifiable promises about its own outcome. */
public class OutcomeCheckTest {

    private static final List<String> READS = Arrays.asList(
            "https://en.wikipedia.org/wiki/Eiffel_Tower",
            "https://www.toureiffel.paris/en/history");

    // -------------------------------------------------------------- download

    @Test
    public void aDownloadThatNeverHappenedIsSaidInAsManyWords() {
        String note = OutcomeCheck.note("download the annual report", null, READS);
        assertTrue(note, note.contains("asked for a download"));
        assertTrue(note, note.contains("no file was downloaded"));
    }

    @Test
    public void aCompletedOrRunningDownloadSatisfiesTheAsk() {
        assertEquals("", OutcomeCheck.note("download the annual report",
                "Downloaded report.pdf to Documents.", READS));
        assertEquals("", OutcomeCheck.note("download the annual report",
                "Download still in progress: report.pdf (not finished yet).", READS));
    }

    @Test
    public void anExplicitFailureIsNotEchoedTwice() {
        assertEquals("", OutcomeCheck.note("download the annual report",
                "Download failed: HTTP 404.", READS));
        assertEquals("", OutcomeCheck.note("download the annual report",
                "No downloadable file was found on the pages read.", READS));
    }

    @Test
    public void aQuestionOwesNoDownload() {
        assertEquals("", OutcomeCheck.note("eiffel tower construction history", null, READS));
    }

    // ------------------------------------------------------------ named site

    @Test
    public void aNamedSiteAnsweredFromSubstitutesIsCalledOut() {
        String note = OutcomeCheck.note(
                "get infinity war from nkiri.ink", "Downloaded x.mkv to Movies.", READS);
        assertTrue(note, note.contains("nkiri.ink"));
        assertTrue(note, note.contains("not among the pages actually read"));
    }

    @Test
    public void readingTheNamedSiteOrItsSubdomainSatisfiesTheAsk() {
        assertEquals("", OutcomeCheck.note("eiffel tower history from wikipedia.org",
                null, READS)); // en.wikipedia.org is wikipedia.org
        assertEquals("", OutcomeCheck.note("history from toureiffel.paris", null, READS));
    }

    @Test
    public void noReadsMeansTheListingAnswerAlreadyExplainsItself() {
        // Snippets-only answers say "the pages could not be read"; piling a
        // named-site note on top of that is noise, not honesty.
        assertEquals("", OutcomeCheck.note("eiffel tower from wikipedia.org",
                null, Collections.emptyList()));
    }

    @Test
    public void emptyInputsProduceNoNote() {
        assertEquals("", OutcomeCheck.note("", null, READS));
        assertEquals("", OutcomeCheck.note(null, null, READS));
    }
}
