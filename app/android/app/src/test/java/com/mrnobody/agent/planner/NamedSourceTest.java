package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Named targets are structure: a URL, a host, an @handle. Not a vocabulary
 * of site names.
 */
public class NamedSourceTest {

    @Test
    public void aBareHostIsFetchable() {
        NamedSource src = NamedSource.extract(
                "pull Infinity War off nkiri.ink please");
        assertEquals("https://nkiri.ink", src.fetchUrl);
        assertEquals("nkiri.ink", src.written);
    }

    @Test
    public void aTypedUrlIsUsedAsWritten() {
        assertEquals("https://nkiri.ink/reacher-s04e01/",
                NamedSource.fetchUrlIn("get https://nkiri.ink/reacher-s04e01/ for me"));
    }

    @Test
    public void aTypedUrlWinsOverALaterBareHost() {
        assertEquals("https://example.com/page",
                NamedSource.fetchUrlIn("open https://example.com/page not other.com"));
    }

    @Test
    public void anAtHandleBecomesAFetchableProfile() {
        NamedSource src = NamedSource.extract(
                "get me whatever @StudioHub put up on their page");
        assertEquals("https://x.com/StudioHub", src.fetchUrl);
        assertEquals("@StudioHub", src.written);
    }

    @Test
    public void anEmailIsNotAHandle() {
        NamedSource src = NamedSource.extract("write to editors@example.com about it");
        // A host in an email may still be a host. The @local-part must not
        // become an X profile.
        if (src != null) {
            assertEquals("https://example.com", src.fetchUrl);
        }
        NamedSource handle = NamedSource.extract("ping @StudioHub later");
        assertEquals("@StudioHub", handle.written);
    }

    @Test
    public void aFilenameIsNotASite() {
        assertNull(NamedSource.extract("download Reacher.S04E01.1080p.mkv"));
        assertNull(NamedSource.extract("summarize the news today"));
    }

    @Test
    public void findUrlDelegatesHere() {
        assertEquals("https://nkiri.ink",
                DeterministicEngine.findUrl(
                        "search for reacher season 4 episode from nkiri.ink and download it"));
        assertEquals("https://x.com/Marvel",
                DeterministicEngine.findUrl("anything new from @Marvel"));
    }
}
