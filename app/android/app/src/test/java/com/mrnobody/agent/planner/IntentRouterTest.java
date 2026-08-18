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
}
