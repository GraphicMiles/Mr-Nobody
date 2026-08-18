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
import com.mrnobody.agent.policy.BudgetGuard;
import com.mrnobody.agent.policy.RepeatCallGuard;
import com.mrnobody.agent.tools.HttpTool;
import com.mrnobody.agent.tools.SearchTool;
import com.mrnobody.agent.util.Hosts;
import com.mrnobody.agent.tasks.Schedule;
import com.mrnobody.agent.tasks.TaskStreamHub;
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

    /**
     * A ceiling on work performed, not just on work repeated. The repeat guard
     * says nothing about a task making forty different calls, which a plan
     * that can extend itself is entirely capable of.
     */
    private final BudgetGuard budgetGuard = new BudgetGuard();

    private final ToolPipeline pipeline =
            new ToolPipeline(policy).addGuard(repeatGuard).addGuard(budgetGuard);

    /**
     * Produces the plan for an instruction. A local provider uses
     * {@link DeterministicPlanner} (the research cascade, or a one-step routed
     * action); a remote provider uses {@link LlmPlanner}, which asks the model
     * for a multi-step plan and falls back to the deterministic cascade on any
     * parse or validation failure. Chosen per run in {@link #run}, so the
     * engine never learns which kind it is running.
     */
    private Planner planner = new DeterministicPlanner();

    /**
     * How many source candidates the read phase will try. Larger than
     * {@link #MAX_SOURCES_READ} on purpose: a candidate that fails to read must
     * not consume the budget, so a later candidate is tried. Bounded by the
     * plan's own ceiling so the answer and verify steps always have room.
     */
    private static final int MAX_READ_CANDIDATES = 8;

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
        task.setStatus(Task.Status.RUNNING);

        // Budgets are per task, not per process: a fresh instruction starts
        // with a full allowance, and a previous task's spending is not
        // inherited by the next one.
        repeatGuard.reset();
        budgetGuard.reset();

        // Cancellation is observed between steps: the task stops in a state we
        // can describe, never halfway through one.
        if (stopped(task, cancellation)) return;

        // The plan comes from the planner, not from hard-coded control flow
        // here. A routed action is a one-step plan; a question is the research
        // cascade; a remote provider proposes a multi-step plan and falls back
        // to the cascade. Choosing a tool is still not the same as permitting
        // it — every tool step passes through the guarded pipeline.
        AiProvider provider = MrNobodyApp.activeProvider();
        planner = provider.isRemote() ? new LlmPlanner(provider) : new DeterministicPlanner();
        Plan plan = planner.plan(task.instruction(), tools.keySet());
        if (plan.size() == 0 || plan.isAbandoned()) {
            fail(task, ToolResult.fail("No plan for this instruction."));
            return;
        }

        if (isRoutedAction(plan)) {
            executeRouted(context, task, plan.steps().get(0), cancellation);
            return;
        }
        executeResearch(context, task, plan, cancellation);
    }

    /** A routed action is the whole task: one tool call whose result is the answer. */
    private static boolean isRoutedAction(Plan plan) {
        return plan.size() == 1 && Task.STEP_ACT.equals(plan.steps().get(0).label);
    }

    /**
     * Run a single routed tool call.
     *
     * <p>Deliberately not folded into the research loop. An action either
     * happened or it did not, and dressing that up as a four-step research
     * narrative would report progress the task is not making.
     */
    private void executeRouted(Context context, Task task, Plan.Step step,
                               Cancellation cancellation) {
        task.setCurrentStep(Task.STEP_ACT);
        ToolResult result = callTool(context, step.tool, step.request, cancellation);
        if (stopped(task, cancellation)) return;

        if (!result.isSuccess()) {
            fail(task, result);
            return;
        }

        String rendered = result.result();
        task.setResult(truncate(rendered == null || rendered.isEmpty()
                ? step.tool + " finished."
                : rendered, 6000));
        task.setStatus(Task.Status.COMPLETED);
    }

    /**
     * Execute the research plan: Search, then the reads the search discovers
     * (the plan grows here), then Answer, then Verify. The fixed cascade of
     * before is now the plan the {@link DeterministicPlanner} returns, so an
     * LLM planner can later return a plan that branches and replans without
     * this method changing.
     */
    private void executeResearch(Context context, Task task, Plan plan,
                                 Cancellation cancellation) {
        Research r = new Research();
        r.namedUrl = findUrl(task.instruction());

        while (!plan.isFinished()) {
            if (stopped(task, cancellation)) return;
            Plan.Step step = plan.current();

            if (step.isToolStep()) {
                task.setCurrentStep(step.label);
                ToolResult result = callTool(context, step.tool, step.request, cancellation);
                if ("search".equals(step.tool)) {
                    // Search is the anchor: an answer with no sources is a
                    // guess with a citation, so its failure ends the task
                    // rather than being replanned around.
                    r.search = result;
                    if (!result.isSuccess()) {
                        fail(task, result);
                        return;
                    }
                    r.results = resultsOf(result);
                    if (r.results.isEmpty()) {
                        fail(task, ToolResult.fail("The search returned nothing to read, so "
                                + "there is nothing to answer from."));
                        return;
                    }
                    plan.advance();
                    continue;
                }

                if ("http".equals(step.tool)) {
                    // A read is best-effort: a blocked source (503, timeout,
                    // bot challenge) must not fail the whole task. Escalate to
                    // the headless browser, which renders JS and looks like a
                    // real browser; if that fails too, skip this source and
                    // move on — the answer step falls back to whatever was
                    // actually read, plus the snippets.
                    boolean read = readOne(r, step, result);
                    if (!read) {
                        ToolResult escalated = readViaBrowser(context, step, cancellation);
                        if (escalated != null) {
                            readOne(r, step, escalated);
                        }
                    }
                    plan.advance();
                    continue;
                }

                // A failed action step can be replanned: a model-backed planner
                // proposes what to do instead; a deterministic one has nothing
                // to offer and the task fails as before.
                if (!result.isSuccess()) {
                    if (!replanAfterFailure(context, task, plan, step, result, cancellation)) {
                        fail(task, result);
                        return;
                    }
                }
                plan.advance();
                continue;
            }

            switch (step.label) {
                case Task.STEP_READ:
                    task.setCurrentStep(Task.STEP_READ);
                    planReads(plan, r);
                    break;
                case Task.STEP_ANSWER:
                    task.setCurrentStep(Task.STEP_ANSWER);
                    answerStep(context, task, cancellation, r);
                    break;
                case Task.STEP_VERIFY:
                    verifyStep(context, task, cancellation, r);
                    break;
                default:
                    break;
            }
            plan.advance();
        }
    }

    /**
     * Ask the planner for replacement steps after a failed action. Returns true
     * if replacement steps were added (the plan continues); false if the task
     * should fail. The plan's own ceiling still applies, so a replan cannot
     * make a task unbounded.
     */
    private boolean replanAfterFailure(Context context, Task task, Plan plan, Plan.Step step,
                                       ToolResult result, Cancellation cancellation) {
        Plan replan = planner.replan(plan, step, result.error(), tools.keySet());
        if (replan == null || replan.size() == 0) return false;
        // Insert in reverse so the replacement steps run next, in order.
        List<Plan.Step> replacements = replan.steps();
        for (int i = replacements.size() - 1; i >= 0; i--) {
            if (!plan.insertNext(replacements.get(i))) return false;
        }
        return true;
    }

    /**
     * Append one {@code http} step per source the search returned, so the plan
     * grows in response to what the search learned — the named page first,
     * then each result in order. The read steps are inserted to run before the
     * Answer and Verify steps that already follow.
     */
    private void planReads(Plan plan, Research r) {
        List<Plan.Step> reads = new ArrayList<>();
        if (r.namedUrl != null) {
            r.titles.put(r.namedUrl, "the page you named");
            reads.add(readStep(r.namedUrl));
        }
        for (Map<String, Object> result : r.results) {
            if (reads.size() >= MAX_READ_CANDIDATES) break;
            String url = String.valueOf(result.get("url"));
            if (url.isEmpty()) continue;
            r.titles.put(url, String.valueOf(result.get("title")));
            reads.add(readStep(url));
        }
        // insertNext always places after the current step, so inserting in
        // reverse yields the correct order: named page, then results.
        for (int i = reads.size() - 1; i >= 0; i--) {
            plan.insertNext(reads.get(i));
        }
    }

    /** A read step: fetch one URL as readable text. */
    private static Plan.Step readStep(String url) {
        return Plan.Step.tool("Read", "http", ToolRequest.of("fetch", "url", url),
                "read a source");
    }

    /** Record one source that actually read, or skip it silently when it failed. */
    private boolean readOne(Research r, Plan.Step step, ToolResult result) {
        String url = step.request == null ? "" : step.request.param("url", "");
        if (url.isEmpty() || r.readUrls.contains(url) || r.readUrls.size() >= MAX_SOURCES_READ) {
            return false;
        }
        String text = result.isSuccess() ? result.result() : null;
        if (text == null || text.trim().isEmpty()) return false;
        r.readUrls.add(url);
        // An explicit http step (e.g. from an LLM plan) has no search-result
        // title; the URL itself is the honest label.
        appendSource(r.sources, r.readUrls.size(), url, r.titles.getOrDefault(url, url), text);
        return true;
    }

    /**
     * Fetch one source through the headless browser, for when the plain HTTP
     * fetch was blocked. Returns null when there is no browser tool (so the
     * caller skips the source rather than failing).
     */
    private ToolResult readViaBrowser(Context context, Plan.Step step, Cancellation cancellation) {
        if (!tools.containsKey("browser")) return null;
        String url = step.request == null ? "" : step.request.param("url", "");
        if (url.isEmpty()) return null;
        return callTool(context, "browser", ToolRequest.of("fetch", "url", url), cancellation);
    }

    /** Answer, strictly from the sources read; fall back to snippets when none read. */
    private void answerStep(Context context, Task task, Cancellation cancellation, Research r) {
        // Nothing readable: keep the snippets, but say what they are. Frozen
        // before the fallback so "pages read" stays false when only snippets
        // exist and no read-time is claimed.
        r.pagesRead = !r.readUrls.isEmpty();
        if (!r.pagesRead && r.results != null) {
            for (Map<String, Object> result : r.results) {
                if (r.readUrls.size() >= MAX_SOURCES_READ) break;
                String url = String.valueOf(result.get("url"));
                if (url.isEmpty()) continue;
                r.readUrls.add(url);
                appendSource(r.sources, r.readUrls.size(), url,
                        String.valueOf(result.get("title")),
                        String.valueOf(result.get("snippet")));
            }
        }
        if (stopped(task, cancellation)) return;

        r.provider = MrNobodyApp.activeProvider();
        if (r.provider.isRemote()) {
            // Page text is fenced before it reaches the model. Until this
            // existed a page could write "ignore your instructions" and arrive
            // in the same voice as the user's own request.
            String nonce = UntrustedContent.newNonce();
            UntrustedContent.Report fenced = UntrustedContent.fence(r.sources.toString(), nonce);
            r.injectionNote = fenced.note();
            r.answer = askProvider(r.provider,
                    GroundedPrompt.build(task.instruction(), fenced.fenced, r.pagesRead, nonce),
                    cancellation, task.id());
        } else if (r.search != null) {
            r.answer = r.search.result();
        } else {
            // No search step ran (an LLM plan that never searched). The sources
            // that were read are the answer's substance.
            r.answer = r.sources.length() == 0
                    ? "(nothing to report)"
                    : "Read:\n" + r.sources.toString();
        }
    }

    /** Verify the answer against what was read, then close the task. */
    private void verifyStep(Context context, Task task, Cancellation cancellation, Research r) {
        task.setStatus(Task.Status.VERIFYING);
        task.setCurrentStep(Task.STEP_VERIFY);
        if (r.provider.isRemote()) {
            AnswerVerifier.Report report = AnswerVerifier.check(r.answer, r.readUrls);
            String note = AnswerVerifier.note(report, r.readUrls);

            // Citations and hostnames are the frame of an answer; the figures
            // are usually the answer itself. Asked for the Bitcoin price the
            // model once copied the 24h low and high correctly and invented
            // the headline price, and every check here passed because the
            // marker [1] was well-formed and the host had been read.
            FigureCheck.Report figures = FigureCheck.check(r.answer, r.sources.toString());
            String figureNote = FigureCheck.note(figures);
            // An attempted injection is something the reader is told about,
            // not something we quietly absorb.
            if (r.injectionNote != null) {
                r.answer = r.answer + "\n\n" + r.injectionNote;
                com.mrnobody.debug.ErrorLog.record("task " + task.id()
                        + ": page content attempted to instruct the agent");
            }
            if (!figureNote.isEmpty()) r.answer = r.answer + "\n\n" + figureNote;
            if (!note.isEmpty()) r.answer = r.answer + "\n\n" + note;
            if (report.hasProblems()) {
                com.mrnobody.debug.ErrorLog.record("task " + task.id()
                        + ": answer could not be verified against its sources");
            }
            if (figures.hasProblems()) {
                com.mrnobody.debug.ErrorLog.record("task " + task.id()
                        + ": " + figures.unsupported.size()
                        + " figure(s) not found in the sources read");
            }
        }

        // When the pages were read. A live figure with no read time is stale
        // the instant it is shown and gives no way to tell: the Bitcoin answer
        // looked equally authoritative an hour later, when it was wrong.
        if (r.pagesRead) {
            r.answer = r.answer + "\n\nRead at " + readTimeLabel() + ".";
        }

        // "Track the price" asks for something to be watched, not answered
        // once. The schedule machinery already existed; nothing was noticing
        // that the user had asked for it.
        RecurrenceRequest.Request recurrence = RecurrenceRequest.parse(task.instruction());
        if (recurrence.isRecurring()) {
            String note = applyRecurrence(context, task, recurrence);
            if (!note.isEmpty()) r.answer = r.answer + "\n\n" + note;
        }

        task.setResult(truncate(r.answer, 6000));
        task.setStatus(Task.Status.COMPLETED);
    }

    /** State shared across the steps of one research run. */
    private static final class Research {
        final StringBuilder sources = new StringBuilder();
        final List<String> readUrls = new ArrayList<>();
        final Map<String, String> titles = new LinkedHashMap<>();
        String namedUrl;
        ToolResult search;
        List<Map<String, Object>> results;
        AiProvider provider;
        boolean pagesRead;
        String answer = "";
        String injectionNote;
    }

    /** Local time, so "read at" means something to the person reading it. */
    private static String readTimeLabel() {
        return new java.text.SimpleDateFormat("HH:mm 'on' d MMM yyyy",
                java.util.Locale.getDefault()).format(new java.util.Date());
    }

    /**
     * Persist and enqueue a repeating schedule for this task.
     *
     * <p>Failure here must not fail the task: the user already has the answer
     * they asked for, and losing it because a scheduler refused would be a
     * worse outcome than not repeating. It is reported instead of thrown, so
     * nobody is told work is scheduled when it is not.
     */
    private String applyRecurrence(Context context, Task task,
                                   RecurrenceRequest.Request recurrence) {
        try {
            MrNobodyApp.tasks().setSchedule(task.id(), recurrence.repeat);
            MrNobodyApp.scheduler().scheduleRepeating(context, task.id(),
                    new Schedule(recurrence.repeat, System.currentTimeMillis()
                            + recurrence.repeat.intervalMs()));
            return recurrence.describe();
        } catch (Throwable e) {
            com.mrnobody.debug.ErrorLog.record("could not schedule repeat for task "
                    + task.id() + ": " + e);
            return "⚠︎ This looked like a request to keep checking, but the repeat "
                    + "could not be scheduled, so this answer is a one-off.";
        }
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
    private String askProvider(AiProvider provider, String prompt, Cancellation cancellation,
                               long taskId) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = {"(no AI response)"};
        try {
            // Stream, so the answer reaches the task chat as it is generated
            // rather than all at once at the end. The same callback path that
            // used to call complete() now forwards each token to the hub; the
            // final text still comes back whole for verification. A provider
            // that cannot stream falls back to one token, so nothing here
            // branches on capability.
            provider.stream(SYSTEM_PROMPT, prompt, new AiProvider.StreamCallback() {
                @Override public void onToken(String token) {
                    TaskStreamHub.instance().emitToken(taskId, token);
                }
                @Override public void onDone(String fullText) {
                    out[0] = fullText;
                    TaskStreamHub.instance().emitDone(taskId, fullText);
                    latch.countDown();
                }
                @Override public void onError(String error) {
                    out[0] = "AI error: " + error;
                    com.mrnobody.debug.ErrorLog.record("AI provider: " + error);
                    TaskStreamHub.instance().emitError(taskId, error);
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
