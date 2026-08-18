package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single-step decision parsing for the autonomous loop. The one invariant that
 * matters most: a malformed or "done" response reads as null (stop), never as
 * an endless loop.
 */
public class StepCodecTest {

    private static final Set<String> TOOLS = new LinkedHashSet<>(
            Arrays.asList("search", "http", "download", "browser", "terminal"));

    @Test
    public void aToolStepParsesWithNormalisation() {
        Plan.Step s = StepCodec.parseOneStep(
                "{\"tool\":\"search\",\"args\":{\"query\":\"laptops\"}}", TOOLS);
        assertNotNull(s);
        assertEquals("search", s.tool);
        assertEquals("laptops", s.request.param("q"));
    }

    @Test
    public void doneSignalsStop() {
        assertNull(StepCodec.parseOneStep("{\"done\":true}", TOOLS));
    }

    @Test
    public void malformedOrEmptyResponsesStop() {
        assertNull(StepCodec.parseOneStep("not json", TOOLS));
        assertNull(StepCodec.parseOneStep("", TOOLS));
        assertNull(StepCodec.parseOneStep("{}", TOOLS));
    }

    @Test
    public void anUnknownToolStops() {
        assertNull(StepCodec.parseOneStep("{\"tool\":\"teleport\",\"args\":{}}", TOOLS));
    }

    @Test
    public void aBareDomainGetsItsSchemeFilledIn() {
        Plan.Step s = StepCodec.parseOneStep(
                "{\"tool\":\"http\",\"args\":{\"url\":\"example.com/page\"}}", TOOLS);
        assertNotNull(s);
        assertEquals("https://example.com/page", s.request.param("url"));
    }

    @Test
    public void aUsableStepWithoutAUrlStops() {
        assertNull(StepCodec.parseOneStep("{\"tool\":\"download\",\"args\":{\"url\":\"notaurl\"}}", TOOLS));
    }
}
