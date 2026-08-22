package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The intent classifier is the thing that decides whether a question wants a
 * figure, an identity, a definition, an explanation or a comparison. It must
 * recognise the <em>shape</em> of a question — not just hit a cue-word list —
 * so a phrasing that never appears in a keyword list is still classified
 * correctly. These cases are the ones the old cue-list-only classifier got
 * wrong or would have missed.
 */
public class AnswerIntentTest {

    // ------------------------------------------------------------ classify

    @Test
    public void figuresRecognizedByShapeNotJustCueWords() {
        assertEquals("how much is a tesla model 3",
                AnswerIntent.FIGURE, AnswerIntent.classify("how much is a tesla model 3"));
        assertEquals("what does the iphone cost",
                AnswerIntent.FIGURE, AnswerIntent.classify("what does the iphone cost"));
        assertEquals("height of the eiffel tower",
                AnswerIntent.FIGURE, AnswerIntent.classify("height of the eiffel tower"));
        assertEquals("what is bitcoin's price",
                AnswerIntent.FIGURE, AnswerIntent.classify("what is bitcoin s price"));
        assertEquals("population of india",
                AnswerIntent.FIGURE, AnswerIntent.classify("population of india"));
    }

    @Test
    public void personsRecognizedByCopulaNotJustWhoWord() {
        assertEquals(AnswerIntent.PERSON, AnswerIntent.classify("who is she"));
        assertEquals(AnswerIntent.PERSON, AnswerIntent.classify("who was the first president"));
        assertEquals(AnswerIntent.PERSON, AnswerIntent.classify(
                "what is mrbeast known for"));
        assertEquals(AnswerIntent.PERSON, AnswerIntent.classify(
                "what is the real name of drake"));
    }

    @Test
    public void definitionsAndExplanationsAreDistinguished() {
        assertEquals(AnswerIntent.DEFINITION, AnswerIntent.classify("what is a cloud"));
        assertEquals(AnswerIntent.DEFINITION, AnswerIntent.classify(
                "what does html mean"));
        assertEquals(AnswerIntent.EXPLAIN, AnswerIntent.classify(
                "why is the sky blue"));
        assertEquals(AnswerIntent.EXPLAIN, AnswerIntent.classify(
                "how does a car engine work"));
    }

    @Test
    public void comparisonsAreRecognizedAcrossPhrasings() {
        assertEquals(AnswerIntent.COMPARE, AnswerIntent.classify(
                "which is heavier gold or iron"));
        assertEquals(AnswerIntent.COMPARE, AnswerIntent.classify(
                "difference between java and python"));
        assertEquals(AnswerIntent.COMPARE, AnswerIntent.classify(
                "is iphone better than samsung"));
    }

    @Test
    public void figureBeatsDefinitionForWhatsXPrice() {
        assertEquals("number questions are figures, not definitions",
                AnswerIntent.FIGURE,
                AnswerIntent.classify("what is the bitcoin price"));
        assertEquals(AnswerIntent.FIGURE,
                AnswerIntent.classify("what is the current inflation rate"));
    }

    // ------------------------------------------------------------ evidence

    @Test
    public void anIdentifierNumberIsNotAFigure() {
        // "SHA-256" must not satisfy a price query.
        assertEquals(0.2, AnswerIntent.FIGURE.evidence(
                "Bitcoin is secured with the SHA-256 algorithm."), 0.001);
        assertEquals(1.0, AnswerIntent.FIGURE.evidence(
                "Bitcoin traded at 64000 dollars on Tuesday."), 0.001);
    }

    @Test
    public void aMetadataDumpIsNotAPersonIdentity() {
        // A comma-separated label list has no verb and no copula identity.
        assertEquals(0.2, AnswerIntent.PERSON.evidence(
                "MrBeast Biography, Age, Girlfriend, Family, Career, Net Worth."), 0.001);
        assertTrue(AnswerIntent.PERSON.evidence(
                "MrBeast is the byname of Jimmy Donaldson, an American YouTuber.")
                >= 1.0);
    }

    @Test
    public void anExplanationNeedsACausalLink() {
        assertTrue(AnswerIntent.EXPLAIN.evidence(
                "Blue light is scattered more strongly than red light, so the sky appears blue.")
                >= 1.0);
        assertEquals(0.0, AnswerIntent.EXPLAIN.evidence(
                "The sky is blue today."), 0.001);
    }
}
