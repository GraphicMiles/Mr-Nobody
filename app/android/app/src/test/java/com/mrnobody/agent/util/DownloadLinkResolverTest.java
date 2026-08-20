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
        assertTrue(DownloadLinkResolver.isDownloadable("https://example.com/image.png"));
        assertTrue(DownloadLinkResolver.isDownloadable("https://example.com/photo.jpeg"));
        assertFalse(DownloadLinkResolver.isDownloadable("https://example.com/page"));
        assertFalse(DownloadLinkResolver.isDownloadable("https://example.com/page.html"));
        assertFalse(DownloadLinkResolver.isDownloadable("example.com/file.zip")); // no scheme
        assertFalse(DownloadLinkResolver.isDownloadable("main.zip"));             // not a URL
        assertFalse(DownloadLinkResolver.isDownloadable(null));
    }

    @Test
    public void aDownloadEndpointDoesNotNeedAFileExtension() {
        assertTrue(DownloadLinkResolver.isDownloadable(
                "https://cdn.example.com/download/abc123"));
        assertTrue(DownloadLinkResolver.isDownloadable(
                "https://drive.google.com/uc?export=download&id=1abc"));
        assertTrue(DownloadLinkResolver.isDownloadable(
                "https://files.example.com/getfile?id=99"));
        assertTrue(DownloadLinkResolver.isDownloadable(
                "https://cdn.example.com/objects/a1b2c3d4e5f67890abcdef"));
        assertFalse("a generic page is still a page",
                DownloadLinkResolver.isDownloadable("https://example.com/about"));
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
    public void aHtmlPageNamedLikeAFileIsNotDownloadable() {
        assertFalse(DownloadLinkResolver.isDownloadable(
                "https://host.com/film.mkv.html"));
    }

    @Test
    public void aMatchingFilenameBeatsAnUnrelatedFile() {
        List<String> links = Arrays.asList(
                "https://cdn.example/other.mkv",
                "https://cdn.example/Avengers.Infinity.War.1080p.mkv");
        assertEquals("https://cdn.example/Avengers.Infinity.War.1080p.mkv",
                DownloadLinkResolver.resolve(links, null, "Infinity War"));
    }

    @Test
    public void skipsNonDownloadableLinksToFindTheFile() {
        List<String> links = Arrays.asList(
                "https://example.com/login", "https://example.com/cart",
                "https://example.com/downloads/release.apk");
        assertEquals("https://example.com/downloads/release.apk",
                DownloadLinkResolver.resolve(links, null));
    }

    // ------------------------------------------------------- image downloads

    @Test
    public void imageIntentIsRecognisedAndPlainDownloadsAreNot() {
        assertTrue(DownloadLinkResolver.wantsImage("download a png icon from pngtree"));
        assertTrue(DownloadLinkResolver.wantsImage("get me a wallpaper of the alps"));
        assertTrue(DownloadLinkResolver.wantsImage("download a photo of a cat"));
        assertTrue(DownloadLinkResolver.wantsImage("save this image"));
        assertFalse(DownloadLinkResolver.wantsImage("download infinity war from nkiri.ink"));
        assertFalse(DownloadLinkResolver.wantsImage("download the annual report pdf"));
        assertFalse(DownloadLinkResolver.wantsImage(null));
    }

    @Test
    public void theNamedExtensionIsExtracted() {
        assertEquals(".png", DownloadLinkResolver.requestedImageExt(
                "download a png icon from pngtree"));
        assertEquals(".jpg", DownloadLinkResolver.requestedImageExt("save a jpg of the eiffel tower"));
        assertEquals(".svg", DownloadLinkResolver.requestedImageExt("get the logo as svg"));
        assertNull(DownloadLinkResolver.requestedImageExt("download a wallpaper"));
        assertNull(DownloadLinkResolver.requestedImageExt(null));
    }

    @Test
    public void isImageReadsThePathNotTheQuery() {
        assertTrue(DownloadLinkResolver.isImage("https://cdn.example/icons/home.png"));
        assertTrue(DownloadLinkResolver.isImage("https://cdn.example/a/b.webp?w=300&h=300"));
        assertFalse(DownloadLinkResolver.isImage("https://example.com/gallery.html"));
        assertFalse(DownloadLinkResolver.isImage("https://example.com/photo-viewer?img=1.png"));
        assertFalse(DownloadLinkResolver.isImage(null));
    }

    @Test
    public void resolveImagePrefersTheAskedExtensionOverOtherImages() {
        List<String> links = Arrays.asList(
                "https://pngtree.example/assets/banner.jpg",
                "https://pngtree.example/icons/home-icon.png",
                "https://pngtree.example/download/pack.zip");
        assertEquals("https://pngtree.example/icons/home-icon.png",
                DownloadLinkResolver.resolveImage(links, null, "png icon", ".png"));
    }

    @Test
    public void resolveImagePrefersAnyImageOverANonImageFile() {
        List<String> links = Arrays.asList(
                "https://cdn.example/files/catalogue.pdf",
                "https://cdn.example/photos/alps.jpg");
        assertEquals("https://cdn.example/photos/alps.jpg",
                DownloadLinkResolver.resolveImage(links, null, "wallpaper alps", null));
    }

    @Test
    public void resolveImageStillHonoursTheNamedHost() {
        List<String> links = Arrays.asList(
                "https://elsewhere.example/nice.png",
                "https://pngtree.example/ok.png");
        assertEquals("https://pngtree.example/ok.png",
                DownloadLinkResolver.resolveImage(links, "pngtree.example", null, ".png"));
    }

    @Test
    public void resolveImageFindsNothingInAnEmptyHarvest() {
        assertNull(DownloadLinkResolver.resolveImage(Arrays.asList(), null, "png icon", ".png"));
        assertNull(DownloadLinkResolver.resolveImage(null, null, "png icon", ".png"));
    }
}
