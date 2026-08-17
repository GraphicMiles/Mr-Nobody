package com.mrnobody.browser;

import android.app.Application;
import android.content.Context;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.ai.GeminiProvider;
import com.mrnobody.agent.ai.GroqProvider;
import com.mrnobody.agent.ai.LocalProvider;
import com.mrnobody.agent.ai.OpenAiCompatibleProvider;
import com.mrnobody.agent.core.AgentEngine;
import com.mrnobody.agent.dispatcher.LocalWorker;
import com.mrnobody.agent.dispatcher.RemoteWorker;
import com.mrnobody.agent.dispatcher.TaskDispatcher;
import com.mrnobody.agent.planner.DeterministicEngine;
import com.mrnobody.agent.tasks.TaskStore;
import com.mrnobody.blocking.FilterEngine;
import com.mrnobody.browser.core.BookmarksStore;
import com.mrnobody.browser.core.PerSiteSettings;
import com.mrnobody.browser.core.PermissionStore;
import com.mrnobody.browser.core.PrivacyReport;
import com.mrnobody.browser.core.Settings;
import com.mrnobody.browser.history.HistoryStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application entry point. Boots the long-lived singletons once per process,
 * including the V1 agent stack (deterministic engine + local worker) behind
 * interfaces that V2 will deepen without a rewrite.
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
    private static final Map<String, AiProvider> aiProviders = new LinkedHashMap<>();
    private static String activeAiProviderId = "local";

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

        // Agent stack (V1 = deterministic + local worker; V2 = LLM + remote).
        agentEngine = new DeterministicEngine();
        taskStore = new TaskStore(this);
        taskDispatcher = new TaskDispatcher("local");
        taskDispatcher.register(new LocalWorker(agentEngine));
        taskDispatcher.register(new RemoteWorker()); // no-op until V2 enables it

        // AI providers (all optional/opt-in except Local).
        registerAiProvider(new LocalProvider());
        registerAiProvider(new GeminiProvider(settings.apiKey("gemini")));
        registerAiProvider(new GroqProvider(settings.apiKey("groq")));
        registerAiProvider(new OpenAiCompatibleProvider(
                "openai-compatible", "OpenAI-compatible",
                settings.apiBase("openai"), settings.apiModel("openai"),
                settings.apiKey("openai")));
    }

    private void registerAiProvider(AiProvider provider) {
        aiProviders.put(provider.id(), provider);
    }

    public static FilterEngine filters() { return filterEngine; }
    public static Settings settings() { return settings; }
    public static HistoryStore history() { return historyStore; }
    public static BookmarksStore bookmarks() { return bookmarksStore; }
    public static PrivacyReport report() { return privacyReport; }
    public static PermissionStore permissions() { return permissionStore; }
    public static PerSiteSettings perSite() { return perSiteSettings; }

    public static AgentEngine agent() { return agentEngine; }
    public static TaskStore tasks() { return taskStore; }
    public static TaskDispatcher dispatcher() { return taskDispatcher; }

    public static AiProvider aiProvider(String id) {
        return aiProviders.getOrDefault(id, aiProviders.get("local"));
    }

    public static List<AiProvider> aiProviders() {
        return new ArrayList<>(aiProviders.values());
    }

    public static String activeAiProviderId() { return activeAiProviderId; }
    public static void setActiveAiProviderId(String id) { activeAiProviderId = id; }

    public static MrNobodyApp app(Context context) {
        return (MrNobodyApp) context.getApplicationContext();
    }
}
