package com.mrnobody.browser;

import android.app.Application;
import android.content.Context;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.ai.GeminiProvider;
import com.mrnobody.agent.ai.GroqProvider;
import com.mrnobody.agent.ai.LocalProvider;
import com.mrnobody.agent.ai.OpenAiCompatibleProvider;
import com.mrnobody.agent.browser.HeadlessSessions;
import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.dispatcher.LocalWorker;
import com.mrnobody.agent.dispatcher.RemoteWorker;
import com.mrnobody.agent.dispatcher.TaskDispatcher;
import com.mrnobody.agent.planner.DeterministicEngine;
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
import com.mrnobody.agent.tools.DownloadTool;
import com.mrnobody.agent.tools.TerminalTool;
import com.mrnobody.browser.blocking.FilterEngine;
import com.mrnobody.browser.core.BookmarksStore;
import com.mrnobody.browser.core.PerSiteSettings;
import com.mrnobody.browser.core.PermissionStore;
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

    private static FilterEngine filterEngine;
    private static Settings settings;
    private static HistoryStore historyStore;
    private static BookmarksStore bookmarksStore;
    private static PrivacyReport privacyReport;
    private static PermissionStore permissionStore;
    private static PerSiteSettings perSiteSettings;

    // Agent stack
    private static AgentEngine agentEngine;
    private static TaskStore taskStore;
    private static TaskEventStore taskEvents;
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
        permissionStore = new PermissionStore(this);
        perSiteSettings = new PerSiteSettings(this);

        historyStore.setEnabled(settings.isHistoryEnabled());

        // Downloads are ours to carry now, so a process death leaves rows that
        // claim to be running when nothing is. Park them as stalled so the user
        // gets a Resume button instead of a progress bar frozen forever.
        try {
            com.mrnobody.browser.download.DownloadEngine.get(this).reconcile();
        } catch (Throwable t) {
            com.mrnobody.debug.ErrorLog.record("download reconcile failed: " + t);
        }
        activeAiProviderId = settings.activeAiProvider();

        // Restore the privacy mode before anything can open a socket. If the
        // saved mode was NOBODY and its route is gone, PrivacyController
        // refuses it and drops to NORMAL rather than starting the app in a
        // state that claims protection it does not have.
        try {
            PrivacyController.apply(
                    PrivacyMode.fromName(settings.privacyMode()), settings);
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
        // The agent's own memory: it can recall past tasks, on-device only.
        engine.registerTool(new com.mrnobody.agent.tools.MemoryTool());
        agentEngine = engine;
        applyTerminalSetting();

        TaskNotifier.ensureChannel(this);

        taskStore = new TaskStore(this);
        taskEvents = new TaskEventStore(this);

        // Attach the two pipeline seams that had never been connected. Until
        // now every tool call vanished on return, and every CONFIRM resolved
        // to a refusal because there was nothing able to ask.
        if (agentEngine instanceof DeterministicEngine) {
            DeterministicEngine de = (DeterministicEngine) agentEngine;
            de.pipeline().setRecorder(new EventLogRecorder(taskEvents));
            approvalOverrides = new ApprovalPolicy.MapOverrides();
            // The prompt writes "always allow" here and the policy reads it
            // here: one instance, or the choice is recorded and ignored.
            de.policy().setOverrides(approvalOverrides);
            de.pipeline().setConfirmer(new ApprovalPrompt(approvalOverrides));
            de.policy().setMode(ApprovalMode.fromName(settings.approvalMode()));
        }
        taskDispatcher = new TaskDispatcher("local");
        taskDispatcher.register(new LocalWorker(agentEngine));
        // Remote execution is opt-in: the worker fails honestly until a server
        // URL is configured, and local remains the default.
        taskDispatcher.register(new RemoteWorker(settings.remoteServer()));
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
            } catch (Exception e) {
                ErrorLog.record("Task reconciliation failed: " + e);
            }
        }, "task-reconcile").start();
    }

    public static FilterEngine filters() { return filterEngine; }
    public static Settings settings() { return settings; }

    /** Apply a privacy mode and persist it only if it actually took effect. */
    public static PrivacyController.Result applyPrivacyMode(PrivacyMode mode) {
        PrivacyController.Result r = PrivacyController.apply(mode, settings);
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
    public static PermissionStore permissions() { return permissionStore; }
    public static PerSiteSettings perSite() { return perSiteSettings; }

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
