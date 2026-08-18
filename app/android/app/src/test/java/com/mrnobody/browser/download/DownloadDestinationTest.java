package com.mrnobody.browser.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The bookkeeping behind "save downloads to the folder I picked".
 *
 * <p>DownloadManager cannot write into a Storage Access Framework tree, so a
 * download is staged in app storage and moved afterwards. The move can happen
 * long after the process that started it has died, so what to move and where
 * has to survive on disk — this is that record, and the parts of it that are
 * pure string work are tested here.
 */
public class DownloadDestinationTest {

    @Test
    public void aPendingMoveSurvivesAsText() {
        String encoded = DownloadDestination.encode(
                "/data/user/0/com.mrnobody.browser/files/staging/Movie.mkv",
                "Movie.mkv",
                "video/x-matroska");

        DownloadDestination.Pending pending = DownloadDestination.decode(encoded);

        assertNotNull(pending);
        assertEquals("/data/user/0/com.mrnobody.browser/files/staging/Movie.mkv", pending.stagedPath);
        assertEquals("Movie.mkv", pending.fileName);
        assertEquals("video/x-matroska", pending.mime);
    }

    @Test
    public void aNameWithSpacesAndPunctuationRoundTrips() {
        String encoded = DownloadDestination.encode(
                "/files/staging/The Film (2026) [1080p].mkv",
                "The Film (2026) [1080p].mkv",
                "video/x-matroska");
        DownloadDestination.Pending pending = DownloadDestination.decode(encoded);
        assertEquals("The Film (2026) [1080p].mkv", pending.fileName);
    }

    @Test
    public void anUnknownTypeIsAbsentRatherThanEmpty() {
        DownloadDestination.Pending pending =
                DownloadDestination.decode(DownloadDestination.encode("/a/b", "b", ""));
        assertNull("an empty mime must not be handed to createDocument", pending.mime);
    }

    @Test
    public void nothingPendingReadsAsNothing() {
        assertNull(DownloadDestination.decode(null));
        assertNull(DownloadDestination.decode(""));
        assertNull(DownloadDestination.decode("\u001f\u001f"));
    }

    @Test
    public void twoDownloadsSharingANameDoNotOverwriteEachOther() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("staging").toFile();
        dir.deleteOnExit();

        assertEquals("Movie.mkv", DownloadDestination.uniqueName(dir, "Movie.mkv"));

        //noinspection ResultOfMethodCallIgnored
        new java.io.File(dir, "Movie.mkv").createNewFile();
        assertEquals("Movie (2).mkv", DownloadDestination.uniqueName(dir, "Movie.mkv"));

        //noinspection ResultOfMethodCallIgnored
        new java.io.File(dir, "Movie (2).mkv").createNewFile();
        assertEquals("Movie (3).mkv", DownloadDestination.uniqueName(dir, "Movie.mkv"));
    }

    @Test
    public void anExtensionlessNameStillGetsAUniqueSuffix() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("staging").toFile();
        dir.deleteOnExit();
        //noinspection ResultOfMethodCallIgnored
        new java.io.File(dir, "archive").createNewFile();
        assertEquals("archive (2)", DownloadDestination.uniqueName(dir, "archive"));
    }
}
