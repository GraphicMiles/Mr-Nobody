package com.mrnobody.agent.planner;

import android.content.Context;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolPipeline;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.policy.ApprovalMode;
import com.mrnobody.agent.policy.ApprovalPolicy;
import com.mrnobody.agent.policy.RepeatCallGuard;
import com.mrnobody.agent.tools.HttpTool;
import com.mrnobody.agent.tools.SearchTool;
import com.mrnobody.agent.util.Hosts;
import com.mrnobody.browser.MrNobodyApp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V1 agent brain — deterministic by default, with an optional AI synthesis step.
 *
 * Lightest-tool cascade for a task:
 *   1. search (returns PARSED results — title/url/snippet)
 *   2. if the instruction names a URL, drive the headless browser on it
 *   3. optional AI synthesis (only if a remote provider is enabled)
 *
 * The deterministic path never dumps raw HTML or page scrapes: it shows the
 * parsed search results (or extracted page text), which is a clean, readable
 * answer. V2 replaces the internals with LLM planning behind the same
 * AgentEngine interface; nothing here is rewritten.
 */
public final class DeterministicEngine implements AgentEngine {

    private static final String SYSTEM_PROMPT =
            "You are Mr Nobody, a privacy-respecting web assistant. You answer only from "
            + "the sources given to you in the message, and you cite them by number. "
            + "If the sources do not contain the answer, you say so plainly instead of "
            + "recalling something plausible. An uncited claim is treated as a mistake.";

    /** How many pages are worth reading before answering. */
    private static final int MAX_SOURCES_READ = 3;

    /** How much of each page the model sees. */
    private static final int PER_SOURCE_CHARS = 2500;

    /** How long a remote provider gets before we give up on it. */
    private static final long PROVIDER_TIMEOUT_MS = 90_000L;

    /** How often that wait checks for a cancel request. */
    private static final long POLL_MS = 250L;

    private static final Pattern URL_IN_TEXT =
            Pattern.compile("(https?://[^\\s\"'<>]+)");

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * Every tool call goes through this — see {@link ToolPipeline}.
     *
     * <p>The policy is tier x mode x per-tool override, and the repeat guard
     * is attached from the start: a planner that cannot make progress will
     * otherwise reissue the same failing call until the battery notices.
     */
    private final ApprovalPolicy policy =
            new ApprovalPolicy(ApprovalMode.CAUTIOUS, ApprovalPolicy.Overrides.NONE);

    private final RepeatCallGuard repeatGuard = new RepeatCallGuard();

    private final ToolPipeline pipeline =
            new ToolPipeline(policy).addGuard(repeatGuard);

    public DeterministicEngine() {
        tools.put("search", new SearchTool());
        tools.put("http", new HttpTool());
    }

