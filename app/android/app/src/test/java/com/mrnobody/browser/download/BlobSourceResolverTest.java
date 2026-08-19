package com.mrnobody.browser.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public class BlobSourceResolverTest {

    private static BlobSourceResolver.Candidate c(String url, String kind) {
        return new BlobSourceResolver.Candidate(url, kind);
    }

    @Test
    public void pngWingBlobResolvesToThePrimaryPngNotItsThumbnail() {
        String full = "https://w7.pngwing.com/pngs/422/211/"
                + "png-transparent-messi-champion-football-fifa-world-cup-trophy.png";
        String thumbnail = "https://w7.pngwing.com/pngs/422/211/"
                + "png-transparent-messi-champion-football-fifa-world-cup-trophy-thumbnail.png";

        String resolved = BlobSourceResolver.resolve(Arrays.asList(
                c(thumbnail, "resource"),
                c("https://assets.pngwing.com/public/pw.js", "resource"),
                c(full, "content")
        ), "image/png");

        assertEquals(full, resolved);
    }

    @Test
    public void newestMatchingResourceCanBackAGeneratedBlobLink() {
        String resolved = BlobSourceResolver.resolve(Arrays.asList(
                c("https://cdn.example/file/report.pdf", "resource"),
                c("https://cdn.example/app.js", "resource")
        ), "application/pdf");

        assertEquals("https://cdn.example/file/report.pdf", resolved);
    }

    @Test
    public void unrelatedPageResourcesAreNotGuessed() {
        assertNull(BlobSourceResolver.resolve(Arrays.asList(
                c("https://ads.example/banner.js", "resource"),
                c("https://fonts.example/inter.woff2", "resource")
        ), "application/octet-stream"));
    }

    @Test
    public void pagePrivateUrlsAreNeverReturnedToTheHttpEngine() {
        assertNull(BlobSourceResolver.resolve(Arrays.asList(
                c("blob:https://example.com/123", "download"),
                c("data:image/png;base64,AAAA", "content")
        ), "image/png"));
    }
}
