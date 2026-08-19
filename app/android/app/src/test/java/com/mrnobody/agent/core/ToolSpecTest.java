package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The declared contract: parameter checking, the model-facing projection, and
 * the rule that a structured tool may not return a page.
 */
public class ToolSpecTest {

    // ---------------------------------------------------------- parameters

    @Test
    public void urlParametersRejectSchemesThatEscapeTheSandbox() {
        ParamSpec url = ParamSpec.url("url", true, "");
        assertNull(url.validate("https://example.com/x?y=1"));
        assertNull(url.validate("http://192.168.0.1:8080"));
        for (String hostile : new String[]{
                "file:///data/data/com.mrnobody.browser/databases/tasks.db",
                "content://com.android.contacts/contacts",
                "javascript:fetch('/steal')",
                "intent://scan/#Intent;scheme=zxing;end"}) {
            assertFalse(hostile, url.validate(hostile) == null);
        }
    }

    @Test
    public void integerAndBooleanParametersAreChecked() {
        assertNull(ParamSpec.integer("ms", true, "").validate("1500"));
        assertFalse(ParamSpec.integer("ms", true, "").validate("soon") == null);
        assertNull(ParamSpec.bool("force", true, "").validate("TRUE"));
        assertFalse(ParamSpec.bool("force", true, "").validate("yes") == null);
    }

    @Test
    public void enumParametersListWhatWasExpected() {
        ParamSpec direction = ParamSpec.enumOf("direction", true, "", "up", "down");
        assertNull(direction.validate("Down"));
        String problem = direction.validate("sideways");
        assertTrue(problem, problem.contains("up, down"));
    }

    @Test
    public void anOptionalParameterMayBeAbsentButNotMalformed() {
        ParamSpec optional = ParamSpec.integer("ms", false, "");
        assertNull(optional.validate(null));
        assertNull(optional.validate(""));
        assertFalse(optional.validate("later") == null);
    }

    @Test
    public void everyProblemIsReportedAtOnce() {
        // A model that gets one error per attempt burns a step per mistake.
        ToolSpec spec = ToolSpec.named("t")
                .param(ParamSpec.url("url", true, ""))
                .param(ParamSpec.integer("ms", true, ""))
                .build();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ms", "soon");
        String problems = spec.validate(new ToolRequest("go", params));
        assertTrue(problems, problems.contains("url is required"));
        assertTrue(problems, problems.contains("ms must be a whole number"));
    }

    // ------------------------------------------------------- model-facing

    @Test
    public void theModelSchemaExposesOnlyNameDescriptionAndParameters() {
        ToolSpec spec = ToolSpec.named("search")
                .describedAs("Search the web.")
                .tier(Tier.EXEC)
                .param(ParamSpec.string("q", true, "What to search for."))
                .timeout(1234)
                .build();

        String json = spec.toJsonSchema();

        assertTrue(json, json.contains("\"name\":\"search\""));
        assertTrue(json, json.contains("\"required\":[\"q\"]"));
        // Internals must not leak into a model request: they are ours, they
        // cost context, and they are a prompt-injection surface.
        assertFalse(json, json.contains("1234"));
        assertFalse(json, json.toUpperCase().contains("EXEC"));
        assertFalse(json, json.contains("timeout"));
    }

    @Test
    public void schemaTextIsEscaped() {
        ToolSpec spec = ToolSpec.named("t")
                .describedAs("Say \"hello\"\nthen stop")
                .build();
        String json = spec.toJsonSchema();
        assertTrue(json, json.contains("\\\"hello\\\""));
        assertFalse("a raw newline would break the payload", json.contains("\n"));
    }

    // -------------------------------------------------------------- output

    @Test
    public void aStructuredToolMayNotReturnAPage() {
        OutputSpec output = OutputSpec.of(v -> "x", "text");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "<!DOCTYPE html><html><body><p>hi</p></body></html>");
        String problem = output.validate(value);
        assertTrue(problem, problem.contains("raw markup"));
    }

    @Test
    public void markupIsDetectedInsideNestedResults() {
        OutputSpec output = OutputSpec.of(v -> "x", "results");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "ok");
        row.put("snippet", "<div class=a></div><div class=b></div><span></span><p></p><li></li>"
                + "<ul></ul><td></td><tr></tr><em></em><b></b><i></i>");
        List<Object> rows = new ArrayList<>();
        rows.add(row);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("results", rows);

        String problem = output.validate(value);

        assertTrue(problem, problem.contains("raw markup"));
        assertTrue("the offending field is named", problem.contains("snippet"));
    }

    @Test
    public void ordinaryProseIsNotMistakenForMarkup() {
        // False positives here would break real results, so the rule has to be
        // about documents, not about the character '<'.
        assertFalse(OutputSpec.looksLikeMarkup(
                "The <div> element is a generic container; use </div> to close it."));
        assertFalse(OutputSpec.looksLikeMarkup(
                "Prices: 12 < 15 < 20, and the review said \"a < b\" repeatedly."));
        assertFalse(OutputSpec.looksLikeMarkup("Short <p>"));
    }

    @Test
    public void aMissingRequiredKeyIsRejected() {
        OutputSpec output = OutputSpec.of(v -> "x", "query", "results");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("query", "laptops");
        assertTrue(output.validate(value).contains("results"));
    }

    @Test
    public void renderingIsAProjectionOfTheValue() {
        OutputSpec output = OutputSpec.of(v -> "found " + v.get("count"), "count");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("count", 3);
        assertNull(output.validate(value));
        assertEquals("found 3", output.render(value));
    }

    @Test
    public void aResultWithoutARenderStillReadsSensibly() {
        ToolResult result = ToolResult.okText("plain");
        assertEquals("plain", result.result());
        assertEquals("plain", result.value().get("text"));
    }

    @Test
    public void tiersAreOrderedByConsequence() {
        assertTrue(Tier.EXEC.atLeast(Tier.WRITE));
        assertTrue(Tier.WRITE.atLeast(Tier.SANDBOX));
        assertTrue(Tier.SANDBOX.atLeast(Tier.READ));
        assertTrue(Tier.WRITE.atLeast(Tier.READ));
        assertFalse(Tier.READ.atLeast(Tier.WRITE));
        assertFalse(Tier.SANDBOX.atLeast(Tier.WRITE));
    }
}
