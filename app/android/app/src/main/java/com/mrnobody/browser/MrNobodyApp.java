package com.mrnobody.browser;

import android.app.Application;
import android.content.Context;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.ai.GeminiProvider;
import com.mrnobody.agent.ai.GroqProvider;
import com.mrnobody.agent.ai.LocalProvider;
import com.mrnobody.agent.ai.OpenAiCompatibleProvider;
import com.mrnobody.agent.ai.FallbackAiProvider;
import com.mrnobody.agent.ai.ProviderSnapshot;
import com.mrnobody.agent.browser.AccountStore;
import com.mrnobody.agent.browser.HeadlessSessions;
import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.core.AgentRunContext;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.design.DesignPlatformAdapter;
import com.mrnobody.agent.design.DesignSessionStore;
import com.mrnobody.agent.design.UnavailableDesignAdapter;
import com.mrnobody.agent.dispatcher.LocalWorker;
import com.mrnobody.agent.dispatcher.RemoteWorker;
import com.mrnobody.agent.dispatcher.TaskDispatcher;
import com.mrnobody.agent.execution.ExecutionLedger;
import com.mrnobody.agent.execution.SqliteExecutionLedger;
import com.mrnobody.agent.jobs.AsyncJobAdapterRegistry;
import com.mrnobody.agent.jobs.AsyncJobCoordinator;
import com.mrnobody.agent.jobs.AsyncJobStore;
import com.mrnobody.agent.jobs.SqliteAsyncJobStore;
import com.mrnobody.agent.jobs.WorkManagerAsyncJobScheduler;
import com.mrnobody.agent.mcp.CanvaMcpDesignAdapter;
import com.mrnobody.agent.mcp.CanvaOAuthManager;
import com.mrnobody.agent.planner.DeterministicEngine;
import com.mrnobody.agent.skills.SkillRegistry;
import com.mrnobody.agent.tasks.TaskReconciler;
import com.mrnobody.agent.tasks.TaskStore;
import com.mrnobody.agent.tasks.TaskEventStore;
import com.mrnobody.agent.tasks.EventLogRecorder;
import com.mrnobody.agent.policy.ApprovalMode;
import com.mrnobody.agent.policy.ApprovalPolicy;
import com.mrnobody.agent.tasks.TaskScheduler;
import com.mrnobody.agent.tasks.WorkManagerTaskScheduler;
import com.mrnobody.agent.policy.PolicyGate;
import com.mrnobody.agent.tools.BrowserTool;
import com.mrnobody.agent.tools.DesignTool;
import com.mrnobody.agent.tools.DownloadTool;
import com.mrnobody.agent.tools.TerminalTool;
import com.mrnobody.browser.blocking.FilterEngine;
import com.mrnobody.browser.core.BookmarksStore;
import com.mrnobody.browser.core.PerSiteSettings;
import com.mrnobody.browser.core.PrivacyProfile;
import com.mrnobody.browser.core.PrivacyReport;
import com.mrnobody.browser.core.Settings;
import com.mrnobody.browser.net.PrivacyController;
import com.mrnobody.browser.net.PrivacyMode;
import com.mrnobody.browser.history.HistoryStore;
import com.mrnobody.debug.ErrorLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Application entry point. Boots the long-lived singletons once per process,
 * including the V1 agent stack (deterministic engine + local worker + headless
 * browser) behind interfaces that V2 will deepen without a rewrite.
 *
 * No analytics, no advertising SDK, no network call at startup. The only I/O is
 * loading the bundled filter list from assets.
 */
public final class MrNobodyApp extends Application {

    /** The live Application, for static paths that need a Context (embedded Tor). */
    private static volatile MrNobodyApp appInstance;

    private static FilterEngine filterEngine;
    private static Settings settings;
    private static HistoryStore historyStore;
    private static BookmarksStore bookmarksStore;
    private static PrivacyReport privacyReport;
    private static PerSiteSettings perSiteSettings;
    private static AccountStore accountStore;

