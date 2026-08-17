package com.mrnobody.browser;

import android.app.Application;
import android.content.Context;

import com.mrnobody.browser.blocking.FilterEngine;
import com.mrnobody.browser.core.Settings;
import com.mrnobody.browser.history.HistoryStore;

/**
 * Application entry point. Boots the long-lived singletons once per process.
 * There is deliberately no analytics, no advertising SDK, and no network call
 * made at startup — the only I/O is loading the bundled filter list from assets.
 */
public final class MrNobodyApp extends Application {

    private static FilterEngine filterEngine;
    private static Settings settings;
    private static HistoryStore historyStore;

    @Override
    public void onCreate() {
        super.onCreate();
        // Load the bundled blocklist. Failure here must never prevent the
        // browser from starting (see docs/ARCHITECTURE.md — failure model).
        filterEngine = new FilterEngine();
        filterEngine.loadBundled(this);
        settings = new Settings(this);
        historyStore = new HistoryStore(this);
    }

    public static FilterEngine filters() {
        return filterEngine;
    }

    public static Settings settings() {
        return settings;
    }

    public static HistoryStore history() {
        return historyStore;
    }

    public static MrNobodyApp app(Context context) {
        return (MrNobodyApp) context.getApplicationContext();
    }
}
