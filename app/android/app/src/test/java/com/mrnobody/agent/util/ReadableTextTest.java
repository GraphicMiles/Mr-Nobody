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
}