    // Agent stack
    private static AgentEngine agentEngine;
    private static TaskStore taskStore;
    private static TaskEventStore taskEvents;
    private static ExecutionLedger executionLedger;
    private static AsyncJobStore asyncJobs;
    private static AsyncJobAdapterRegistry asyncJobAdapters;
    private static AsyncJobCoordinator asyncJobCoordinator;
    private static DesignSessionStore designSessions;
    private static volatile DesignPlatformAdapter designAdapter =
            new UnavailableDesignAdapter("Canva MCP is not configured.");
    private static CanvaOAuthManager canvaOAuth;
    private static ApprovalPolicy.MapOverrides approvalOverrides;
    private static TaskDispatcher taskDispatcher;
    private static TaskScheduler taskScheduler;
    private static String activeAiProviderId = "local";

    // Provider ids in display order (instances are built on demand so a key
    // entered in Settings takes effect immediately).
    public static final List<String> PROVIDER_IDS = Arrays.asList(
            "local", "gemini", "groq", "openai-compatible");

    @Override
    public void onCreate() {
        super.onCreate();
        appInstance = this;

        // Catch native crashes before anything else can. A Java uncaught
        // exception or OOM takes the process down silently; the handler writes
        // the stack to disk so the next launch can surface it in the debug ⓘ
        // panel instead of leaving the user with a mystery.
        com.mrnobody.debug.CrashLog.install(this);
        String prior = com.mrnobody.debug.CrashLog.read(this);
        if (prior != null) {
            com.mrnobody.debug.ErrorLog.record("last crash: " + prior.trim());
            com.mrnobody.debug.CrashLog.clear(this);
        }

        filterEngine = new FilterEngine();
        filterEngine.loadBundled(this);
        settings = new Settings(this);
        // The blocking toggle must reach the engine's own enable flag, or the
        // setting is a label the engine never reads.
        filterEngine.setEnabled(settings.isBlockingEnabled());
        historyStore = new HistoryStore(this);
        bookmarksStore = new BookmarksStore(this);
        privacyReport = new PrivacyReport(this);
        perSiteSettings = new PerSiteSettings(this);
        accountStore = new AccountStore(this);

        historyStore.setEnabled(settings.isHistoryEnabled());

        // Finish profile deletions a previous process owed. At this point no
        // WebView has bound a profile, so a deletion that was refused as
        // "in use" last session is guaranteed removable now. No-op when
        // nothing is owed.
        try {
            com.mrnobody.browser.net.ProfileManager.sweepAtStartup(this);
        } catch (Throwable t) {
            com.mrnobody.debug.ErrorLog.record("profile startup sweep failed: " + t);
        }

        // Downloads are ours to carry now, so a process death leaves rows that
        // claim to be running when nothing is. Park them as stalled so the user
        // gets a Resume button instead of a progress bar frozen forever.
        try {
            com.mrnobody.browser.download.DownloadEngine.get(this).reconcile();
        } catch (Throwable t) {
            com.mrnobody.debug.ErrorLog.record("download reconcile failed: " + t);
        }
        activeAiProviderId = settings.activeAiProvider();
        canvaOAuth = new CanvaOAuthManager(this);
        designAdapter = new CanvaMcpDesignAdapter(this, canvaOAuth);

        // Restore the privacy mode before anything can open a socket. If the
        // saved mode was NOBODY and its route is gone, PrivacyController
        // refuses it and drops to NORMAL rather than starting the app in a
        // state that claims protection it does not have.
        try {
            PrivacyController.apply(
                    PrivacyMode.fromName(settings.privacyMode()), settings, this);
        } catch (Throwable t2) {
            com.mrnobody.debug.ErrorLog.record("privacy mode restore failed: " + t2);
        }

        // Agent stack (V1 = deterministic + local worker + headless WebView).
        DeterministicEngine engine = new DeterministicEngine();
        HeadlessSessions.init(this);
        // One engine per task, resolved at call time from the bound task id.
        // A shared instance here is how two tasks inherited each other's cookies.
        engine.registerTool(new BrowserTool(HeadlessSessions::current));
        // Search can escalate into the headless browser when a provider
        // answers a plain fetch with a challenge page.
        engine.registerTool(new com.mrnobody.agent.tools.SearchTool(HeadlessSessions::current));
        engine.registerTool(new DownloadTool());
        engine.registerTool(new DesignTool(MrNobodyApp::designAdapter,
                MrNobodyApp::designSessions));
        // Long-term retrieval is not registered until its opt-in storage policy
        // is wired end to end. Task history remains visible/erasable in Memory,
        // but a planner cannot invoke a capability outside its actual scope.
        agentEngine = engine;
        applyTerminalSetting();

        TaskNotifier.ensureChannel(this);

        taskStore = new TaskStore(this);
        taskEvents = new TaskEventStore(this);
        executionLedger = new SqliteExecutionLedger(this);
        asyncJobs = new SqliteAsyncJobStore(this);
        asyncJobAdapters = new AsyncJobAdapterRegistry();
        asyncJobCoordinator = new AsyncJobCoordinator(asyncJobs, executionLedger,
                new WorkManagerAsyncJobScheduler());
        designSessions = new DesignSessionStore(this);

        // Attach the pipeline seams: user-facing audit, durable execution
        // authority, approval policy, and confirmation UI.
        if (agentEngine instanceof DeterministicEngine) {
            DeterministicEngine de = (DeterministicEngine) agentEngine;
            de.pipeline().setRecorder(new EventLogRecorder(taskEvents));
            de.pipeline().setLedger(executionLedger);
            approvalOverrides = new ApprovalPolicy.MapOverrides();
            // The prompt writes "always allow" here and the policy reads it
            // here: one instance, or the choice is recorded and ignored.
            de.policy().setOverrides(approvalOverrides);
            de.pipeline().setConfirmer(new ApprovalPrompt(approvalOverrides));
            de.policy().setMode(ApprovalMode.fromName(settings.approvalMode()));
        }
        taskDispatcher = new TaskDispatcher("local");
        taskDispatcher.register(new LocalWorker(agentEngine, executionLedger));
        // Remote execution is opt-in: the worker fails honestly until a server
        // URL is configured, and local remains the default.
        taskDispatcher.register(new RemoteWorker(() -> settings.remoteServer()));
        taskScheduler = new WorkManagerTaskScheduler();

        // Android can stop the process mid-task and nothing writes the ending,
        // so a row can claim RUNNING forever. Close those out on the way up.
        // After the store exists, and off the main thread: this touches SQLite.
        final TaskStore store = taskStore;
        new Thread(() -> {
            try {
                int closed = store.reconcileStale(TaskReconciler.DEFAULT_STALE_AFTER_MS);
                if (closed > 0) {
                    ErrorLog.record("Reconciled " + closed + " interrupted task(s) at startup");
                }
                // A crash between TaskStore.submit and WorkManager enqueue
                // leaves a durable QUEUED outbox row. Unique work makes this
                // safe to replay and closes that scheduling gap.
                for (com.mrnobody.agent.core.Task queued : store.queued()) {
                    taskScheduler.schedule(MrNobodyApp.this, queued.id());
                }
                WorkManagerAsyncJobScheduler jobScheduler = new WorkManagerAsyncJobScheduler();
                for (com.mrnobody.agent.jobs.AsyncJob job : asyncJobs.pending()) {
                    jobScheduler.schedule(MrNobodyApp.this, job);
                }
            } catch (Exception e) {
                ErrorLog.record("Task reconciliation failed: " + e);
            }
        }, "task-reconcile").start();
    }

