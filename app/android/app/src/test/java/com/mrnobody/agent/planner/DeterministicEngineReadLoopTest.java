package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;
import com.mrnobody.agent.policy.TaskBudget;
import com.mrnobody.agent.util.SiteMemory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The read-loop economics rules, proven end-to-end on the JVM with fake
 * tools: the engine plans, searches, chooses, reads and answers exactly as on
 * a device, while the fakes record what was fetched and what it cost.
 *
 * <p>Device evidence that motivated each rule is quoted at the test.
 */
public class DeterministicEngineReadLoopTest {

    private static final Context NO_CONTEXT = null;

    // ---------------------------------------------------------------- fakes

    /** A tool whose response is a function of the request; records calls. */
    static final class FakeTool implements Tool {
        final ToolSpec spec;
        final Tier tierForAll;
        final List<ToolRequest> calls = new ArrayList<>();
        Function<ToolRequest, ToolResult> responder;

        FakeTool(ToolSpec spec, Tier tierForAll, Function<ToolRequest, ToolResult> responder) {
            this.spec = spec;
            this.tierForAll = tierForAll;
            this.responder = responder;
        }

        @Override public ToolSpec spec() { return spec; }

        @Override public Tier tierFor(ToolRequest request) { return tierForAll; }

        @Override
        public synchronized ToolResult execute(Context context, ToolRequest request) {
            calls.add(request);
            return responder.apply(request);
        }

        synchronized List<String> urlsFetched() {
            List<String> out = new ArrayList<>();
            for (ToolRequest r : calls) out.add(r.param("url", ""));
            return out;
        }
    }

    private static ToolSpec searchSpec() {
        return ToolSpec.named("search")
                .describedAs("Fake search.")
                .tier(Tier.READ)
                .param(ParamSpec.string("q", true, "Query."))
                .param(ParamSpec.string("provider", false, "Engine."))
                .returns(OutputSpec.of(v -> String.valueOf(v.get("count")) + " results",
                        "results", "count"))
                .timeout(5_000)
                .build();
    }

    private static ToolSpec httpSpec() {
        return ToolSpec.named("http")
                .describedAs("Fake http fetch.")
                .tier(Tier.READ)
                .param(ParamSpec.url("url", true, "URL."))
                .returns(OutputSpec.of(v -> String.valueOf(v.get("text")),
                        "url", "status", "text"))
                .timeout(5_000)
                .build();
    }

    private static ToolSpec browserSpec() {
        return ToolSpec.named("browser")
                .describedAs("Fake headless browser.")
                .tier(Tier.WRITE) // narrowed per call by tierFor, like the real one
                .param(ParamSpec.enumOf("action", false, "Action.", "fetch", "links"))
                .param(ParamSpec.url("url", false, "URL."))
                .param(ParamSpec.integer("timeout", false, "Budget ms."))
                .param(ParamSpec.bool("images", false, "Collect img srcs."))
                .returns(OutputSpec.of(v -> String.valueOf(v.get("text")), "text"))
                .timeout(30_000)
                .build();
    }

