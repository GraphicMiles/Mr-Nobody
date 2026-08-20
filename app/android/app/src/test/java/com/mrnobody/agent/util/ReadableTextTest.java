package com.mrnobody.agent.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReadableTextTest {

    @Test
    public void youtubeConfigurationIsNotEvidence() {
        String dump = "(function() { window.ytplayer={}; ytcfg.set({\"CLIENT_CANARY_STATE\":"
                + "\"none\",\"EXPERIMENT_FLAGS\":{\"ab_det_apm\":true,"
                + "\"allow_skip_networkless\":true}}); })();";
        assertFalse(ReadableText.usable(dump));
        assertFalse(ReadableText.proseSentence(dump));
    }

    @Test
    public void ordinaryArticleProseRemainsUsable() {
        String prose = "Bitcoin was introduced in a 2008 white paper published under the "
                + "name Satoshi Nakamoto. The network began operating in January 2009.";
        assertTrue(ReadableText.usable(prose));
        assertTrue(ReadableText.proseSentence(prose));
    }

    @Test
    public void escapedConfigurationDumpIsRejected() {
        String dump = "\\u003dDESKTOP \\u003dSTATE \\u003dTRUE \\u003dVALUE "
                + "\\u003dITEM \\u003dCONFIG \\u003dFLAG \\u003dOTHER \\u003dLAST";
        assertFalse(ReadableText.usable(dump));
    }

    @Test
    public void antiBotAndConsentBoilerplateIsNotEvidence() {
        assertFalse(ReadableText.proseSentence(
                "Please enable JavaScript or switch to a supported browser to keep watching."));
        assertFalse(ReadableText.proseSentence(
                "We use cookies to improve your experience and analyse traffic on our site."));
        assertFalse(ReadableText.proseSentence(
                "Click Accept all cookies to agree to the use of cookies for analytics."));
        assertFalse(ReadableText.proseSentence(
                "Subscribe to our newsletter for the latest updates delivered to your inbox."));
    }

    @Test
    public void tableOfContentsNumberRunsAreNotEvidence() {
        assertFalse(ReadableText.proseSentence(
                "Table of contents 1 Introduction 2 Common infrastructure "
                        + "3 Semantics 4 The elements of HTML 5 Microdata"));
        assertFalse(ReadableText.proseSentence(
                "1 Introduction 2 Infrastructure 3 Semantics 4 Elements "
                        + "5 Microdata 6 User interaction 7 Loading"));
    }

    @Test
    public void proseMentioningNumbersOrCookiesInPassingSurvives() {
        assertTrue(ReadableText.proseSentence(
                "The recipe calls for 2 eggs, 3 cups of flour and 1 spoon of sugar mixed well."));
        assertTrue(ReadableText.proseSentence(
                "The bakery sells fresh cookies every morning from its stall in the market."));
    }

    @Test
    public void thePreviewAnnotationIsNeverQuotedAsEvidence() {
        assertFalse(ReadableText.proseSentence(
                "[... 1234 characters omitted. The full output was NOT retained and "
                        + "cannot be retrieved from this preview.]"));
    }
}
