package com.mrnobody.agent.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Ordering a provider's catalogue. The real Groq listing that produced the
 * reported 404 is the fixture: thirteen models, of which most cannot hold a
 * conversation, and none of them the one the app had hardcoded.
 */
public class ModelCatalogTest {

    /** Groq's catalogue as returned in August 2026. */
    private static final List<String> GROQ = Arrays.asList(
            "allam-2-7b",
            "canopylabs/orpheus-arabic-saudi",
            "canopylabs/orpheus-v1-english",
            "groq/compound",
            "groq/compound-mini",
            "meta-llama/llama-prompt-guard-2-22m",
            "meta-llama/llama-prompt-guard-2-86m",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "openai/gpt-oss-safeguard-20b",
            "qwen/qwen3.6-27b",
            "whisper-large-v3",
            "whisper-large-v3-turbo");

    @Test
    public void theModelWeUsedToHardcodeIsNotEvenInTheList() {
        // Why nothing ships a default model any more.
        assertFalse(GROQ.contains("llama-3.3-70b-versatile"));
    }

    @Test
    public void chatModelsComeFirst() {
        List<String> ordered = ModelCatalog.ordered(GROQ);
        assertEquals("allam-2-7b", ordered.get(0));
        assertTrue(ordered.indexOf("openai/gpt-oss-120b") < ordered.indexOf("whisper-large-v3"));
        assertTrue(ordered.indexOf("qwen/qwen3.6-27b")
                < ordered.indexOf("meta-llama/llama-prompt-guard-2-22m"));
    }

    @Test
    public void nothingIsHiddenFromTheUser() {
        assertEquals(GROQ.size(), ModelCatalog.ordered(GROQ).size());
    }

    @Test
    public void speechGuardAndEmbeddingModelsAreNotOfferedFirst() {
        for (String notChat : new String[]{
                "whisper-large-v3", "canopylabs/orpheus-v1-english",
                "meta-llama/llama-prompt-guard-2-22m", "openai/gpt-oss-safeguard-20b",
                "text-embedding-3-large", "playai-tts", "stable-diffusion-xl"}) {
            assertFalse(notChat, ModelCatalog.looksLikeChatModel(notChat));
        }
        for (String chat : new String[]{
                "openai/gpt-oss-120b", "qwen/qwen3.6-27b", "groq/compound",
                "gemini-2.5-pro", "claude-sonnet-4", "llama-4-scout-17b"}) {
            assertTrue(chat, ModelCatalog.looksLikeChatModel(chat));
        }
    }

    @Test
    public void chatCountDescribesTheList() {
        // 6 of Groq's 13 can chat: allam, compound, compound-mini, gpt-oss-120b,
        // gpt-oss-20b, qwen3.6-27b.
        assertEquals(6, ModelCatalog.chatCount(GROQ));
    }

    @Test
    public void geminiNamesAreTrimmedToTheirId() {
        assertEquals("gemini-2.0-flash", ModelCatalog.stripPrefix("models/gemini-2.0-flash"));
        assertEquals("gemini-2.0-flash", ModelCatalog.stripPrefix("gemini-2.0-flash"));
        assertEquals("", ModelCatalog.stripPrefix(null));
    }

    @Test
    public void anEmptyOrRaggedListDoesNotThrow() {
        assertTrue(ModelCatalog.ordered(null).isEmpty());
        assertEquals(1, ModelCatalog.ordered(Arrays.asList("", "  ", "gpt-4o")).size());
    }
}
