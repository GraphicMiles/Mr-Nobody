package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Which tool an instruction needs.
 *
 * <p>The absence of this produced the reported bug: the planner ran a fixed
 * cascade with two hard-coded calls, so {@code DownloadTool} was registered
 * and unreachable and "…and download it" was never attempted — not refused,
 * simply never tried, while the model answered a research question instead.
 *
 * <p>The negative cases matter as much as the positive ones. Routing to a
 * downloader when the user asked a question would fetch bytes nobody wanted.
 */
public class ToolRouterTest {

    private static final Set<String> ALL = new HashSet<>(Arrays.asList(
            "search", "http", "browser", "download", "terminal"));

    // ------------------------------------------------------------- routing

    @Test
    public void aDirectFileUrlWithADownloadVerbRoutesToTheDownloader() {
        ToolRouter.Route r = ToolRouter.route(
                "download https://example.test/show.s04e01.mkv", ALL);

        assertNotNull(r);
        assertEquals("download", r.tool);
        assertEquals("https://example.test/show.s04e01.mkv", r.request.param("url"));
    }

    @Test
    public void otherDownloadVerbsAlsoRoute() {
        assertNotNull(ToolRouter.route("save https://example.test/a.zip", ALL));
        assertNotNull(ToolRouter.route("grab https://example.test/a.pdf", ALL));
    }

    @Test
    public void theRouteExplainsItself() {
        ToolRouter.Route r = ToolRouter.route("download https://example.test/a.zip", ALL);
        assertNotNull(r.reason);
    }

    @Test
    public void trailingPunctuationIsNotPartOfTheUrl() {
        ToolRouter.Route r = ToolRouter.route("download https://example.test/a.zip.", ALL);
        assertEquals("https://example.test/a.zip", r.request.param("url"));
    }

    // ------------------------------------------------- falls back to research

    @Test
    public void theReportedInstructionStillGoesToTheCascade() {
        // "from nkiri.ink" names a site, not a file. The page has to be read
        // before anything can be downloaded, so routing straight to the
        // downloader would fetch HTML and call it a file.
        assertNull(ToolRouter.route(
                "search for reacher season 4 episode from nkiri.ink and download it", ALL));
    }

    @Test
    public void aQuestionIsNotAnAction() {
        assertNull(ToolRouter.route("what is the capital of Nigeria", ALL));
        assertNull(ToolRouter.route("summarize the news today", ALL));
    }

    @Test
    public void aDownloadVerbWithNothingToFetchFallsBack() {
        assertNull(ToolRouter.route("download the thing", ALL));
    }

    @Test
    public void emptyAndNullAreSafe() {
        assertNull(ToolRouter.route(null, ALL));
        assertNull(ToolRouter.route("   ", ALL));
    }

    // ------------------------------------------------------------ permission

    /**
     * Selection is not permission, but it is also not a way around
     * registration: a tool the engine does not have must be unreachable, not
     * reached and refused. This is what keeps the terminal switch meaningful.
     */
    @Test
    public void anUnregisteredToolIsNeverRoutedTo() {
        Set<String> withoutDownload = new HashSet<>(Arrays.asList("search", "http"));
        assertNull(ToolRouter.route("download https://example.test/a.zip", withoutDownload));
    }

    @Test
    public void noToolsAtAllMeansNoRoute() {
        assertNull(ToolRouter.route("download https://example.test/a.zip",
                Collections.emptySet()));
        assertNull(ToolRouter.route("download https://example.test/a.zip", null));
    }

    // -------------------------------------------------------------- terminal

    @Test
    public void aShellIntentRoutesToTheTerminalWhenEnabled() {
        ToolRouter.Route r = ToolRouter.route("pull my repo", ALL);
        assertNotNull("a shell intent must reach the terminal, not a web search", r);
        assertEquals("terminal", r.tool);
        assertEquals("pull my repo", r.request.param("cmd"));

        assertEquals("terminal", ToolRouter.route("git clone https://github.com/x/y", ALL).tool);
        assertEquals("terminal", ToolRouter.route("pip install requests", ALL).tool);
        assertEquals("terminal", ToolRouter.route("run python script.py", ALL).tool);
    }

    @Test
    public void aShellIntentDoesNotRouteWhenTheTerminalIsOff() {
        Set<String> noTerminal = new HashSet<>(Arrays.asList("search", "http", "download"));
        assertNull(ToolRouter.route("pull my repo", noTerminal));
        assertNull(ToolRouter.route("git clone https://github.com/x/y", noTerminal));
    }
}
