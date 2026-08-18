package com.mrnobody.agent.planner;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.ToolRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A model-backed planner: the active AI provider proposes the steps.
 *
 * <p>This is the thing that turns the agent from a research cascade into a
 * multi-step agent. The provider is asked to return a plan as JSON — a list of
 * tool steps — and the plan is then executed by the engine through the same
 * guarded pipeline as every other call. Choosing a step is still not
 * permitting it: an EXEC step the model proposes still asks the user.
 *
 * <p>Validation is strict and fail-closed. The model's JSON is parsed and every
 * step checked against the registered tools before anything runs; on any
 * parse, shape, or unknown-tool error the planner falls back to
 * {@link DeterministicPlanner}, so a bad model output can never break a task —
 * it degrades to the behaviour that always existed.
 *
 * <p>The Answer and Verify internal steps are appended by this planner, not
 * produced by the model: synthesising an answer from the gathered sources and
 * verifying it is the engine's job and must not be delegated to whatever the
 * model happened to emit.
 */
public final class LlmPlanner implements Planner {

    private static final long ASK_TIMEOUT_MS = 60_000L;

    private final AiProvider provider;
    private final DeterministicPlanner fallback = new DeterministicPlanner();

    public LlmPlanner(AiProvider provider) {
        this.provider = provider;
    }

    @Override
    public Plan plan(String instruction, Collection<String> availableTools) {
        String prompt = planPrompt(instruction, availableTools);
        String response = ask(prompt);
        if (response == null) return fallback.plan(instruction, availableTools);

        List<Plan.Step> steps = parseSteps(response, availableTools);
        if (steps == null || steps.isEmpty()) return fallback.plan(instruction, availableTools);

        // The model proposes the tool work; the engine owns the answer.
        steps.add(Plan.Step.internal(Task.STEP_ANSWER));
        steps.add(Plan.Step.internal(Task.STEP_VERIFY));
        return new Plan(steps);
    }

    @Override
    public Plan replan(Plan current, Plan.Step failedStep, String error,
                       Collection<String> availableTools) {
        String prompt = "You are planning steps for Mr Nobody. A step failed and you must "
                + "propose what to do instead.\n\n"
                + "Failed step: " + (failedStep == null ? "unknown" : failedStep.label)
                + (failedStep != null && failedStep.isToolStep() ? " (" + failedStep.tool + ")" : "")
                + "\nError: " + (error == null ? "unknown" : error) + "\n\n"
                + "Available tools: " + String.join(", ", availableTools) + "\n\n"
                + "Return JSON only, in this shape:\n"
                + "{\"steps\":[{\"tool\":\"http\",\"args\":{\"url\":\"https://...\"}}]}\n"
                + "Return an empty steps array if there is nothing useful to try.";
        String response = ask(prompt);
        if (response == null) return null;

        List<Plan.Step> steps = parseSteps(response, availableTools);
        return steps == null ? null : new Plan(steps);
    }

    /** The prompt that asks for a plan. */
    private static String planPrompt(String instruction, Collection<String> availableTools) {
        return "You are the planner for Mr Nobody, a privacy-respecting web assistant. "
                + "Break this instruction into the smallest set of tool steps that answers it.\n\n"
                + "Instruction: " + (instruction == null ? "" : instruction) + "\n\n"
                + "Available tools: " + String.join(", ", availableTools) + "\n"
                + "Tool arguments: search takes {q}; http takes {url}; browser takes {url,action}; "
                + "download takes {url}.\n\n"
                + "Return JSON only, in this shape:\n"
                + "{\"steps\":[{\"tool\":\"search\",\"args\":{\"q\":\"...\"}},"
                + "{\"tool\":\"http\",\"args\":{\"url\":\"https://...\"}}]}\n"
                + "Prefer fewer steps. Do not include answer or verification steps.";
    }

    /**
     * Parse the model's JSON into tool steps, validating each against the
     * registered tools. Returns null on any error — the caller falls back.
     *
     * <p>The browser tool dispatches on {@code request.action()}, so a model's
     * {@code args.action} becomes the request action and the rest stay params;
     * every other tool takes its action from a fixed map and keeps all args as
     * params. A missing required parameter is left missing — the pipeline's
     * spec validation refuses it, fail-closed, rather than this planner
     * inventing a value.
     */
    private static List<Plan.Step> parseSteps(String response, Collection<String> availableTools) {
        try {
            JSONObject root = new JSONObject(response.trim());
            JSONArray arr = root.optJSONArray("steps");
            if (arr == null) return null;

            List<Plan.Step> steps = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) return null;
                String tool = s.optString("tool", "").trim();
                if (tool.isEmpty()) return null;
                if (!availableTools.contains(tool)) return null;

                JSONObject args = s.optJSONObject("args");
                Map<String, String> params = new LinkedHashMap<>();
                if (args != null) {
                    for (String key : args.keySet()) {
                        if (!args.isNull(key)) params.put(key, String.valueOf(args.opt(key)));
                    }
                }

                String action;
                if ("browser".equals(tool)) {
                    // The browser's action is part of the request, not a param.
                    action = params.containsKey("action") ? params.remove("action") : "open";
                } else {
                    action = actionFor(tool);
                }
                steps.add(Plan.Step.tool(tool, tool, new ToolRequest(action, params),
                        "planned by model"));
            }
            return steps;
        } catch (Exception e) {
            return null;
        }
    }

    /** The ToolRequest action each tool expects, matching the deterministic planner. */
    private static String actionFor(String tool) {
        switch (tool) {
            case "search":
                return "search";
            case "http":
                return "fetch";
            case "download":
                return "download";
            default:
                return tool;
        }
    }

    /** Blocking ask of the provider, with a timeout. Null on error or timeout. */
    private String ask(String prompt) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        try {
            provider.complete(SYSTEM_PROMPT, prompt, new AiProvider.CompletionCallback() {
                @Override public void onResult(String text) {
                    out[0] = text;
                    latch.countDown();
                }
                @Override public void onError(String error) {
                    latch.countDown();
                }
            });
            if (!latch.await(ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out[0];
    }

    private static final String SYSTEM_PROMPT =
            "You are the planner for Mr Nobody, a privacy-respecting web assistant. "
            + "You answer only with valid JSON. You never invent a tool.";
}