    /** Register an additional tool (e.g. a BrowserTool wrapping a headless engine). */
    public void registerTool(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** Remove a tool — used when the user switches the terminal back off. */
    public void unregisterTool(String name) {
        tools.remove(name);
    }

    /** Whether a tool is currently available to the planner. */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /** The approval policy, so Settings can change the mode at runtime. */
    public ApprovalPolicy policy() {
        return policy;
    }

    /** Names of every registered tool. */
    public java.util.Set<String> toolNames() {
        return java.util.Collections.unmodifiableSet(tools.keySet());
    }

    @Override
    public void run(Context context, Task task, Cancellation cancellation) {
        String instruction = task.instruction();
        task.setStatus(Task.Status.RUNNING);

        // Cancellation is observed between steps: the task stops in a state we
        // can describe, never halfway through one.
        if (stopped(task, cancellation)) return;

        // 0. Does this instruction name an action rather than a question?
        //    Until the router existed, the answer was always "no": the cascade
        //    below ran regardless, so "...and download it" reached a model that
        //    could only answer, never act. A routed call is still subject to
        //    the pipeline, so choosing a tool is not the same as permitting it.
        ToolRouter.Route route = ToolRouter.route(instruction, tools.keySet());
        if (route != null) {
            runRoutedAction(context, task, route, cancellation);
            return;
        }

        // 1. Search — parsed results, never a page scrape. A refusal is a
        //    failure: an answer with no sources is a guess with a citation.
        task.setCurrentStep(Task.STEP_SEARCH);
        ToolResult search = callTool(context, "search",
                ToolRequest.of("search", "q", instruction), cancellation);
        if (!search.isSuccess()) {
            fail(task, search);
            return;
        }
        if (stopped(task, cancellation)) return;

        List<Map<String, Object>> results = resultsOf(search);
        if (results.isEmpty()) {
            fail(task, ToolResult.fail("The search returned nothing to read, so there is "
                    + "nothing to answer from."));
            return;
        }

        // 2. Read the sources. This is the step that was missing: previously
        //    only a URL typed by the user was ever opened, so an instruction
        //    like "find the best X" reached the model as five snippets and a
        //    blank cheque.
        task.setCurrentStep(Task.STEP_READ);
        List<String> readUrls = new ArrayList<>();
        StringBuilder sources = new StringBuilder();
        String namedUrl = findUrl(instruction);
        if (namedUrl != null) {
            String text = readPage(context, namedUrl, cancellation);
            if (!text.isEmpty()) {
                readUrls.add(namedUrl);
                appendSource(sources, readUrls.size(), namedUrl, "the page you named", text);
            }
        }
        for (Map<String, Object> result : results) {
            if (readUrls.size() >= MAX_SOURCES_READ) break;
            if (stopped(task, cancellation)) return;
            String url = String.valueOf(result.get("url"));
            String title = String.valueOf(result.get("title"));
            if (url.isEmpty() || readUrls.contains(url)) continue;
            String text = readPage(context, url, cancellation);
            if (text.isEmpty()) continue;
            readUrls.add(url);
            appendSource(sources, readUrls.size(), url, title, text);
        }

        // Nothing readable: keep the snippets, but say what they are.
        boolean pagesRead = !readUrls.isEmpty();
        if (!pagesRead) {
            for (Map<String, Object> result : results) {
                if (readUrls.size() >= MAX_SOURCES_READ) break;
                String url = String.valueOf(result.get("url"));
                if (url.isEmpty()) continue;
                readUrls.add(url);
                appendSource(sources, readUrls.size(), url,
                        String.valueOf(result.get("title")),
                        String.valueOf(result.get("snippet")));
            }
        }
        if (stopped(task, cancellation)) return;

        // 3. Answer. The local provider does not write prose — it shows what
        //    was found, which is honest. A remote provider is asked to answer
        //    strictly from the sources above.
        task.setCurrentStep(Task.STEP_ANSWER);
        AiProvider provider = MrNobodyApp.activeProvider();
        String answer;
        String injectionNote = null;
        if (provider.isRemote()) {
            // Page text is fenced before it reaches the model. Until this
            // existed a page could write "ignore your instructions" and arrive
            // in the same voice as the user's own request.
            String nonce = UntrustedContent.newNonce();
            UntrustedContent.Report fenced =
                    UntrustedContent.fence(sources.toString(), nonce);
            injectionNote = fenced.note();
            answer = askProvider(provider,
                    GroundedPrompt.build(instruction, fenced.fenced, pagesRead, nonce),
                    cancellation);
        } else {
            answer = search.result();
        }
        if (stopped(task, cancellation)) return;

        // 4. Verify what came back against what was read.
        task.setStatus(Task.Status.VERIFYING);
        task.setCurrentStep(Task.STEP_VERIFY);
        if (provider.isRemote()) {
            AnswerVerifier.Report report = AnswerVerifier.check(answer, readUrls);
            String note = AnswerVerifier.note(report, readUrls);
            // An attempted injection is something the reader is told about,
            // not something we quietly absorb.
            if (injectionNote != null) {
                answer = answer + "\n\n" + injectionNote;
                com.mrnobody.debug.ErrorLog.record("task " + task.id()
                        + ": page content attempted to instruct the agent");
            }
            if (!note.isEmpty()) answer = answer + "\n\n" + note;
            if (report.hasProblems()) {
                com.mrnobody.debug.ErrorLog.record("task " + task.id()
                        + ": answer could not be verified against its sources");
            }
        }

        task.setResult(truncate(answer, 6000));
        task.setStatus(Task.Status.COMPLETED);
    }

    /**
     * Run a single routed tool call.
     *
     * <p>Deliberately not folded into the cascade. An action either happened or
     * it did not, and dressing that up as a four-step research narrative would
     * report progress the task is not making.
     */
    private void runRoutedAction(Context context, Task task, ToolRouter.Route route,
                                 Cancellation cancellation) {
        task.setCurrentStep(Task.STEP_ACT);
        ToolResult result = callTool(context, route.tool, route.request, cancellation);
        if (stopped(task, cancellation)) return;

        if (!result.isSuccess()) {
            fail(task, result);
            return;
        }

        String rendered = result.result();
        task.setResult(truncate(rendered == null || rendered.isEmpty()
                ? route.tool + " finished."
                : rendered, 6000));
        task.setStatus(Task.Status.COMPLETED);
    }

    /** Fetch one page's readable text through the pipeline; empty on failure. */
    private String readPage(Context context, String url, Cancellation cancellation) {
        ToolResult page = callTool(context, "http", ToolRequest.of("fetch", "url", url), cancellation);
        if (!page.isSuccess()) return "";
        String text = page.result();
        return text == null ? "" : text.trim();
    }

    private static void appendSource(StringBuilder sources, int number, String url,
                                     String title, String text) {
        sources.append("\n[").append(number).append("] ").append(title)
                .append("\n").append(url).append("\n")
                .append(truncate(text, PER_SOURCE_CHARS)).append("\n");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resultsOf(ToolResult search) {
        Object rows = search.value().get("results");
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows instanceof List) {
            for (Object row : (List<Object>) rows) {
                if (row instanceof Map) out.add((Map<String, Object>) row);
            }
        }
        return out;
    }


    /**
     * The single tool entry point (see {@link AgentEngine#callTool}): resolve
     * the tool, then hand the call to the pipeline, which owns validation,
     * policy, guards, confirmation, timeouts, output checking and the record.
     */
    @Override
    public ToolResult callTool(Context context, String name, ToolRequest request) {
        return callTool(context, name, request, Cancellation.NONE);
    }

    /** As above, but able to abandon a slow tool when the task is cancelled. */
    public ToolResult callTool(Context context, String name, ToolRequest request,
                               Cancellation cancellation) {
        Tool tool = tools.get(name);
        if (tool == null) return ToolResult.fail("no tool named " + name);
        return pipeline.run(context, tool, request, cancellation);
    }

    /** The pipeline, so the host can attach a confirmer and a recorder to it. */
    public ToolPipeline pipeline() {
        return pipeline;
    }

    /** Mark the task cancelled and stop, if a cancel request is outstanding. */
    private boolean stopped(Task task, Cancellation cancellation) {
        if (cancellation == null || !cancellation.isCancelled()) return false;
        task.setCurrentStep("");
        task.setStatus(Task.Status.CANCELLED);
        return true;
    }

    private void fail(Task task, ToolResult r) {
        task.setError(r.error());
        task.setStatus(Task.Status.FAILED);
        // The debug overlay is the only channel a user has for reporting a
        // failure — a task that fails silently there is a failure we never see.
        com.mrnobody.debug.ErrorLog.record("task " + task.id() + " failed: " + r.error());
    }

    /**
     * The site the user named, as something fetchable.
     *
     * <p>A scheme is used as written. A bare hostname is not: "download it
     * from nkiri.ink" named a site the agent never opened, because this only
     * recognised {@code http://} and {@code https://}. The task then answered
     * from three pages the user had not asked about, and reported nothing
     * wrong — the one page they specified was the one page never read.
     */
    static String findUrl(String text) {
        Matcher m = URL_IN_TEXT.matcher(text);
        if (m.find()) return m.group(1);

        String host = Hosts.firstIn(text);
        return host == null ? null : "https://" + host;
    }

    /**
     * Wait for a provider, in short slices so a cancel request is noticed in
     * under a second rather than after the whole timeout. A blocking
     * {@code await(90s)} here is what made "cancel" mean "cancel, eventually".
     */
    private String askProvider(AiProvider provider, String prompt, Cancellation cancellation) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = {"(no AI response)"};
        try {
            provider.complete(SYSTEM_PROMPT, prompt, new AiProvider.CompletionCallback() {
                @Override public void onResult(String text) { out[0] = text; latch.countDown(); }
                @Override public void onError(String error) {
                    out[0] = "AI error: " + error;
                    com.mrnobody.debug.ErrorLog.record("AI provider: " + error);
                    latch.countDown();
                }
            });
            long deadline = System.currentTimeMillis() + PROVIDER_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (latch.await(POLL_MS, TimeUnit.MILLISECONDS)) return out[0];
                if (cancellation != null && cancellation.isCancelled()) return "(cancelled)";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
