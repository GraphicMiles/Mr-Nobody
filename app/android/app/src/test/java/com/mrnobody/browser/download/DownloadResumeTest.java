package com.mrnobody.browser.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rules that decide whether a stopped download can be continued.
 *
 * <p>Mr Nobody performs its own transfers, which is what makes pause and
 * resume possible at all — the system downloader had neither. The risk that
 * comes with it is silent corruption: appending to a file the server has
 * replaced produces the right number of bytes and the wrong film. These tests
 * pin the decisions that prevent it.
 */
public class DownloadResumeTest {

    @Test
    public void continuingAsksForEverythingFromTheOffset() {
        assertEquals("bytes=1048576-", DownloadResume.rangeHeader(1048576));
    }

    @Test
    public void aRangeAnsweredWithAWholeBodyRestartsFromZero() {
        // We asked to continue from 1 MB and got 200: the server either
        // ignored the range or If-Range says the file changed. Appending here
        // is how a resumed download becomes a corrupt one.
        assertTrue(DownloadResume.mustRestart(200, 1048576));
    }

    @Test
    public void aRangeAnsweredWithARangeContinues() {
        assertFalse(DownloadResume.mustRestart(206, 1048576));
    }

    @Test
    public void aFirstAttemptIsNeverARestart() {
        assertFalse("nothing to restart from", DownloadResume.mustRestart(200, 0));
    }

    @Test
    public void onlyTwoStatusCodesCarryBytesWeMayWrite() {
        assertTrue(DownloadResume.isUsable(200));
        assertTrue(DownloadResume.isUsable(206));
        assertFalse(DownloadResume.isUsable(403));
        assertFalse(DownloadResume.isUsable(404));
        assertFalse(DownloadResume.isUsable(302));
        assertFalse(DownloadResume.isUsable(416));
    }

    @Test
    public void aPartialResponseProvesResumability() {
        assertTrue(DownloadResume.supportsRanges(206, null));
    }

    @Test
    public void aServerThatAdvertisesRangesIsBelieved() {
        assertTrue(DownloadResume.supportsRanges(200, "bytes"));
        assertTrue(DownloadResume.supportsRanges(200, " Bytes "));
    }

    @Test
    public void aServerThatRefusesRangesIsNotResumable() {
        // "none" is the explicit refusal; absent is an unknown we treat as no,
        // so the UI never offers a Resume that would silently start over.
        assertFalse(DownloadResume.supportsRanges(200, "none"));
        assertFalse(DownloadResume.supportsRanges(200, null));
    }

    @Test
    public void aResumedDownloadReportsTheSizeOfTheWholeFile() {
        // Content-Length on a 206 is the length of the remainder. Using it as
        // the total is why a resumed download can sit at "8%" forever.
        assertEquals(100_000_000L, DownloadResume.totalSize(206, 92_000_000L, 8_000_000L));
    }

    @Test
    public void afreshDownloadReportsTheDeclaredLength() {
        assertEquals(8_000_000L, DownloadResume.totalSize(200, 0, 8_000_000L));
    }

    @Test
    public void aServerThatWillNotSayHowBigTheFileIsStaysUnknown() {
        assertEquals(DownloadRecord.UNKNOWN_SIZE, DownloadResume.totalSize(200, 0, -1));
    }

    @Test
    public void anEtagIsPreferredOverADate() {
        assertEquals("\"abc123\"",
                DownloadResume.validator("\"abc123\"", "Tue, 18 Aug 2026 00:00:00 GMT"));
    }

    @Test
    public void aDateIsUsedWhenThereIsNoEtag() {
        assertEquals("Tue, 18 Aug 2026 00:00:00 GMT",
                DownloadResume.validator(null, "Tue, 18 Aug 2026 00:00:00 GMT"));
        assertEquals("Tue, 18 Aug 2026 00:00:00 GMT",
                DownloadResume.validator("  ", "Tue, 18 Aug 2026 00:00:00 GMT"));
    }

    @Test
    public void noValidatorMeansNoIfRangeHeader() {
        assertNull(DownloadResume.validator(null, null));
        assertNull(DownloadResume.validator("", " "));
    }

    @Test
    public void refusalsAreExplainedInWordsNotCodes() {
        assertEquals("The file is no longer there (404)", DownloadResume.message(404, "Not Found"));
        assertEquals("The server refused this download (403)",
                DownloadResume.message(403, "Forbidden"));
        assertEquals("The server could not continue this download",
                DownloadResume.message(416, "Range Not Satisfiable"));
        assertTrue(DownloadResume.message(503, "Unavailable").contains("try again"));
    }

    @Test
    public void anUnfamiliarCodeStillSaysSomethingUseful() {
        assertEquals("Server returned 418 I'm a teapot",
                DownloadResume.message(418, "I'm a teapot"));
        assertEquals("Server returned 418", DownloadResume.message(418, null));
    }
}
