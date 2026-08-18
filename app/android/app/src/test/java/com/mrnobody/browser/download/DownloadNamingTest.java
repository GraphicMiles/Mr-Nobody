package com.mrnobody.browser.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Naming a download is the difference between a file that opens and a file the
 * user has to rename by hand. The reported case: a .mkv arrived as
 * {@code downloadfile.bin} because the server said octet-stream.
 */
public class DownloadNamingTest {

    @Test
    public void theReportedCase_mkvServedAsOctetStream() {
        String name = DownloadNaming.fileName(
                "https://cdn.example.com/files/Some.Movie.2026.1080p.mkv?token=abc123",
                null,
                "application/octet-stream");
        assertEquals("Some.Movie.2026.1080p.mkv", name);
    }

    @Test
    public void theServersOwnNameWins() {
        String name = DownloadNaming.fileName(
                "https://example.com/d/9f2b1c",
                "attachment; filename=\"Holiday Video.mkv\"",
                "application/octet-stream");
        assertEquals("Holiday Video.mkv", name);
    }

    @Test
    public void rfc5987NamesAreDecoded() {
        String name = DownloadNaming.fileName(
                "https://example.com/d/1",
                "attachment; filename*=UTF-8''Rapport%20Final%20%C3%A9t%C3%A9.pdf",
                null);
        assertEquals("Rapport Final été.pdf", name);
    }

    @Test
    public void theExtendedNameBeatsThePlainOne() {
        String name = DownloadNaming.fileName(
                "https://example.com/d/1",
                "attachment; filename=\"fallback.bin\"; filename*=UTF-8''real%20name.mkv",
                null);
        assertEquals("real name.mkv", name);
    }

    @Test
    public void aMissingExtensionIsTakenFromTheMimeType() {
        assertEquals("report.pdf", DownloadNaming.fileName(
                "https://example.com/generate", "attachment; filename=report", "application/pdf"));
        assertEquals("clip.mkv", DownloadNaming.fileName(
                "https://example.com/x", "attachment; filename=clip", "video/x-matroska"));
    }

    @Test
    public void aRouteThatIsNotAFileNameDoesNotBecomeOne() {
        // "watch" is a route, not a file; the type is what we have to go on.
        assertEquals("download.mp4", DownloadNaming.fileName(
                "https://example.com/watch?v=abcd", null, "video/mp4"));
    }

    @Test
    public void withNothingToGoOnItIsHonestlyABinary() {
        assertEquals("download.bin", DownloadNaming.fileName(
                "https://example.com/stream/9f2b1c", null, "application/octet-stream"));
    }

    @Test
    public void aNameCannotEscapeItsDirectory() {
        String name = DownloadNaming.fileName(
                "https://example.com/x",
                "attachment; filename=\"../../../../data/data/com.mrnobody.browser/evil.sh\"",
                null);
        assertTrue(name, !name.contains("/"));
        assertTrue(name, !name.contains(".."));
        assertEquals("evil.sh", name);
    }

    @Test
    public void hostileCharactersAreReplacedNotKept() {
        String name = DownloadNaming.sanitize("re:port*?<>|.pdf");
        assertTrue(name, name.endsWith(".pdf"));
        for (String bad : new String[]{":", "*", "?", "<", ">", "|"}) {
            assertTrue(name + " still contains " + bad, !name.contains(bad));
        }
    }

    @Test
    public void aHiddenFileCannotBeCreatedByName() {
        assertEquals("bashrc", DownloadNaming.sanitize(".bashrc"));
    }

    @Test
    public void veryLongNamesAreTruncatedButKeepTheirExtension() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 400; i++) huge.append('a');
        String name = DownloadNaming.sanitize(huge + ".mkv");
        assertTrue(name.length() <= 120);
        assertTrue(name.endsWith(".mkv"));
    }

    @Test
    public void urlEncodedNamesAreDecoded() {
        assertEquals("My Movie.mkv", DownloadNaming.fileName(
                "https://example.com/files/My%20Movie.mkv", null, null));
    }

    @Test
    public void octetStreamTellsUsNothing() {
        assertNull(DownloadNaming.extensionForMime("application/octet-stream"));
        assertNull(DownloadNaming.extensionForMime("binary/octet-stream"));
        assertNull(DownloadNaming.extensionForMime(null));
    }

    @Test
    public void commonTypesMapToTheExtensionPeopleExpect() {
        assertEquals("mkv", DownloadNaming.extensionForMime("video/x-matroska"));
        assertEquals("mp3", DownloadNaming.extensionForMime("audio/mpeg"));
        assertEquals("jpg", DownloadNaming.extensionForMime("image/jpeg"));
        assertEquals("apk", DownloadNaming.extensionForMime(
                "application/vnd.android.package-archive"));
        assertEquals("pdf", DownloadNaming.extensionForMime("application/pdf; charset=binary"));
    }

    @Test
    public void anUnknownButPlausibleSubtypeIsUsed() {
        assertEquals("flac", DownloadNaming.extensionForMime("audio/flac"));
        assertEquals("wasm", DownloadNaming.extensionForMime("application/wasm"));
    }

    @Test
    public void aQueryStringIsNotPartOfTheName() {
        assertEquals("song.mp3", DownloadNaming.fileName(
                "https://cdn.example.com/a/song.mp3?Expires=1&Signature=xyz", null, null));
    }
}
