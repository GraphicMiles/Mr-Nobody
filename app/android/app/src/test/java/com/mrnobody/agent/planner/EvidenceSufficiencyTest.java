package com.mrnobody.agent.planner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Rule 1: the read loop stops the moment two distinct sources each contribute
 * two question-matching prose sentences. The judgment reuses the exact
 * scoring and gates the answer composer uses, so "sufficient" here always
 * means "citable" there.
 */
public class EvidenceSufficiencyTest {

    private static final String QUESTION = "eiffel tower construction history";

    private static String source(int n, String url, String body) {
        return "[" + n + "] Source " + n + "\n" + url + "\n" + body + "\n";
    }

    private static final String BODY_A =
            "The Eiffel Tower construction started in 1887 on the Champ de Mars in Paris. "
            + "The tower construction finished in 1889 as the entrance arch to the fair. "
            + "Tickets can be purchased online for visitors arriving after nine.";

    private static final String BODY_B =
            "Gustave Eiffel led the tower construction with a team of three hundred workers. "
            + "The history of the Eiffel Tower includes protests from artists during construction. "
            + "The restaurant on the second floor serves lunch daily.";

    @Test
    public void twoRichSourcesAreEnough() {
        String sources = source(1, "https://a.example/eiffel", BODY_A)
                + source(2, "https://b.example/tower", BODY_B);
        assertTrue(EvidenceSufficiency.enough(QUESTION, sources));
    }

    @Test
    public void oneSourceIsNeverEnough() {
        String sources = source(1, "https://a.example/eiffel", BODY_A);
        assertFalse(EvidenceSufficiency.enough(QUESTION, sources));
    }

    @Test
    public void aThinSecondSourceIsNotEnough() {
        // Source 2 has only one sentence that matches the question.
        String thin = "The Eiffel Tower construction is described in this history article. "
                + "Contact us for advertising rates and press enquiries today.";
        String sources = source(1, "https://a.example/eiffel", BODY_A)
                + source(2, "https://b.example/tower", thin);
        assertFalse(EvidenceSufficiency.enough(QUESTION, sources));
    }

    @Test
    public void aMirrorOfTheFirstSourceDoesNotCountTwice() {
        // Same body under two URLs: dedupeBodies collapses it, one source left.
        String sources = source(1, "https://a.example/eiffel", BODY_A)
                + source(2, "https://amp.a.example/eiffel", BODY_A);
        assertFalse(EvidenceSufficiency.enough(QUESTION, sources));
    }

    @Test
    public void boilerplateSentencesNeverCountAsEvidence() {
        String furniture =
                "Please enable JavaScript or switch to a supported browser to continue. "
                + "We use cookies to improve your experience of the Eiffel Tower site. "
                + "Accept all cookies to continue reading about tower construction history.";
        String sources = source(1, "https://a.example/eiffel", BODY_A)
                + source(2, "https://b.example/wall", furniture);
        assertFalse(EvidenceSufficiency.enough(QUESTION, sources));
    }

    @Test
    public void emptyInputsAreNeverSufficient() {
        assertFalse(EvidenceSufficiency.enough(QUESTION, ""));
        assertFalse(EvidenceSufficiency.enough(QUESTION, null));
        assertFalse(EvidenceSufficiency.enough(null, "anything"));
        assertFalse(EvidenceSufficiency.enough("", "anything"));
    }
}
