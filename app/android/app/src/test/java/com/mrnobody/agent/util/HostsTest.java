package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

/**
 * Host detection in prose.
 *
 * <p>The case that produced this class: "search for reacher season 4 episode
 * from nkiri.ink and download it". Nothing in the pipeline saw a site there —
 * the planner wanted a scheme, and the verifier's suffix list stopped at nine
 * entries and did not include {@code .ink}.
 *
 * <p>The false-positive tests matter as much as the detection ones. This runs
 * over model output, and marking a correct answer as citing "Reacher.S04E01
 * .1080p.mkv" would train the reader to ignore the warning.
 */
public class HostsTest {

    // ------------------------------------------------------------ the report

    @Test
    public void theInstructionThatWasIgnored() {
        assertEquals("nkiri.ink",
                Hosts.firstIn("search for reacher season 4 episode from nkiri.ink and download it"));
    }

    @Test
    public void theHostTheAnswerNamedIsFound() {
        Set<String> hosts = Hosts.findIn(
                "none of them mention the site nkiri.ink or provide any link");
        assertTrue(hosts.toString(), hosts.contains("nkiri.ink"));
    }

    // -------------------------------------------------------- bare hostnames

    @Test
    public void bareHostnamesAreFoundWithoutAScheme() {
        assertEquals("example.com", Hosts.firstIn("go to example.com now"));
        assertEquals("bbc.co.uk", Hosts.firstIn("as bbc.co.uk reported"));
        assertEquals("guardian.ng", Hosts.firstIn("guardian.ng says otherwise"));
    }

    @Test
    public void suffixesBeyondTheOldNineAreRecognised() {
        // Every one of these was invisible to the previous matcher.
        for (String host : new String[]{
                "evil.xyz", "evil.to", "evil.ru", "evil.app", "evil.dev",
                "site.stream", "site.download", "site.icu", "site.ink"}) {
            assertTrue(host + " should be recognised",
                    Hosts.findIn("see " + host + " for more").contains(host));
        }
    }

    @Test
    public void wwwAndCaseAreNormalised() {
        assertEquals("example.com", Hosts.firstIn("WWW.Example.COM"));
    }

    // --------------------------------------------------------------- schemes

    @Test
    public void fullUrlsAreReducedToTheirHost() {
        assertEquals("example.com", Hosts.firstIn("https://example.com/a/b?c=d#e"));
        assertEquals("example.com", Hosts.firstIn("http://user@example.com:8080/x"));
    }

    @Test
    public void anExplicitSchemeIsTrustedEvenWithAnOddSuffix() {
        // A scheme is the author asserting it is a URL; we do not second-guess
        // the suffix. A bare token still has to earn it.
        assertEquals("thing.internal", Hosts.firstIn("https://thing.internal/path"));
        assertNull(Hosts.firstIn("thing.internal"));
    }

    // -------------------------------------------------------- false positives

    @Test
    public void releaseNamesAreNotHosts() {
        assertNull(Hosts.firstIn("Reacher.S04E01.1080p.WEB-DL.mkv"));
        assertNull(Hosts.firstIn("The.Movie.2024.720p.mp4"));
    }

    @Test
    public void filenamesAndPathsAreNotHosts() {
        assertNull(Hosts.firstIn("open config.json"));
        assertNull(Hosts.firstIn("see MrNobodyWebView.java"));
        assertNull(Hosts.firstIn("read docs/spec/V2_SPEC.md"));
        assertNull(Hosts.firstIn("blocklist.txt was compiled"));
    }

    @Test
    public void proseAndNumbersAreNotHosts() {
        assertNull(Hosts.firstIn("Season 4.Episode 2"));
        assertNull(Hosts.firstIn("version 3.14 shipped"));
        assertNull(Hosts.firstIn("e.g. this one"));
        assertNull(Hosts.firstIn("Wait. Then go."));
    }

    @Test
    public void emptyAndNullAreSafe() {
        assertTrue(Hosts.findIn(null).isEmpty());
        assertTrue(Hosts.findIn("").isEmpty());
        assertNull(Hosts.firstIn(""));
    }

    // ------------------------------------------------------------- behaviour

    @Test
    public void hostsAreReturnedOnceInOrderOfAppearance() {
        Set<String> hosts = Hosts.findIn("first a.com then b.org then a.com again");
        assertEquals("[a.com, b.org]", hosts.toString());
    }

    @Test
    public void trailingPunctuationIsNotPartOfTheHost() {
        assertEquals("example.com", Hosts.firstIn("try example.com."));
        assertTrue(Hosts.findIn("(see example.com)").contains("example.com"));
        assertFalse(Hosts.findIn("try example.com.").contains("example.com."));
    }
}