    private static ToolResult searchResults(String... urls) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String url : urls) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", "Result for " + url);
            row.put("url", url);
            row.put("snippet", "A snippet mentioning eiffel tower construction history.");
            rows.add(row);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("results", rows);
        v.put("count", rows.size());
        return ToolResult.ok(v);
    }

    private static ToolResult page(String url, String text) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("url", url);
        v.put("status", 200);
        v.put("text", text);
        return ToolResult.ok(v);
    }

    /** Two question-matching prose sentences, unique per URL. */
    private static String richBody(String url) {
        String tag = url.replaceAll("[^a-z0-9]", " ");
        return "The Eiffel Tower construction started in 1887 according to the page at "
                + tag + " which covers the early years in detail. "
                + "The history of the tower construction on " + tag
                + " also describes the 1889 opening for the world fair. "
                + "Subscribe buttons and other page furniture follow here.";
    }

    /** Usable prose that never matches the question. */
    private static String blandBody(String url) {
        String tag = url.replaceAll("[^a-z0-9]", " ");
        return "This page at " + tag + " writes at length about gardening in the tropics. "
                + "Bananas and mangoes both grow well when watered generously every week.";
    }

    private static final String ASKED = "eiffel tower construction history";

    private DeterministicEngine engine;
    private FakeTool search;
    private FakeTool http;
    private FakeTool browser;

    @Before
    public void setUp() {
        SiteMemory.reset();
        engine = new DeterministicEngine();
        search = new FakeTool(searchSpec(), Tier.READ, req -> searchResults(
                "https://one.example/a", "https://two.example/b", "https://three.example/c",
                "https://four.example/d", "https://five.example/e", "https://six.example/f"));
        http = new FakeTool(httpSpec(), Tier.READ,
                req -> page(req.param("url", ""), richBody(req.param("url", ""))));
        browser = new FakeTool(browserSpec(), Tier.READ,
                req -> page(req.param("url", ""), richBody(req.param("url", ""))));
        engine.registerTool(search);
        engine.registerTool(http);
        engine.registerTool(browser);
    }

    @After
    public void tearDown() {
        TaskBudget.clearLimitOverrides();
        SiteMemory.reset();
    }

    private Task run(String instruction) {
        Task task = new Task(1L, instruction);
        engine.run(NO_CONTEXT, task, Cancellation.NONE);
        return task;
    }

    // ------------------------------------------------------------- the rules

    @Test
    public void rule1_readingStopsWhenTwoSourcesSuffice() {
        // "how old is messi" read six pages for >69s on-device; page two
        // already answered. With rich sources the loop must stop at two.
        Task task = run(ASKED);
        assertEquals(Task.Status.COMPLETED, task.status());
        assertEquals("reads after sufficiency are pure waste",
                2, http.calls.size());
        assertEquals("the browser must never run when http answered",
                0, browser.calls.size());
        assertTrue(task.result(), task.result().contains("[1]"));
        assertTrue(task.result(), task.result().contains("[2]"));
    }

    @Test
    public void rule2_noEscalationWhenHttpSucceededButEvidenceIsMerelyImperfect() {
        // pngtree: a 2.1s http success was followed by a 20.2s browser
        // "recovery". A usable-but-unhelpful page is a read source, not a
        // failure to escalate on.
        http.responder = req -> page(req.param("url", ""), blandBody(req.param("url", "")));
        Task task = run(ASKED);
        assertEquals(Task.Status.COMPLETED, task.status());
        assertEquals("read cap is three usable sources", 3, http.calls.size());
        assertEquals("usable http text must never pay for the browser",
                0, browser.calls.size());
    }

    @Test
    public void rule2_escalationHappensOnValidatedFailureAndIsCappedAtEightSeconds() {
        // Unusable http output IS the validated failure; the browser gets
        // one shot per source, with an 8s page-load cap, not twenty.
        http.responder = req -> page(req.param("url", ""), "{\"a\":1,\"b\":{\"c\":[2,3]}}");
        Task task = run(ASKED);
        assertEquals(Task.Status.COMPLETED, task.status());
        assertTrue("the browser is the legitimate fallback here",
                browser.calls.size() >= 1);
        for (ToolRequest req : browser.calls) {
            assertEquals("escalated reads are capped at 8s",
                    "8000", req.param("timeout", ""));
        }
        assertTrue(task.result(), task.result().contains("[1]"));
    }

    @Test
    public void rule5_budgetExpiryStillProducesAnAnswerFromEvidenceInHand() {
        // Never a spinner death: past the wall the engine answers with what
        // it has — here, only the search snippets.
        TaskBudget.overrideLimitsForTest(-1L, -1L);
        Task task = run(ASKED);
        assertEquals(Task.Status.COMPLETED, task.status());
        assertEquals("no read is started past the wall", 0, http.calls.size());
        assertEquals(0, browser.calls.size());
        assertNotNull(task.result());
        assertTrue("the snippet fallback labels itself honestly",
                task.result().contains("search listings"));
    }

    @Test
    public void rule6_cheapHostsAreReadFirst() {
        SiteMemory.recordHttpOutcome("five.example", true);
        SiteMemory.recordHttpOutcome("five.example", true);
        SiteMemory.recordHttpOutcome("one.example", false);
        SiteMemory.recordHttpOutcome("one.example", false);
        Task task = run(ASKED);
        assertEquals(Task.Status.COMPLETED, task.status());
        List<String> fetched = http.urlsFetched();
        assertTrue("something was read", fetched.size() >= 1);
        assertEquals("the proven-cheap host goes first",
                "https://five.example/e", fetched.get(0));
    }

    @Test
    public void rule3_clockQuestionsNeverTouchATool() {
        Task task = run("whats the time");
        assertEquals(Task.Status.COMPLETED, task.status());
        assertEquals(0, search.calls.size());
        assertEquals(0, http.calls.size());
        assertEquals(0, browser.calls.size());
        assertTrue(task.result(), task.result().contains("no network was used"));
    }
}