    public static FilterEngine filters() { return filterEngine; }
    public static Settings settings() { return settings; }

    /** Apply a privacy mode and persist it only if it actually took effect. */
    public static PrivacyController.Result applyPrivacyMode(PrivacyMode mode) {
        PrivacyController.Result r = PrivacyController.apply(mode, settings, appInstance);
        // Persist what was achieved, not what was asked for: a refused mode
        // must not come back on the next launch.
        settings.setPrivacyMode(r.effective.name());
        return r;
    }

    /**
     * Apply a privacy profile's defaults and push them onto the live engines.
     * Selecting a profile that only stored its name was a lock icon on an
     * unlocked door.
     */
    public static void applyPrivacyProfile(PrivacyProfile profile) {
        if (profile == null) profile = PrivacyProfile.BALANCED;
        profile.apply(settings);
        settings.setProfile(profile);
        if (filterEngine != null) filterEngine.setEnabled(settings.isBlockingEnabled());
        if (historyStore != null) historyStore.setEnabled(settings.isHistoryEnabled());
        // Nobody's fingerprint claim outranks the profile: leaving it off
        // after picking Balanced while Nobody is live would strip the
        // identification half of that mode.
        if (PrivacyController.current().needsFingerprintDefence()) {
            settings.setFingerprintProtection(true);
        }
    }
    public static HistoryStore history() { return historyStore; }
    public static BookmarksStore bookmarks() { return bookmarksStore; }
    public static PrivacyReport report() { return privacyReport; }
    public static PerSiteSettings perSite() { return perSiteSettings; }
    public static AccountStore accounts() { return accountStore; }

