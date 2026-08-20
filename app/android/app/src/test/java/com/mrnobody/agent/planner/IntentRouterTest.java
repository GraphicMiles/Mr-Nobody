package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** JVM tests for deterministic intent routing (the vertical slice entry point). */
public class IntentRouterTest {

    @Test
    public void urlWithSchemeIsUrl() {
        assertEquals(IntentType.URL, IntentRouter.route("https://example.com/page"));
        assertEquals(IntentType.URL, IntentRouter.route("http://example.com"));
    }

    @Test
    public void bareDomainIsUrl() {
        assertEquals(IntentType.URL, IntentRouter.route("example.com"));
        assertEquals(IntentType.URL, IntentRouter.route("github.com/GraphicMiles"));
    }

    @Test
    public void ipAndLocalhostAreUrls() {
        assertEquals(IntentType.URL, IntentRouter.route("192.168.1.1"));
        assertEquals(IntentType.URL, IntentRouter.route("localhost:8080"));
    }

    @Test
    public void aPlainPhraseIsSearchButAQuestionIsTask() {
        // No verb, no question word, no fact word: a plain search.
        assertEquals(IntentType.SEARCH, IntentRouter.route("latest Arsenal result"));
        assertEquals(IntentType.SEARCH, IntentRouter.route("arsenal"));
        // A natural-language question goes to the agent, not a results page.
        assertEquals(IntentType.TASK, IntentRouter.route("what is the weather"));
        assertEquals(IntentType.TASK, IntentRouter.route("how old is hrithik roshan"));
        // A vague/partial fact lookup is also a task.
        assertEquals(IntentType.TASK, IntentRouter.route("hrithik roshan age"));
        assertEquals(IntentType.TASK, IntentRouter.route("bitcoin price"));
    }

    @Test
    public void instructionIsTask() {
        assertEquals(IntentType.TASK, IntentRouter.route("Find laptops under 500000"));
        assertEquals(IntentType.TASK, IntentRouter.route("summarize this article"));
        assertEquals(IntentType.TASK, IntentRouter.route("download the report"));
    }

    @Test
    public void compoundInstructionIsTask() {
        assertEquals(IntentType.TASK, IntentRouter.route("open this site and download the pdf"));
    }

    @Test
    public void emptyIsSearch() {
        assertEquals(IntentType.SEARCH, IntentRouter.route(""));
        assertEquals(IntentType.SEARCH, IntentRouter.route(null));
        assertEquals(IntentType.SEARCH, IntentRouter.route("   "));
    }

    @Test
    public void researchReadAndUsePhrasingsAreTasks() {
        // BUG-7: all of these were observed on-device landing on a raw
        // results page instead of the agent.
        assertEquals(IntentType.TASK,
                IntentRouter.route("research the tallest buildings in Africa"));
        assertEquals(IntentType.TASK,
                IntentRouter.route("read example.com/article and summarize it"));
        assertEquals(IntentType.TASK,
                IntentRouter.route("use google search to find cheap flights"));
        assertEquals(IntentType.TASK,
                IntentRouter.route("look for the best laptops of 2026"));
    }

    @Test
    public void slashCommandsForceTheirType() {
        assertEquals(IntentType.TASK, IntentRouter.route("/agent latest arsenal result"));
        assertEquals(IntentType.TASK, IntentRouter.route("/task check the weather"));
        assertEquals(IntentType.TASK, IntentRouter.route("/download https://example.com/f.pdf"));
        // These would otherwise classify differently:
        assertEquals(IntentType.SEARCH, IntentRouter.route("/search what is the weather"));
        assertEquals(IntentType.URL, IntentRouter.route("/open example page"));
    }

    @Test
    public void slashPayloadStripsTheCommand() {
        assertEquals("find laptops", IntentRouter.payload("/agent find laptops"));
        assertEquals("what is love", IntentRouter.payload("/search what is love"));
        assertEquals("example.com", IntentRouter.payload("/open example.com"));
        assertEquals("download https://example.com/f.pdf",
                IntentRouter.payload("/download https://example.com/f.pdf"));
        // Non-commands pass through unchanged.
        assertEquals("plain text", IntentRouter.payload("  plain text "));
    }

    @Test
    public void aBareSlashWordIsNotACommand() {
        // No trailing payload → not treated as a slash command.
        assertEquals(null, IntentRouter.slashCommand("/agent"));
        assertEquals(null, IntentRouter.slashCommand("/searching habits"));
    }
}
