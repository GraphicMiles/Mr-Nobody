package com.mrnobody.browser;

import android.app.Application;
import android.content.Context;
import android.webkit.WebView;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.ai.GeminiProvider;
import com.mrnobody.agent.ai.GroqProvider;
import com.mrnobody.agent.ai.LocalProvider;
import com.mrnobody.agent.ai.OpenAiCompatibleProvider;
import com.mrnobody.agent.browser.HeadlessWebViewEngine;
import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.dispatcher.LocalWorker;
import com.mrnobody.agent.dispatcher.RemoteWorker;
import com.mrnobody.agent.dispatcher.TaskDispatcher;
import com.mrnobody.agent.planner.DeterministicEngine;
import com.mrnobody.agent.tasks.TaskStore;
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
import com.mrnobody.browser.core.PrivacyReport;
import com.mrnobody.browser.core.Settings;
import com.mrnobody.browser.history.HistoryStore;

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
    private static TaskDispatcher taskDispatcher;
    private static TaskScheduler taskScheduler;
    private static HeadlessWebViewEngine headlessEngine;
    private static String activeAiProviderId = "local";

    // Provider ids in display order (instances are built on demand so a key
    // entered in Settings takes effect immediately).
    public static final List<String> PROVIDER_IDS = Arrays.asList(
            "local", "gemini", "groq", "openai-compatible");

    @Override
    public void onCreate() {
        super.onCreate();

        filterEngine = new FilterEngine();
        filterEngine.loadBundled(this);
        settings = new Settings(this);
        historyStore = new HistoryStore(this);
        bookmarksStore = new BookmarksStore(this);
        privacyReport = new PrivacyReport(this);
        permissionStore = new PermissionStore(this);
        perSiteSettings = new PerSiteSettings(this);

        historyStore.setEnabled(settings.isHistoryEnabled());
        activeAiProviderId = settings.activeAiProvider();

        // Agent stack (V1 = deterministic + local worker + headless WebView).
        DeterministicEngine engine = new DeterministicEngine();
        headlessEngine = new HeadlessWebViewEngine(this);
        engine.registerTool(new BrowserTool(headlessEngine));
        engine.registerTool(new DownloadTool());
        agentEngine = engine;
        applyTerminalSetting();

        TaskNotifier.ensureChannel(this);

        taskStore = new TaskStore(this);
        taskDispatcher = new TaskDispatcher("local");
        taskDispatcher.register(new LocalWorker(agentEngine));
        taskDispatcher.register(new RemoteWorker()); // no-op until V2 enables it
        taskScheduler = new WorkManagerTaskScheduler();
    }

    public static FilterEngine filters() { return filterEngine; }
    public static Settings settings() { return settings; }
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
    public static TaskDispatcher dispatcher() { return taskDispatcher; }
    public static TaskScheduler scheduler() { return taskScheduler; }
    public static HeadlessWebViewEngine headlessEngine() { return headlessEngine; }

    // ------------------------------------------------------------ AI providers

    /** Build the provider for an id, reading the current key/base/model from settings. */
    public static AiProvider buildProvider(String id) {
        switch (id) {
            case "gemini":
                return new GeminiProvider(settings.apiBase("gemini"),
                        settings.apiModel("gemini"), settings.apiKey("gemini"));
            case "groq":
                return new GroqProvider(settings.apiBase("groq"),
                        settings.apiModel("groq"), settings.apiKey("groq"));
            case "openai-compatible":
                return new OpenAiCompatibleProvider(
                        "openai-compatible", "OpenAI-compatible",
                        settings.apiBase("openai"), settings.apiModel("openai"),
                        settings.apiKey("openai"));
            case "local":
            default:
                return new LocalProvider();
        }
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