    public static AgentEngine agent() { return agentEngine; }

    /**
     * The terminal tool only exists while the user has it switched on. It is
     * not merely hidden: an agent cannot call a tool that was never registered
     * (V1 §9 — feature-flagged until proven necessary).
     */
    public static void applyTerminalSetting() {
        if (!(agentEngine instanceof DeterministicEngine)) return;
        DeterministicEngine engine = (DeterministicEngine) agentEngine;
        if (settings.isTerminalEnabled()) {
            if (!engine.hasTool("terminal")) engine.registerTool(new TerminalTool(new PolicyGate()));
        } else {
            engine.unregisterTool("terminal");
        }
    }
    public static TaskStore tasks() { return taskStore; }
    public static TaskEventStore taskEvents() { return taskEvents; }
    public static ExecutionLedger executionLedger() { return executionLedger; }
    public static AsyncJobStore asyncJobs() { return asyncJobs; }
    public static AsyncJobAdapterRegistry asyncJobAdapters() { return asyncJobAdapters; }
    public static AsyncJobCoordinator asyncJobCoordinator() { return asyncJobCoordinator; }
    public static DesignSessionStore designSessions() { return designSessions; }
    public static DesignPlatformAdapter designAdapter() { return designAdapter; }
    public static CanvaOAuthManager canvaOAuth() { return canvaOAuth; }
    public static void setDesignAdapter(DesignPlatformAdapter adapter) {
        designAdapter = adapter == null
                ? new UnavailableDesignAdapter("Design platform is unavailable.") : adapter;
    }
    public static ApprovalPolicy.MapOverrides approvalOverrides() { return approvalOverrides; }

    /** Change how often the agent stops to ask, and remember it. */
    public static void setApprovalMode(ApprovalMode mode) {
        settings.setApprovalMode(mode.name());
        if (agentEngine instanceof DeterministicEngine) {
            ((DeterministicEngine) agentEngine).policy().setMode(mode);
        }
    }
    public static TaskDispatcher dispatcher() { return taskDispatcher; }
    public static TaskScheduler scheduler() { return taskScheduler; }

    // ------------------------------------------------------------ AI providers

    /**
     * Freeze provider/model/base and the explicitly consented fallback order
     * before a run's first call. A retry reloads these values from the task row,
     * never from settings changed halfway through the run.
     */
    public static AgentRunContext createRunContext(Task task) {
        ProviderSnapshot primary;
        List<ProviderSnapshot> fallbacks = new ArrayList<>();
        if (task.providerSnapshot().isEmpty()) {
            String id = activeAiProviderId;
            primary = snapshot(id);
            task.setProviderSnapshot(primary.encode());
            if (settings.hasAiFallbackConsent()) {
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                seen.add(primary.id);
                for (String candidate : settings.aiFallbackProviders().split(",")) {
                    String fallbackId = candidate == null ? "" : candidate.trim();
                    if (fallbackId.isEmpty() || "local".equals(fallbackId)
                            || !PROVIDER_IDS.contains(fallbackId) || !seen.add(fallbackId)) continue;
                    fallbacks.add(snapshot(fallbackId));
                }
            }
            task.setFallbackProviderSnapshots(encodeSnapshots(fallbacks));
        } else {
            primary = ProviderSnapshot.decode(task.providerSnapshot());
            fallbacks.addAll(decodeSnapshots(task.fallbackProviderSnapshots()));
        }
        if (task.executionPlatform().isEmpty()) {
            boolean continuingDesign = false;
            try { continuingDesign = designSessions != null
                    && designSessions.findByTask(task.id()) != null; }
            catch (Throwable ignored) { }
            task.setExecutionPlatform(continuingDesign ? "canva-mcp"
                    : SkillRegistry.standard().route(task.activeInstruction()).executionPlatform);
        }

        AiProvider provider = providerFrom(primary);
        if (!primary.isLocal() && !fallbacks.isEmpty()) {
            List<AiProvider> chain = new ArrayList<>();
            chain.add(provider);
            for (ProviderSnapshot fallback : fallbacks) chain.add(providerFrom(fallback));
            provider = new FallbackAiProvider(chain);
        }
        return new AgentRunContext(task.id(), task.runId(), primary, fallbacks,
                provider, task.executionPlatform());
    }

