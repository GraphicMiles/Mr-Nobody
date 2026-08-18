package com.mrnobody.agent.planner;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.core.Task;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A model-backed planner: the active AI provider proposes the steps.
 *
 * <p>This is the plan-once path — the model returns the whole plan up front,
 * then the engine executes it. The step-by-step alternative is the autonomous
 * planner in {@link AutonomousPlanner}; both normalise the model's output
 * through the same {@link StepCodec}, so neither drifts from the other.
 *
 * <p>Validation is strict and fail-closed. The model's JSON is parsed and every
 * step checked against the registered tools before anything runs; on any
 * parse, shape, or unknown-tool error the planner falls back to
 * {@link DeterministicPlanner}, so a bad model output can never break a task —
 * it degrades to the behaviour that always existed.
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

        List<Plan.Step> steps = StepCodec.parseSteps(response, availableTools);
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

        List<Plan.Step> steps = StepCodec.parseSteps(response, availableTools);
        return steps == null ? null : new Plan(steps);
    }

    /** The prompt that asks for a whole plan. */
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
