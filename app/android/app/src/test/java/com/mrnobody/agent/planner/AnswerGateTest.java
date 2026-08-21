package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Verification is blocking: one corrective re-ask, then the honest fallback. */
public class AnswerGateTest {

    @Test
    public void aCleanAnswerPasses() {
        assertEquals(AnswerGate.Action.PASS, AnswerGate.decide(false, 0));
        assertEquals(AnswerGate.Action.PASS, AnswerGate.decide(false, 1));
    }

    @Test
    public void theFirstFailureBuysExactlyOneRewrite() {
        assertEquals(AnswerGate.Action.RETRY, AnswerGate.decide(true, 0));
        assertEquals(AnswerGate.Action.FALLBACK, AnswerGate.decide(true, 1));
        assertEquals(AnswerGate.Action.FALLBACK, AnswerGate.decide(true, 5));
        assertEquals(1, AnswerGate.MAX_RETRIES);
    }

    @Test
    public void theCorrectionQuotesTheFindingsAtTheModel() {
        String c = AnswerGate.correction("2 citation problem(s)", "1 unsupported figure");
        assertTrue(c.contains("failed verification"));
        assertTrue(c.contains("2 citation problem(s)"));
        assertTrue(c.contains("1 unsupported figure"));
        assertTrue("the rewrite rule is explicit", c.contains("verbatim"));
    }

    @Test
    public void emptyFindingsProduceNoEmptyBullets() {
        String c = AnswerGate.correction("", null);
        assertTrue(!c.contains("- \n") && !c.contains("-  "));
    }

    @Test
    public void theFallbackNoteSaysWhatHappenedAndWhatTheTextNowIs() {
        String n = AnswerGate.fallbackNote();
        assertTrue(n.contains("could not be verified"));
        assertTrue(n.contains("discarded"));
        assertTrue(n.contains("extracted directly"));
    }
}
