package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The transport-noise cleaner: strips HTML, decodes entities, flattens
 * markdown links and drops JSON metadata before text can be cited as evidence.
 */
public class SanitizeTest {

    @Test
    public void decodesSingleAndDoubleEncodedEntities() {
        assertEquals("Politics, Law & Entrepreneurs",
                Sanitize.prose("Politics, Law &amp; Entrepreneurs"));
        // &lt;c&gt; decodes to a literal <c> and is kept as text, not re-stripped.
        assertEquals("a & b <c> 'd'",
                Sanitize.prose("a &amp;amp; b &lt;c&gt; &#39;d&apos;"));
    }

    @Test
    public void stripsHtmlTagsIncludingNested() {
        // Punctuation is genuine page text and is preserved; only markup dies.
        assertEquals("Who is MrBeast?. Is MrBeast married?.",
                Sanitize.prose("<div> Who is MrBeast?. </div><em></em><div> Is MrBeast married?."));
    }

    @Test
    public void stripsTagsWithAttributesAndEscapedSlashes() {
        assertEquals("at Leader Biography.",
                Sanitize.prose("at <a href=\"https:\\/\\/leaderbiography.com\\/\">Leader Biography<\\/a>."));
    }

    @Test
    public void flattensMarkdownLinks() {
        assertEquals("Bitcoin is secured with the SHA-256 algorithm, which belongs to the SHA-2 family.",
                Sanitize.prose("Bitcoin is secured with the [SHA-256 algorithm](coinmarketcap.com ), "
                        + "which belongs to the SHA-2 family."));
    }

    @Test
    public void flattensMarkdownImageAndEmphasis() {
        assertEquals("alt text and bold text.",
                Sanitize.prose("![alt text](https://x/y.png) and **bold text**."));
    }

    @Test
    public void dropsBareParenthesisedHosts() {
        // A bare "(domain.tld)" reference is dropped; "(BCH)" is a real
        // parenthetical (no dot) and stays.
        assertEquals("Bitcoin Cash (BCH).",
                Sanitize.prose("Bitcoin Cash (BCH)(coinmarketcap.com )."));
    }

    @Test
    public void dropsJsonMetadataSnippets() {
        String out = Sanitize.prose("displayType: standard article");
        assertFalse(out.contains("displayType"));
    }

    @Test
    public void nullAndEmptySafe() {
        assertEquals("", Sanitize.prose(null));
        assertEquals("", Sanitize.prose(""));
    }

    @Test
    public void keepsNormalProseIntact() {
        String prose = "The history of the tower and its construction is a long"
                + " story that covers the 1889 world fair opening.";
        assertEquals(prose, Sanitize.prose(prose));
    }

    @Test
    public void realWorldScreenshotLeaksAreCleaned() {
        // The exact strings that were quoted verbatim in the on-device answer
        // (the screenshots that prompted this work).
        String nav = "Politics, Law &amp; Entrepreneurs Government Businesspeople"
                + " &amp; Entrepreneurs <div> Who is MrBeast?. </div><em></em><div>"
                + " Is MrBeast married?.";
        String cleanedNav = Sanitize.prose(nav);
        assertFalse(cleanedNav, cleanedNav.contains("<div>"));
        assertFalse(cleanedNav, cleanedNav.contains("</em>"));
        assertFalse(cleanedNav, cleanedNav.contains("&amp;"));

        String meta = "displayType: standard article MrBeast, aka Jimmy Donaldson,"
                + " runs the largest YouTube channel in the world.";
        String cleanedMeta = Sanitize.prose(meta);
        assertFalse(cleanedMeta, cleanedMeta.toLowerCase().contains("displaytype"));
        assertTrue(cleanedMeta, cleanedMeta.contains("runs the largest YouTube channel"));

        String markdown = "Bitcoin is secured with the [SHA-256 algorithm]"
                + "(coinmarketcap.com ), which belongs to the SHA-2 family of"
                + " hashing algorithms, which is also used by its fork Bitcoin Cash"
                + " (BCH)(coinmarketcap.com ), as well as several other cryptocurrencies.";
        String cleanedMd = Sanitize.prose(markdown);
        assertFalse(cleanedMd, cleanedMd.contains("[SHA-256"));
        assertFalse(cleanedMd, cleanedMd.contains("](coinmarketcap"));
        assertTrue(cleanedMd, cleanedMd.contains("SHA-256 algorithm"));
        assertTrue(cleanedMd, cleanedMd.contains("Bitcoin Cash"));
    }

    @Test
    public void doesNotMangleImportantContent() {
        // A percent/degree/registered symbol and a legitimately parenthesised
        // price must survive.
        String out = Sanitize.prose("The rate is 7.2% ± 0.1 © and (USD 200).");
        assertTrue(out, out.contains("7.2%"));
        assertTrue(out, out.contains("©"));
    }
}
