package com.mrnobody.agent.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The SSE frame reader and the per-provider delta extraction it feeds.
 *
 * <p>These are the parts of streaming that can be tested without a network:
 * the wire format parsing and the JSON-to-text mapping. A regression here is a
 * stream that silently drops every token, which is the worst kind of streaming
 * bug — the UI looks live while nothing arrives.
 */
public class SseFramesTest {

    /** Read a canned SSE body and collect every payload in order. */
    private static List<String> frames(String body) throws IOException {
        List<String> out = new ArrayList<>();
        SseFrames.read(new StringReader(body), out::add);
        return out;
    }

    @Test
    public void doneStopsTheStreamBeforeLateFrames() throws IOException {
        List<String> f = frames(
                "data: {\"a\":1}\n\n"
                + "data: {\"a\":2}\n\n"
                + "data: [DONE]\n\n"
                + "data: {\"a\":3}\n");
        assertEquals(List.of("{\"a\":1}", "{\"a\":2}"), f);
    }

    @Test
    public void doneMarkerIsExposedToCallers() throws IOException {
        assertTrue(SseFrames.read(new StringReader("data: [DONE]\n\n"), json -> { }));
        assertFalse(SseFrames.read(new StringReader("data: {\"a\":1}\n\n"), json -> { }));
    }

    @Test
    public void nonDataLinesAndKeepAlivesAreIgnored() throws IOException {
        List<String> f = frames(
                ": keep-alive\n\n"
                + "event: message\n"
                + "data: {\"a\":1}\n\n"
                + "data:\n\n"
                + "data:   \n\n"
                + "data: {\"a\":2}\n");
        assertEquals(List.of("{\"a\":1}", "{\"a\":2}"), f);
    }

    @Test
    public void anEmptyBodyEmitsNothing() throws IOException {
        assertEquals(List.of(), frames(""));
    }

    @Test
    public void openAiDeltaContentIsExtracted() {
        assertEquals("the answer",
                OpenAiCompatibleProvider.deltaContent(
                        "{\"choices\":[{\"delta\":{\"content\":\"the answer\"}}]}"));
    }

    @Test
    public void openAiRoleOnlyAndUsageFramesYieldNothing() {
        // A role-only frame and a usage frame both carry no text.
        assertEquals("", OpenAiCompatibleProvider.deltaContent(
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"));
        assertEquals("", OpenAiCompatibleProvider.deltaContent(
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":1}}"));
    }

    @Test
    public void openAiMalformedFrameYieldsNothing() {
        assertEquals("", OpenAiCompatibleProvider.deltaContent("not json"));
        assertEquals("", OpenAiCompatibleProvider.deltaContent(""));
        assertEquals("", OpenAiCompatibleProvider.deltaContent("{}"));
    }

    @Test
    public void geminiCandidateTextIsExtracted() {
        assertEquals("the answer",
                GeminiProvider.candidateText(
                        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"the answer\"}]}}]}"));
    }

    @Test
    public void geminiEmptyOrMalformedFrameYieldsNothing() {
        assertEquals("", GeminiProvider.candidateText(
                "{\"candidates\":[{\"content\":{\"parts\":[]}}]}"));
        assertEquals("", GeminiProvider.candidateText("not json"));
        assertEquals("", GeminiProvider.candidateText("{}"));
    }
}
