package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClaimKindTest {

    @Test
    public void bestIsOpinion() {
        assertEquals(ClaimKind.Kind.OPINION,
                ClaimKind.classify("Movie X is the best Marvel film of 2026."));
    }

    @Test
    public void appearsIsInference() {
        assertEquals(ClaimKind.Kind.INFERENCE,
                ClaimKind.classify("It appears to have the strongest reviews."));
    }

    @Test
    public void aPlainStatementIsFact() {
        assertEquals(ClaimKind.Kind.FACT,
                ClaimKind.classify("The film opened on 12 March 2026 [1]."));
    }

    @Test
    public void mixedAnswerGetsALegend() {
        String note = ClaimKind.note(
                "The film opened in March [1]. Reviewers ranked it best. It appears worth watching.");
        assertTrue(note, note.toLowerCase().contains("opinion"));
        assertTrue(note, note.toLowerCase().contains("inference"));
    }
}
