package com.mrnobody.browser.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

/**
 * What a download looks like to the rest of the app.
 *
 * <p>The Downloads screen and the notification read the same record, so the
 * two can never disagree about whether something is paused — a bug the old
 * split between DownloadManager's cursor and our own bookkeeping made easy.
 */
public class DownloadRecordTest {

    private static DownloadRecord film() {
        DownloadRecord r = DownloadRecord.create(
                "https://example.com/f.mkv", "Batman.mkv", "video/x-matroska",
                "Mozilla/5.0", null, "Movies");
        r.id = 7;
        return r;
    }

    @Test
    public void aNewDownloadIsQueuedAndNotFinished() {
        DownloadRecord r = film();
        assertEquals(DownloadRecord.Status.QUEUED, r.status);
        assertTrue(r.status.isActive());
        assertFalse(r.status.isTerminal());
    }

    @Test
    public void onlyTheUserCanResumeSomethingThatStopped() {
        assertTrue(DownloadRecord.Status.PAUSED.isResumable());
        assertTrue(DownloadRecord.Status.WAITING.isResumable());
        // A failed download is worth retrying; a finished or cancelled one is
        // not, and offering the button would be a lie.
        assertTrue(DownloadRecord.Status.FAILED.isResumable());
        assertFalse(DownloadRecord.Status.COMPLETED.isResumable());
        assertFalse(DownloadRecord.Status.CANCELLED.isResumable());
        assertFalse(DownloadRecord.Status.RUNNING.isResumable());
    }

    @Test
    public void progressIsUnknownRatherThanZeroWhenTheServerWillNotSay() {
        DownloadRecord r = film();
        r.bytes = 5_000_000;
        r.total = DownloadRecord.UNKNOWN_SIZE;
        // -1, not 0: a spinner is honest, a bar stuck at 0% is not.
        assertEquals(-1, r.percent());
    }

    @Test
    public void progressIsClampedToSomethingAPersonCanRead() {
        DownloadRecord r = film();
        r.total = 200;
        r.bytes = 50;
        assertEquals(25, r.percent());
        // Servers do lie about Content-Length.
        r.bytes = 400;
        assertEquals(100, r.percent());
    }

    @Test
    public void theUiAndTheNotificationReadTheSameFields() {
        DownloadRecord r = film();
        r.total = 1000;
        r.bytes = 250;
        r.status = DownloadRecord.Status.PAUSED;
        r.destUri = "content://media/external/downloads/42";

        Map<String, Object> m = r.toMap();

        assertEquals(7L, m.get("id"));
        assertEquals("Batman.mkv", m.get("name"));
        assertEquals("PAUSED", m.get("status"));
        assertEquals(25, m.get("percent"));
        assertEquals(250L, m.get("downloaded"));
        assertEquals(1000L, m.get("size"));
        assertEquals("Movies", m.get("folder"));
        assertEquals(true, m.get("canResume"));
        assertEquals("content://media/external/downloads/42", m.get("localUri"));
    }

    @Test
    public void anUnreadableStatusOnDiskDoesNotCrashTheList() {
        // Forward compatibility: a row written by a newer build must not take
        // the Downloads screen down.
        assertEquals(DownloadRecord.Status.QUEUED, DownloadRecord.Status.of("SOMETHING_NEW"));
        assertEquals(DownloadRecord.Status.QUEUED, DownloadRecord.Status.of(null));
        assertEquals(DownloadRecord.Status.PAUSED, DownloadRecord.Status.of("PAUSED"));
    }

    @Test
    public void twoDownloadsSharingANameDoNotOverwriteEachOther() {
        HashSet<String> taken = new HashSet<>(Arrays.asList("Movie.mkv", "Movie (2).mkv"));
        assertEquals("Movie (3).mkv", DownloadDestination.uniqueName(taken, "Movie.mkv"));
        assertEquals("Other.mkv", DownloadDestination.uniqueName(taken, "Other.mkv"));
    }

    @Test
    public void anExtensionlessNameStillGetsAUniqueSuffix() {
        HashSet<String> taken = new HashSet<>(Arrays.asList("archive"));
        assertEquals("archive (2)", DownloadDestination.uniqueName(taken, "archive"));
    }
}
