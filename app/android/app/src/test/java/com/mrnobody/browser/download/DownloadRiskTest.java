package com.mrnobody.browser.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloadRiskTest {

    @Test
    public void ordinaryDocumentsAndMediaDoNotInterruptTheUser() {
        assertFalse(DownloadRisk.assess("photo.png", "image/png").requiresConfirmation);
        assertFalse(DownloadRisk.assess("report.pdf", "application/pdf").requiresConfirmation);
        assertFalse(DownloadRisk.assess("movie.mkv", "video/x-matroska").requiresConfirmation);
        assertFalse(DownloadRisk.assess("archive.zip", "application/zip").requiresConfirmation);
    }

    @Test
    public void installersExecutablesScriptsAndMacrosRequireConfirmation() {
        assertTrue(DownloadRisk.assess("update.apk", null).requiresConfirmation);
        assertTrue(DownloadRisk.assess("setup.exe", "application/octet-stream").requiresConfirmation);
        assertTrue(DownloadRisk.assess("run.sh", "text/plain").requiresConfirmation);
        assertTrue(DownloadRisk.assess("invoice.docm", null).requiresConfirmation);
        assertTrue(DownloadRisk.assess("payload", "application/x-executable").requiresConfirmation);
        assertTrue(DownloadRisk.assess("download.txt", "text/plain",
                "https://example.com/run.sh").requiresConfirmation);
    }

    @Test
    public void unidentifiedBinaryFilesRequireConfirmation() {
        assertTrue(DownloadRisk.assess("download.bin", "application/octet-stream")
                .requiresConfirmation);
        assertTrue(DownloadRisk.assess("download", "application/octet-stream")
                .requiresConfirmation);
    }

    @Test
    public void extensionMatchingIsCaseInsensitiveAndIgnoresQueryText() {
        assertTrue(DownloadRisk.assess("APP.APK?token=1", "application/octet-stream")
                .requiresConfirmation);
    }
}
