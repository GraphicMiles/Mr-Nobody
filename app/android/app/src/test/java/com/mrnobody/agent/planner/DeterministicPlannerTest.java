package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Task;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The plan the deterministic planner produces. The engine used to hard-code
 * this sequence as control flow; now it is data, and this test pins the shape
 * so a regression in planning fails here rather than on a device.
 */
public class DeterministicPlannerTest {

    private static final Set<String> TOOLS = new LinkedHashSet<>(
            Arrays.asList("search", "http", "download", "browser", "terminal"));

    private final Planner planner = new DeterministicPlanner();

    @Test
    public void aQuestionPlansTheResearchCascade() {
        Plan plan = planner.plan("find laptops under 500000", TOOLS);

        assertEquals(4, plan.size());
        assertToolStep(plan.steps().get(0), Task.STEP_SEARCH, "search");
        assertInternalStep(plan.steps().get(1), Task.STEP_READ);
        assertInternalStep(plan.steps().get(2), Task.STEP_ANSWER);
        assertInternalStep(plan.steps().get(3), Task.STEP_VERIFY);
    }

    @Test
    public void theSearchStepCarriesTheInstructionAsItsQuery() {
        Plan plan = planner.plan("find laptops under 500000", TOOLS);

        Plan.Step search = plan.steps().get(0);
        assertNotNull(search.request);
        assertEquals("search", search.request.action());
        assertEquals("find laptops under 500000", search.request.param("q"));
    }

    @Test
    public void latestYouTubeRequestUsesARestrictedVideoQuery() {
        Plan plan = planner.plan(
                "the latest video on youtube from screen crush channel", TOOLS);
        String query = plan.steps().get(0).request.param("q");
        assertTrue(query, query.contains("screen crush"));
        assertEquals("youtube", plan.steps().get(0).request.param("provider"));
    }

    @Test
    public void aDownloadInstructionPlansASingleAction() {
        // A real file URL is a direct action: fetch that one file.
        Plan plan = planner.plan(
                "download https://example.test/report.pdf", TOOLS);

        assertEquals(1, plan.size());
        assertToolStep(plan.steps().get(0), Task.STEP_ACT, "download");
        assertNotNull("the action step must carry its arguments", plan.steps().get(0).request);
        assertEquals("https://example.test/report.pdf",
                plan.steps().get(0).request.param("url"));
    }

    @Test
    public void aLandingPageUrlPlansResolveNotADirectFetch() {
        // "…/Silo.S03E01.(THENKIRI.COM).mkv.html" is a page that *contains* ".mkv"
        // but returns HTML. It must not be fetched as a file; the plan has to
        // read it and resolve the real file link. (Regression: the agent saved
        // the HTML page as the download.)
        Plan plan = planner.plan(
                "download https://downloadwella.com/x/Silo.S03E01.(THENKIRI.COM).mkv.html",
                TOOLS);

        assertFalse("a landing page must not be a single direct download",
                plan.size() == 1 && Task.STEP_ACT.equals(plan.steps().get(0).label));
        assertTrue("a landing page download plans a resolve step",
                plan.steps().stream().anyMatch(
                        s -> Task.STEP_RESOLVE_DOWNLOAD.equals(s.label)));
    }

    @Test
    public void aDownloadByNamePlansResolveThenDownload() {
        // "download moci" names no URL, so it cannot route straight to the
        // downloader. The plan must search, read, then resolve a file link —
        // not hand the model an invented URL.
        Plan plan = planner.plan("download moci", TOOLS);

        assertEquals(5, plan.size());
        assertEquals(Task.STEP_SEARCH, plan.steps().get(0).label);
        assertEquals(Task.STEP_READ, plan.steps().get(1).label);
        assertEquals(Task.STEP_RESOLVE_DOWNLOAD, plan.steps().get(2).label);
        assertEquals(Task.STEP_ANSWER, plan.steps().get(3).label);
        assertEquals(Task.STEP_VERIFY, plan.steps().get(4).label);
    }

    @Test
    public void aQuestionStillPlansThePlainCascade() {
        Plan plan = planner.plan("what is the capital of ghana", TOOLS);

        assertEquals(4, plan.size());
        assertEquals(Task.STEP_SEARCH, plan.steps().get(0).label);
        assertEquals(Task.STEP_READ, plan.steps().get(1).label);
        assertEquals(Task.STEP_ANSWER, plan.steps().get(2).label);
        assertEquals(Task.STEP_VERIFY, plan.steps().get(3).label);
    }

    @Test
    public void aToolThatIsNotRegisteredIsNeverRoutedTo() {
        // With no download tool, the same instruction falls back to research
        // rather than planning a call the engine cannot serve.
        Set<String> noDownload = new LinkedHashSet<>(Arrays.asList("search", "http"));
        Plan plan = planner.plan("download the report.pdf from example.com", noDownload);

        assertEquals(4, plan.size());
        assertEquals(Task.STEP_SEARCH, plan.steps().get(0).label);
    }

    private static void assertToolStep(Plan.Step step, String label, String tool) {
        assertEquals(label, step.label);
        assertTrue(step.isToolStep());
        assertEquals(tool, step.tool);
    }

    private static void assertInternalStep(Plan.Step step, String label) {
        assertEquals(label, step.label);
        assertFalse(step.isToolStep());
    }
}
