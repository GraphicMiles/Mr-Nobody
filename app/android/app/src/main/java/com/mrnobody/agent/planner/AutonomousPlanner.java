package com.mrnobody.agent.planner;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.ai.TokenUsage;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The step-by-step planner: the agent decides its next action from what it has
 * observed so far, instead of committing to a fixed plan up front.
 *
 * <p>This is the observe → reason → act loop. Each {@link #nextStep} call hands
 * the model the instruction plus the transcript of tool results gathered so
 * far, and asks for exactly one decision: call a tool, or finish gathering.
 * The engine executes the step through the guarded pipeline, feeds the result
 * back, and asks again — until the model says {@code done}, the step ceiling is
 * reached, or the budget guard refuses. The answer itself is still synthesised
 * and verified by the engine, not written by this loop.
 *
 * <p>Safety is structural, not begged for: every returned step is normalised
 * and validated by the same {@link StepCodec} the plan-once path uses, and a
 * malformed or unknown-tool response reads as {@code done} (null) rather than
 * looping forever. Page content in the transcript is fenced with
 * {@link UntrustedContent} before it reaches the model.
 */
public final class AutonomousPlanner {

    private static final long ASK_TIMEOUT_MS = 60_000L;

    private final AiProvider provider;
    private final String nonce;
    private final Consumer<TokenUsage> usageSink;

    public AutonomousPlanner(AiProvider provider, String nonce) {
        this(provider, nonce, null);
    }

    /**
     * @param usageSink receives the provider's reported usage for every planning
     *                  call, so the run can total its token spend. May be null.
     */
    public AutonomousPlanner(AiProvider provider, String nonce, Consumer<TokenUsage> usageSink) {
        this.provider = provider;
        this.nonce = nonce;
        this.usageSink = usageSink;
    }

    /**
     * Propose the single next tool step, or null to finish gathering.
     *
     * @param transcript already-rendered, already-fenced lines of what happened
     *                   so far (one per tool call).
     */
    public Plan.Step nextStep(String instruction, List<String> transcript,
                              Collection<String> availableTools) {
        String prompt = buildPrompt(instruction, transcript, availableTools);
        String response = ask(prompt);
        if (response == null) return null;
        return StepCodec.parseOneStep(response, availableTools);
    }

    private String buildPrompt(String instruction, List<String> transcript,
                               Collection<String> availableTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question from the user:\n").append(instruction).append("\n\n");
        sb.append("You may call these tools: ").append(String.join(", ", availableTools)).append("\n");
        sb.append("search takes {q}; http takes {url}; browser takes {url,action}; ")
                .append("download takes {url}; terminal takes {cmd}; memory takes {q}.\n\n");
        sb.append("For research and monitoring, prefer the read-only tools — search, ")
                .append("http, and browser (action fetch or open) — and say {done} once you ")
                .append("have read enough. If the user named a site, fetch that site; do not substitute other sites. If they asked to keep watching something, fetch the page. Mr Nobody schedules the repeat, so do not recommend other monitoring apps. terminal and download are for when the user ")
                .append("explicitly asks to run a command or fetch a file. Never call a ")
                .append("privileged tool when a read would answer the question.\n\n");
        if (transcript != null && !transcript.isEmpty()) {
            sb.append("What you have done so far:\n");
            int n = 1;
            for (String line : transcript) {
                sb.append(n++).append(". ").append(line).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Decide the single next action. Reply with JSON only:\n")
                .append("{\"tool\":\"search\",\"args\":{\"q\":\"...\"}}  to call a tool, or\n")
                .append("{\"done\":true}  when you have gathered enough to answer.\n")
                .append("Do not repeat a call that just failed. Prefer the fewest steps.");
        return sb.toString();
    }

    /** Blocking ask of the provider, with a timeout. Null on error or timeout. */
    private String ask(String prompt) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        try {
            provider.complete(systemPrompt(), prompt, new AiProvider.CompletionCallback() {
                @Override public void onResult(String text) {
                    out[0] = text;
                    latch.countDown();
                }
                @Override public void onError(String error) {
                    latch.countDown();
                }
                @Override public void onUsage(TokenUsage usage) {
                    if (usageSink != null) usageSink.accept(usage);
                }
            });
            if (!latch.await(ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out[0];
    }

    private String systemPrompt() {
        return "You are the planner for Mr Nobody, a privacy-respecting web assistant. "
                + "You answer only with valid JSON. You never invent a tool.\n"
                + UntrustedContent.rules(nonce);
    }
}
