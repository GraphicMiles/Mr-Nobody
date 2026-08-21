package com.mrnobody.agent.planner;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Source-wiring proof for the read-loop economics batch — the pieces that
 * touch Android at runtime (the engine's tool plumbing, the browser tool, the
 * diagnostics screen) are asserted against their source, the same way
 * {@code WebViewBlockingWiringTest} and {@code ProfileCleanupWiringTest} do.
 *
 * <p>Behaviour is proven by {@code DeterministicEngineReadLoopTest}; this
 * file pins the wiring so a refactor cannot silently disconnect a rule.
 */
public class ReadLoopWiringTest {

    private static String engine() throws IOException {
        return read("agent/planner/DeterministicEngine.java");
    }

    private static String read(String rel) throws IOException {
        Path root = Paths.get("src/main/java/com/mrnobody");
        return new String(Files.readAllBytes(root.resolve(rel)), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- rule 1

    @Test
    public void theReadLoopConsultsEvidenceSufficiencyAfterEveryRead() throws IOException {
        String src = engine();
        int read = src.indexOf("readBestEffort(context, r, step, result, cancellation);");
        int check = src.indexOf("EvidenceSufficiency.enough(", read);
        assertTrue("sufficiency must be checked right after a read lands",
                read > 0 && check > read && check - read < 400);
    }

    @Test
    public void aReadThatCannotHelpIsNotFetchedAtAll() throws IOException {
        String src = engine();
        assertTrue("the http fetch is skipped before the tool call, not after",
                src.contains("\"http\".equals(step.tool) && !shouldReadMore(r)"));
        assertTrue(src.contains("private boolean shouldReadMore(Research r)"));
    }

    // ---------------------------------------------------------------- rule 2

    @Test
    public void escalatedReadsCarryTheEightSecondCap() throws IOException {
        String src = engine();
        assertTrue(src.contains("BROWSER_FETCH_TIMEOUT_MS = 8_000L"));
        int method = src.indexOf("private ToolResult readViaBrowser");
        int use = src.indexOf("BROWSER_FETCH_TIMEOUT_MS", method);
        assertTrue("readViaBrowser must pass the cap", method > 0 && use > method);
    }

    @Test
    public void readBestEffortRefusesToEscalateWhatCannotBeRecorded() throws IOException {
        String src = engine();
        int method = src.indexOf("private ToolResult readBestEffort");
        int guard = src.indexOf("r.readUrls.contains(url) || !shouldReadMore(r)", method);
        int ladder = src.indexOf("FetchLadder.firstStep(host)", method);
        assertTrue("the cap/dup/enough guard must precede any browser use",
                method > 0 && guard > method && ladder > guard);
    }

    // ---------------------------------------------------------------- rule 3

    @Test
    public void clockQuestionsAreAnsweredBeforeAnyPlanningOrClassification() throws IOException {
        String src = engine();
        int clock = src.indexOf("ClockSkill.answer(asked)");
        int classify = src.indexOf("IntentClassifier.classify(provider, asked)");
        assertTrue("clock skill must run before classification and planning",
                clock > 0 && classify > clock);
    }

    // ---------------------------------------------------------------- rule 5

    @Test
    public void bothPathsCreateAWallClockBudget() throws IOException {
        String src = engine();
        assertTrue("deterministic path budgets",
                src.indexOf("TaskBudget.download() : TaskBudget.research()")
                        != src.lastIndexOf("TaskBudget.download() : TaskBudget.research()"));
        assertTrue("autonomous loop breaks at the wall",
                src.contains("if (r.budget != null && r.budget.expired()) break;"));
    }

    @Test
    public void theDownloadHarvestStopsAtTheBudgetWall() throws IOException {
        String src = engine();
        int method = src.indexOf("private void resolveDownload");
        int wall = src.indexOf("r.budget.expired()", method);
        assertTrue(method > 0 && wall > method);
    }

    // ---------------------------------------------------------------- rule 6

    @Test
    public void chosenPagesAreRankedByCheapSuccess() throws IOException {
        String src = engine();
        int method = src.indexOf("private void planReads");
        int rank = src.indexOf("CandidateRank.byCheapSuccess(r.results)", method);
        assertTrue(method > 0 && rank > method);
    }

    @Test
    public void httpOutcomesFeedTheScore() throws IOException {
        String src = read("agent/tools/HttpTool.java");
        assertTrue("success/failure of every fetch is recorded",
                src.contains("SiteMemory.recordHttpOutcome(host,")
                        && src.contains("recordHttpOutcome(host, false)"));
    }

    // ------------------------------------------------------- image downloads

    @Test
    public void imageDownloadsHarvestImgSourcesNotJustAnchors() throws IOException {
        String engine = engine();
        int method = engine.indexOf("private void resolveDownload");
        assertTrue(engine.indexOf("DownloadLinkResolver.wantsImage(r.asked)", method) > method);
        assertTrue("img preview harvest from the read loop is reused, with origins",
                engine.indexOf("r.images.entrySet()", method) > method);
        assertTrue("the links call asks the browser for images",
                engine.indexOf("params.put(\"images\", \"true\")", method) > method);
        assertTrue("the winning candidate's source page rides along as the Referer",
                engine.indexOf("params.put(\"referer\", origin)", method) > method);

        String browser = read("agent/tools/BrowserTool.java");
        assertTrue(browser.contains("LINKS_AND_IMAGES_SCRIPT"));
        assertTrue("the script reads img srcs", browser.contains("img[src]"));
        assertTrue("the script reads srcset", browser.contains("srcset"));
        assertTrue("lazy-loaded galleries keep the real file in data-src",
                browser.contains("data-src"));
        assertTrue("the images switch is declared to the contract",
                browser.contains("ParamSpec.bool(\"images\""));
    }

    @Test
    public void headlessExtractionNeverFallsBackToRawTextContent() throws IOException {
        // textContent includes <style>/<script> text; a device answer once
        // quoted a CSS custom-property block as page evidence.
        String engine = read("agent/browser/HeadlessWebViewEngine.java");
        assertTrue("the fallback strips style/script subtrees first",
                engine.contains("querySelectorAll('style,script,noscript,template,svg')"));
        assertTrue("main falls back to the cleaned clone, not raw textContent",
                engine.contains("main.innerText||clean(main)"));
        assertTrue("body falls back the same way",
                engine.contains("b.innerText||clean(b)"));
    }

    @Test
    public void cheapCandidatesShortCircuitTheBrowserLinkHarvest() throws IOException {
        String src = engine();
        int method = src.indexOf("private void resolveDownload");
        int direct = src.indexOf("boolean directHonoursHost", method);
        int harvest = src.indexOf("!directHonoursHost && tools.containsKey(\"browser\")", method);
        assertTrue(method > 0 && direct > method && harvest > direct);
    }

    // ------------------------------------------------------------ benchmarks

    @Test
    public void theDefaultsBenchmarkProbesAnEmptyPrefsFileNotTheLiveOne() throws IOException {
        String src = read("debug/Diagnostics.java");
        assertTrue("defaults are probed against a cleared file",
                src.contains("mrnobody_defaults_probe"));
        assertTrue("the probe uses the named-file Settings constructor",
                src.contains("new Settings(context, probeFile)"));
    }

    @Test
    public void theEngineTestHarnessCanAlwaysGetALocalProvider() throws IOException {
        String src = read("browser/MrNobodyApp.java");
        assertTrue("activeProvider must not NPE before onCreate/settings",
                src.contains("if (settings == null) return new LocalProvider();"));
    }
}
