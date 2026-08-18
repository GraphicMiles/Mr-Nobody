package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.core.Task;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The model-backed planner. The provider is a fake that returns canned JSON,
 * so the parse/validate/fall-back/replan behaviour is pinned without a network
 * or a model.
 */
public class LlmPlannerTest {

    private static final Set<String> TOOLS = new LinkedHashSet<>(
            Arrays.asList("search", "http", "download", "browser", "terminal"));

    /** A provider that returns a fixed response and counts its asks. */
    private static final class FakeProvider implements AiProvider {
        final AtomicInteger asks = new AtomicInteger();
        String response;

        FakeProvider(String response) {
            this.response = response;
        }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public boolean isRemote() { return true; }

        @Override
        public void complete(String system, String user, CompletionCallback cb) {
            asks.incrementAndGet();
            if (response == null) cb.onError("boom");
            else cb.onResult(response);
        }
    }

    @Test
    public void aValidPlanIsParsedIntoExecutableSteps() {
        FakeProvider p = new FakeProvider(
                "{\"steps\":[{\"tool\":\"search\",\"args\":{\"q\":\"laptops\"}},"
                + "{\"tool\":\"http\",\"args\":{\"url\":\"https://example.com\"}}]}");
        Plan plan = new LlmPlanner(p).plan("find laptops", TOOLS);

        // Tool steps plus the planner-owned Answer + Verify.
        assertEquals(4, plan.size());
        Plan.Step search = plan.steps().get(0);
        assertEquals("search", search.tool);
        assertEquals("search", search.request.action());
        assertEquals("laptops", search.request.param("q"));

        Plan.Step read = plan.steps().get(1);
        assertEquals("http", read.tool);
        assertEquals("fetch", read.request.action());
        assertEquals("https://example.com", read.request.param("url"));

        assertEquals(Task.STEP_ANSWER, plan.steps().get(2).label);
        assertEquals(Task.STEP_VERIFY, plan.steps().get(3).label);
    }

    @Test
    public void theBrowserActionBecomesTheRequestActionNotAParam() {
        FakeProvider p = new FakeProvider(
                "{\"steps\":[{\"tool\":\"browser\",\"args\":{\"action\":\"click\","
                + "\"selector\":\"#buy\"}}]}");
        Plan plan = new LlmPlanner(p).plan("click buy", TOOLS);

        Plan.Step step = plan.steps().get(0);
        assertEquals("browser", step.tool);
        assertEquals("click", step.request.action());
        assertEquals("#buy", step.request.param("selector"));
        assertNull("action must not linger as a param", step.request.param("action"));
    }

    @Test
    public void anUnknownToolFallsBackToTheCascade() {
        FakeProvider p = new FakeProvider(
                "{\"steps\":[{\"tool\":\"teleport\",\"args\":{}}]}");
        Plan plan = new LlmPlanner(p).plan("find laptops", TOOLS);

        assertEquals(4, plan.size());
        assertEquals(Task.STEP_SEARCH, plan.steps().get(0).label);
    }

    @Test
    public void malformedJsonFallsBackToTheCascade() {
        FakeProvider p = new FakeProvider("not json at all");
        Plan plan = new LlmPlanner(p).plan("find laptops", TOOLS);

        assertEquals(4, plan.size());
        assertEquals(Task.STEP_SEARCH, plan.steps().get(0).label);
    }

    @Test
    public void aProviderErrorFallsBackToTheCascade() {
        FakeProvider p = new FakeProvider(null);
        Plan plan = new LlmPlanner(p).plan("find laptops", TOOLS);

        assertEquals(4, plan.size());
    }

    @Test
    public void replanReturnsReplacementStepsOrNothing() {
        FakeProvider p = new FakeProvider(
                "{\"steps\":[{\"tool\":\"http\",\"args\":{\"url\":\"https://other.example\"}}]}");
        LlmPlanner planner = new LlmPlanner(p);

        Plan replacement = planner.replan(
                Plan.of(Plan.Step.internal("x")),
                Plan.Step.tool("http", "http", new com.mrnobody.agent.core.ToolRequest("fetch",
                        java.util.Collections.singletonMap("url", "https://a.example")), ""),
                "HTTP 404",
                TOOLS);

        assertEquals(1, replacement.size());
        assertEquals("http", replacement.steps().get(0).tool);
        assertEquals("https://other.example", replacement.steps().get(0).request.param("url"));

        // A failing provider yields no replan (the engine fails the task).
        FakeProvider broken = new FakeProvider(null);
        assertNull(new LlmPlanner(broken).replan(
                Plan.of(Plan.Step.internal("x")),
                Plan.Step.internal("x"), "error", TOOLS));
    }

    @Test
    public void aPlanFromTheModelIsBounded() {
        // Far more steps than Plan.MAX_STEPS must be refused, not honoured.
        StringBuilder sb = new StringBuilder("{\"steps\":[");
        for (int i = 0; i < Plan.MAX_STEPS + 5; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"tool\":\"http\",\"args\":{\"url\":\"https://").append(i).append("\"}}");
        }
        sb.append("]}");
        FakeProvider p = new FakeProvider(sb.toString());
        Plan plan = new LlmPlanner(p).plan("read everything", TOOLS);

        // Plan constructor clamps to MAX_STEPS; the planner still appends two.
        assertTrue(plan.size() <= Plan.MAX_STEPS + 2);
        assertFalse(plan.isAbandoned());
    }
}
