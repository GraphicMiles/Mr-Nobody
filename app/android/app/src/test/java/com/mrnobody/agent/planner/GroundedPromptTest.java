package com.mrnobody.agent.planner;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GroundedPromptTest {

    @Test
    public void asksForAHeadingAndBoldFactsNotAWallOfProse() {
        String prompt = GroundedPrompt.build("find the latest Prime series", "[1] x", true);
        assertTrue(prompt.contains("# "));
        assertTrue(prompt.contains("**double asterisks**"));
        assertTrue(prompt.contains("short paragraphs"));
        assertTrue(prompt.contains("Do not paste raw URLs"));
    }
}
