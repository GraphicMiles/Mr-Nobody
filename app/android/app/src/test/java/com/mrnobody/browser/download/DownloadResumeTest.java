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

    // --------------------------------------------------------------------
    // The reported-size bug: a 30 MB file near completion showing under half.
    // --------------------------------------------------------------------

    /**
     * The exact shape of the bug. We resumed a 30 MB file at 12 MB and the CDN
     * answered the range with the length of the <em>whole</em> file rather
     * than the remainder. Adding our offset to that counts the first 12 MB
     * twice: the total reads 42 MB, and a download one byte from done sits at
     * 71%. Content-Range states the real total, so it wins.
     */
    @Test
    public void aStatedTotalBeatsArithmeticOnTheBodyLength() {
        assertEquals(31_457_280L, DownloadResume.totalSize(
                206, 12_582_912L, 31_457_280L,
                "bytes 12582912-31457279/31457280"));
    }

    /** With no Content-Range, the old arithmetic is still the best guess. */
    @Test
    public void withoutAStatedTotalTheRemainderIsStillAdded() {
        assertEquals(100_000_000L,
                DownloadResume.totalSize(206, 92_000_000L, 8_000_000L, null));
    }

    /** A server that will not commit to a size must not be invented one. */
    @Test
    public void anUnknownStatedTotalIsNotATotal() {
        assertEquals(DownloadRecord.UNKNOWN_SIZE,
                DownloadResume.statedTotal("bytes 0-1023/*"));
        assertEquals(DownloadRecord.UNKNOWN_SIZE, DownloadResume.statedTotal(null));
        assertEquals(DownloadRecord.UNKNOWN_SIZE, DownloadResume.statedTotal("garbage"));
        assertEquals(DownloadRecord.UNKNOWN_SIZE, DownloadResume.statedTotal("bytes 0-1/"));
    }

    @Test
    public void aWellFormedStatedTotalIsRead() {
        assertEquals(31_457_280L, DownloadResume.statedTotal("bytes 0-31457279/31457280"));
    }

    /**
     * Content-Length measures the compressed body while the stream we count
     * is decompressed. A percentage of two different measurements is not a
     * percentage, so the size has to be reported unknown.
     */
    @Test
    public void aCompressedBodyMeansTheLengthDescribesSomethingElse() {
        assertTrue(DownloadResume.lengthDescribesTheStream(null));
        assertTrue(DownloadResume.lengthDescribesTheStream(""));
        assertTrue(DownloadResume.lengthDescribesTheStream("identity"));
        assertFalse(DownloadResume.lengthDescribesTheStream("gzip"));
        assertFalse(DownloadResume.lengthDescribesTheStream("br"));
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
    public void resumedRangeMustBeginAtTheRequestedOffset() {
        assertTrue(DownloadResume.startsAt("bytes 12582912-31457279/31457280", 12_582_912L));
        assertFalse(DownloadResume.startsAt("bytes 0-31457279/31457280", 12_582_912L));
        assertFalse(DownloadResume.startsAt("bytes */31457280", 12_582_912L));
        assertFalse(DownloadResume.startsAt(null, 12_582_912L));
    }

    @Test
    public void knownLengthMustMatchBeforeCompletion() {
        assertTrue(DownloadResume.isComplete(100, 100));
        assertFalse(DownloadResume.isComplete(99, 100));
        assertFalse(DownloadResume.isComplete(101, 100));
        assertTrue(DownloadResume.isComplete(99, DownloadRecord.UNKNOWN_SIZE));
    }

    @Test
    public void resumedResponseMustNotReplaceThePrefixValidator() {
        assertTrue(DownloadResume.responseMatchesValidator("\"old\"", "\"old\"", null));
        assertFalse(DownloadResume.responseMatchesValidator("\"old\"", "\"new\"", null));
        assertTrue(DownloadResume.responseMatchesValidator(
                "Tue, 18 Aug 2026 00:00:00 GMT", "\"newly-added\"",
                "Tue, 18 Aug 2026 00:00:00 GMT"));
    }

    @Test
    public void omittedResponseValidatorCanStillHaveHonouredIfRange() {
        assertTrue(DownloadResume.responseMatchesValidator("\"old\"", null, null));
        assertFalse(DownloadResume.responseMatchesValidator(null, null, null));
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