    private static ProviderSnapshot snapshot(String id) {
        String safe = id == null || !PROVIDER_IDS.contains(id) ? "local" : id;
        String key = storageKey(safe);
        return new ProviderSnapshot(safe,
                "local".equals(safe) ? "" : settings.apiBase(key),
                "local".equals(safe) ? "" : settings.apiModel(key));
    }

    private static AiProvider providerFrom(ProviderSnapshot snapshot) {
        if (snapshot == null || snapshot.isLocal()) return new LocalProvider();
        String key = settings.apiKey(storageKey(snapshot.id));
        return buildProvider(snapshot.id, snapshot.baseUrl, snapshot.modelId, key);
    }

    private static String encodeSnapshots(List<ProviderSnapshot> snapshots) {
        StringBuilder out = new StringBuilder();
        if (snapshots != null) {
            for (ProviderSnapshot snapshot : snapshots) {
                if (snapshot == null) continue;
                if (out.length() > 0) out.append('\n');
                out.append(snapshot.encode());
            }
        }
        return out.toString();
    }

    private static List<ProviderSnapshot> decodeSnapshots(String encoded) {
        List<ProviderSnapshot> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return out;
        for (String line : encoded.split("\\n")) {
            if (!line.trim().isEmpty()) out.add(ProviderSnapshot.decode(line));
        }
        return out;
    }

    /** Build the provider for an id, reading the current key/base/model from settings. */
    public static AiProvider buildProvider(String id) {
        String key = settings.apiKey(storageKey(id));
        return buildProvider(id, settings.apiBase(storageKey(id)), settings.apiModel(storageKey(id)), key);
    }

    /**
     * Build a provider from explicit values — used to probe a configuration
     * (list its models) before the user commits it.
     */
    public static AiProvider buildProvider(String id, String base, String model, String key) {
        switch (id) {
            case "gemini":
                return new GeminiProvider(base, model, key);
            case "groq":
                return new GroqProvider(base, model, key);
            case "openai":
            case "openai-compatible":
                return new OpenAiCompatibleProvider(
                        "openai-compatible", "OpenAI-compatible", base, model, key);
            case "local":
            default:
                return new LocalProvider();
        }
    }

    /** Settings store the generic gateway under "openai"; the UI calls it that too. */
    private static String storageKey(String providerId) {
        return "openai-compatible".equals(providerId) ? "openai" : providerId;
    }

    public static String providerDisplayName(String id) {
        return buildProvider(id).displayName();
    }

    public static AiProvider activeProvider() {
        // Before onCreate has run (or on the JVM test harness, where it never
        // runs) there are no settings to read; local is the only honest answer.
        if (settings == null) return new LocalProvider();
        return buildProvider(activeAiProviderId);
    }

    public static String activeAiProviderId() { return activeAiProviderId; }

    public static void setActiveAiProviderId(String id) {
        activeAiProviderId = id;
        settings.setActiveAiProvider(id);
    }

    public static List<String> providerIds() {
        return new ArrayList<>(PROVIDER_IDS);
    }

    public static MrNobodyApp app(Context context) {
        return (MrNobodyApp) context.getApplicationContext();
    }
}
