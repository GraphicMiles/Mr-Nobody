package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The download-link resolver: given a page's links, pick the file the user
 * asked for. This is the decision that must not be wrong — a bad choice puts a
 * wrong file on someone's phone.
 */
public class DownloadLinkResolverTest {

    @Test
    public void identifiesDownloadableUrls() {
        assertTrue(DownloadLinkResolver.isDownloadable("https://example.com/movie.mkv"));
        assertTrue(DownloadLinkResolver.isDownloadable("https://example.com/archive.zip?dl=1"));
        assertTrue(DownloadLinkResolver.isDownloadable("https://example.com/report.PDF"));
        assertFalse(DownloadLinkResolver.isDownloadable("https://example.com/page"));
        assertFalse(DownloadLinkResolver.isDownloadable("https://example.com/page.html"));
        assertFalse(DownloadLinkResolver.isDownloadable("example.com/file.zip")); // no scheme
        assertFalse(DownloadLinkResolver.isDownloadable("main.zip"));             // not a URL
        assertFalse(DownloadLinkResolver.isDownloadable(null));
    }

    @Test
    public void resolvesTheFirstDownloadableLink() {
        List<String> links = Arrays.asList(
                "https://example.com/", "https://example.com/film.mkv", "https://example.com/other.mp4");
        assertEquals("https://example.com/film.mkv", DownloadLinkResolver.resolve(links, null));
    }

    @Test
    public void prefersTheUsersNamedSite() {
        List<String> links = Arrays.asList(
                "https://mirror.com/file.zip", "https://nkiri.ink/file.zip");
        assertEquals("https://nkiri.ink/file.zip",
                DownloadLinkResolver.resolve(links, "nkiri.ink"));
        // www. is normalised away on both sides.
        assertEquals("https://www.nkiri.ink/file.zip",
                DownloadLinkResolver.resolve(
                        Arrays.asList("https://www.nkiri.ink/file.zip", "https://mirror.com/file.zip"),
                        "nkiri.ink"));
    }

    @Test
    public void returnsNullWhenNothingIsDownloadable() {
        assertNull(DownloadLinkResolver.resolve(
                Arrays.asList("https://example.com/", "https://example.com/about"), null));
        assertNull(DownloadLinkResolver.resolve(null, null));
        assertNull(DownloadLinkResolver.resolve(Arrays.asList(), "nkiri.ink"));
    }

    @Test
    public void skipsNonDownloadableLinksToFindTheFile() {
        List<String> links = Arrays.asList(
                "https://example.com/login", "https://example.com/cart",
                "https://example.com/downloads/release.apk");
        assertEquals("https://example.com/downloads/release.apk",
                DownloadLinkResolver.resolve(links, null));
    }
}
