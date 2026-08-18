package com.mrnobody.agent.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class ModelRouterTest {

    @Test
    public void flashAndMiniAreCheap() {
        assertTrue(ModelRouter.isCheap("gemini-2.0-flash"));
        assertTrue(ModelRouter.isCheap("gpt-4o-mini"));
        assertFalse(ModelRouter.isCheap("gemini-2.0-pro"));
        assertFalse(ModelRouter.isCheap("llama-3.3-70b-versatile"));
        assertFalse(ModelRouter.isCheap("whisper-large"));
    }

    @Test
    public void pickCheapPrefersAFlashSibling() {
        assertEquals("gemini-2.0-flash",
                ModelRouter.pickCheap(
                        Arrays.asList("gemini-2.0-pro", "gemini-2.0-flash", "whisper-1"),
                        "gemini-2.0-pro"));
    }

    @Test
    public void pickCheapKeepsTheCurrentWhenNothingIsCheaper() {
        assertEquals("llama-3.3-70b-versatile",
                ModelRouter.pickCheap(
                        Arrays.asList("llama-3.3-70b-versatile"),
                        "llama-3.3-70b-versatile"));
    }
}
