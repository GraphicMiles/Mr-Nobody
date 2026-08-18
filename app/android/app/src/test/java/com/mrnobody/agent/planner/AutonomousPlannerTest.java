package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.ai.AiProvider;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The observe → reason → act loop. A scripted provider walks a multi-step
 * scenario (search → read → done) and each turn's prompt must carry the
 * previous step's result — that feedback loop is the whole point of autonomy.
 */
public class AutonomousPlannerTest {

    private static final Set<String> TOOLS = new LinkedHashSet<>(
            Arrays.asList("search", "http", "download", "browser", "terminal"));

    /** Returns a canned response per turn, and records every user prompt. */
    private static final class ScriptedProvider implements AiProvider {
        final List<String> prompts = new ArrayList<>();
        final List<String> script;
        final AtomicInteger turn = new AtomicInteger();

        ScriptedProvider(String... script) {
            this.script = Arrays.asList(script);
        }

        @Override public String id() { return "scripted"; }
        @Override public String displayName() { return "Scripted"; }
        @Override public boolean isRemote() { return true; }

        @Override
        public void complete(String system, String user, CompletionCallback cb) {
            prompts.add(user);
            int t = turn.getAndIncrement();
            if (t >= script.size()) {
                cb.onResult("{\"done\":true}");
            } else {
                cb.onResult(script.get(t));
            }
        }
    }

    @Test
    public void walksSearchThenReadThenDone() {
        ScriptedProvider p = new ScriptedProvider(
                "{\"tool\":\"search\",\"args\":{\"q\":\"laptops\"}}",
                "{\"tool\":\"http\",\"args\":{\"url\":\"https://example.com/laptops\"}}");
        AutonomousPlanner planner = new AutonomousPlanner(p, "NONCE");

        List<String> transcript = new ArrayList<>();
        Plan.Step s1 = planner.nextStep("find laptops", transcript, TOOLS);
        assertNotNull(s1);
        assertEquals("search", s1.tool);
        assertEquals("laptops", s1.request.param("q"));

        // The engine feeds the result back before asking again.
        transcript.add("called search {q=laptops} → three laptops found");
        Plan.Step s2 = planner.nextStep("find laptops", transcript, TOOLS);
        assertNotNull(s2);
        assertEquals("http", s2.tool);
        assertEquals("https://example.com/laptops", s2.request.param("url"));

        transcript.add("called http {url=https://example.com/laptops} → page text");
        Plan.Step s3 = planner.nextStep("find laptops", transcript, TOOLS);
        assertNull("the model signals done", s3);
    }

    @Test
    public void thePromptCarriesTheTranscriptSoTheModelObserves() {
        ScriptedProvider p = new ScriptedProvider("{\"done\":true}");
        AutonomousPlanner planner = new AutonomousPlanner(p, "NONCE");

        List<String> transcript = new ArrayList<>();
        transcript.add("called search {q=laptops} → three laptops found");
        planner.nextStep("find laptops", transcript, TOOLS);

        // The first prompt (turn 0) contains the previous result — the model
        // is shown what happened, not just asked again.
        assertTrue(p.prompts.get(0).contains("three laptops found"));
        assertTrue(p.prompts.get(0).contains("called search"));
    }

    @Test
    public void aProviderErrorStops() {
        ScriptedProvider p = new ScriptedProvider(); // empty script → but we override below
        // A provider that errors: build one directly.
        AiProvider erroring = new AiProvider() {
            @Override public String id() { return "err"; }
            @Override public String displayName() { return "Err"; }
            @Override public boolean isRemote() { return true; }
            @Override
            public void complete(String s, String u, CompletionCallback cb) { cb.onError("boom"); }
        };
        AutonomousPlanner planner = new AutonomousPlanner(erroring, "NONCE");
        assertNull("an error reads as stop, never a loop", planner.nextStep("x", new ArrayList<>(), TOOLS));
    }

    @Test
    public void stepsContinueUntilTheModelSaysDone() {
        // The model proposes steps for a while, then finishes. The planner
        // returns a step per call until "done"; the step ceiling that bounds a
        // misbehaving model lives in the engine loop, not here.
        ScriptedProvider p = new ScriptedProvider(
                "{\"tool\":\"search\",\"args\":{\"q\":\"x\"}}",
                "{\"tool\":\"search\",\"args\":{\"q\":\"y\"}}",
                "{\"done\":true}");
        AutonomousPlanner planner = new AutonomousPlanner(p, "NONCE");

        assertNotNull(planner.nextStep("x", new ArrayList<>(), TOOLS));
        assertNotNull(planner.nextStep("x", new ArrayList<>(), TOOLS));
        assertNull("done ends the loop", planner.nextStep("x", new ArrayList<>(), TOOLS));
    }
}
