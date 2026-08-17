package com.mrnobody.browser;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.browser.core.BookmarksStore;
import com.mrnobody.browser.core.PrivacyProfile;
import com.mrnobody.browser.deeplink.DeepLinkHandler;
import com.mrnobody.browser.webview.MrNobodyWebViewFactory;

import android.app.DownloadManager;
import android.database.Cursor;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

/**
 * Flutter host activity. The entire UI is Flutter; this class owns the bridge
 * to the Java core (agent engine, task store, filter engine) and routes deep
 * links / shared URLs to Dart.
 */
public class MainActivity extends FlutterActivity {

    private static final String CORE = "mrnobody/core";
    private static final String DEEPLINK = "mrnobody/deeplink";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    private MethodChannel deeplinkChannel;

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);

        // The visible browser is our own WebView, hosted as a platform view, so
        // the filter engine sits on the request path (see MrNobodyWebView).
        flutterEngine.getPlatformViewsController().getRegistry().registerViewFactory(
                MrNobodyWebViewFactory.VIEW_TYPE,
                new MrNobodyWebViewFactory(flutterEngine.getDartExecutor().getBinaryMessenger()));

        // Core bridge: run tasks, list tasks, privacy stats, settings.
        new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CORE)
                .setMethodCallHandler((call, result) -> {
                    switch (call.method) {
                        case "runTask": {
                            String instruction = call.argument("instruction");
                            if (instruction == null || instruction.trim().isEmpty()) {
                                result.error("bad_arg", "instruction required", null);
                                return;
                            }
                            ensureNotificationPermission();
                            long id = MrNobodyApp.tasks().insert(instruction.trim());
                            MrNobodyApp.scheduler().schedule(getApplicationContext(), id);
                            result.success(Map.of("id", id));
                            return;
                        }
                        case "recentTasks": {
                            List<Task> tasks = MrNobodyApp.tasks().recent(100);
                            List<Map<String, Object>> out = new ArrayList<>();
                            for (Task t : tasks) {
                                Map<String, Object> m = new HashMap<>();
                                m.put("id", t.id());
                                m.put("instruction", t.instruction());
                                m.put("status", t.status().name());
                                m.put("step", t.currentStep() == null ? "" : t.currentStep());
                                m.put("progress", t.progress());
                                out.add(m);
                            }
                            result.success(out);
                            return;
                        }
                        case "privacyStats": {
                            Map<String, Object> m = new HashMap<>();
                            m.put("pageAds", MrNobodyApp.filters().getPageAdsBlocked());
                            m.put("pageTrackers", MrNobodyApp.filters().getPageTrackersBlocked());
                            m.put("todayAds", MrNobodyApp.report().adsBlocked());
                            m.put("todayTrackers", MrNobodyApp.report().trackersBlocked());
                            m.put("score", MrNobodyApp.filters().privacyScore());
                            result.success(m);
                            return;
                        }
                        case "isHistoryEnabled": {
                            result.success(MrNobodyApp.settings().isHistoryEnabled());
                            return;
                        }
                        case "setHistoryEnabled": {
                            Boolean v = call.argument("value");
                            if (v == null) {
                                result.error("bad_arg", "value required", null);
                                return;
                            }
                            MrNobodyApp.settings().setHistoryEnabled(v);
                            MrNobodyApp.history().setEnabled(v);
                            result.success(null);
                            return;
                        }
                        case "isFirstLaunchDone": {
                            result.success(MrNobodyApp.settings().isFirstLaunchDone());
                            return;
                        }
                        case "setFirstLaunchDone": {
                            MrNobodyApp.settings().setFirstLaunchDone();
                            result.success(null);
                            return;
                        }
                        case "search": {
                            // Agent-path search: parse results via the core (never
                            // raw HTML). Runs off the UI thread (network I/O).
                            String q = call.argument("q");
                            if (q == null || q.trim().isEmpty()) {
                                result.error("bad_arg", "q required", null);
                                return;
                            }
                            final String query = q.trim();
                            executor.execute(() -> {
                                // Through the engine, never straight at the tool:
                                // the guarded pipeline lives on callTool().
                                ToolResult r = MrNobodyApp.agent().callTool(
                                        getApplicationContext(), "search",
                                        ToolRequest.of("search", "q", query));
                                Map<String, Object> m = new HashMap<>();
                                m.put("success", r.isSuccess());
                                // Both audiences: the structured value for the
                                // UI, the rendered projection for a model.
                                m.put("value", r.value());
                                m.put("text", r.isSuccess() ? r.result() : r.error());
                                runOnUiThread(() -> result.success(m));
                            });
                            return;
                        }
                        case "downloads": {
                            List<Map<String, Object>> out = new ArrayList<>();
                            try {
                                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                                if (dm != null) {
                                    DownloadManager.Query query = new DownloadManager.Query();
                                    Cursor c = dm.query(query);
                                    if (c != null) {
                                        int iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
                                        int iSize = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                                        int iSoFar = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                                        int iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                                        while (c.moveToNext()) {
                                            Map<String, Object> m = new HashMap<>();
                                            m.put("name", c.getString(iTitle));
                                            m.put("size", c.getLong(iSize));
                                            m.put("downloaded", iSoFar >= 0 ? c.getLong(iSoFar) : 0L);
                                            m.put("status", c.getInt(iStatus));
                                            out.add(m);
                                        }
                                        c.close();
                                    }
                                }
                            } catch (Exception e) {
                                // best-effort; return empty list
                            }
                            result.success(out);
                            return;
                        }
                        case "getSettings": {
                            Map<String, Object> m = new HashMap<>();
                            m.put("history", MrNobodyApp.settings().isHistoryEnabled());
                            m.put("js", MrNobodyApp.settings().isJsEnabled());
                            m.put("suggestions", MrNobodyApp.settings().areSuggestionsEnabled());
                            m.put("terminal", MrNobodyApp.settings().isTerminalEnabled());
                            m.put("profile", MrNobodyApp.settings().getProfile().name());
                            m.put("searchEngine", MrNobodyApp.settings().getSearchEngine());
                            m.put("provider", MrNobodyApp.settings().activeAiProvider());
                            result.success(m);
                            return;
                        }
                        case "setSetting": {
                            String key = call.argument("key");
                            Object value = call.argument("value");
                            if (key == null) {
                                result.error("bad_arg", "key required", null);
                                return;
                            }
                            applySetting(key, value);
                            result.success(null);
                            return;
                        }
                        case "providerConfig": {
                            String id = call.argument("id");
                            if (id == null) id = "local";
                            Map<String, Object> m = new HashMap<>();
                            m.put("id", id);
                            m.put("base", MrNobodyApp.settings().apiBase(id));
                            m.put("model", MrNobodyApp.settings().apiModel(id));
                            // The key itself never crosses the channel — only whether one exists.
                            m.put("hasKey", !MrNobodyApp.settings().apiKey(id).isEmpty());
                            result.success(m);
                            return;
                        }
                        case "saveProvider": {
                            String id = call.argument("id");
                            if (id == null || id.trim().isEmpty()) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            String key = call.argument("key");
                            String base = call.argument("base");
                            String model = call.argument("model");
                            if (key != null && !key.isEmpty()) MrNobodyApp.settings().setApiKey(id, key);
                            if (base != null) MrNobodyApp.settings().setApiBase(id, base);
                            if (model != null) MrNobodyApp.settings().setApiModel(id, model);
                            Boolean makeActive = call.argument("active");
                            if (makeActive != null && makeActive) MrNobodyApp.setActiveAiProviderId(id);
                            result.success(null);
                            return;
                        }
                        case "cancelTask": {
                            Number cancelId = call.argument("id");
                            if (cancelId == null) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            long id = cancelId.longValue();
                            Task pending = MrNobodyApp.tasks().get(id);
                            if (pending == null) {
                                result.success(false);
                                return;
                            }
                            // The request is persisted either way: a worker may
                            // be mid-step in another process, or not started at
                            // all. Queued work can be closed out immediately.
                            MrNobodyApp.tasks().requestCancel(id);
                            MrNobodyApp.scheduler().cancel(getApplicationContext(), id);
                            if (pending.status() == Task.Status.QUEUED
                                    || pending.status() == Task.Status.WAITING) {
                                pending.setStatus(Task.Status.CANCELLED);
                                pending.setCurrentStep("");
                                MrNobodyApp.tasks().update(pending);
                                MrNobodyApp.tasks().clearCancelRequest(id);
                            }
                            result.success(true);
                            return;
                        }
                        case "task": {
                            Number idArg = call.argument("id");
                            if (idArg == null) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            Task t = MrNobodyApp.tasks().get(idArg.longValue());
                            if (t == null) {
                                result.success(null);
                                return;
                            }
                            result.success(taskMap(t));
                            return;
                        }
                        case "bookmarks": {
                            List<Map<String, Object>> out = new ArrayList<>();
                            for (BookmarksStore.Bookmark b : MrNobodyApp.bookmarks().all()) {
                                Map<String, Object> m = new HashMap<>();
                                m.put("id", b.id);
                                m.put("title", b.title);
                                m.put("url", b.url);
                                out.add(m);
                            }
                            result.success(out);
                            return;
                        }
                        case "addBookmark": {
                            String url = call.argument("url");
                            String title = call.argument("title");
                            if (url == null || url.trim().isEmpty()) {
                                result.error("bad_arg", "url required", null);
                                return;
                            }
                            MrNobodyApp.bookmarks().add(title == null ? url : title, url, "");
                            result.success(null);
                            return;
                        }
                        case "clearData": {
                            List<String> buckets = call.argument("buckets");
                            result.success(clearData(buckets));
                            return;
                        }
                        default:
                            result.notImplemented();
                    }
                });

        // Deep-link channel: forward incoming intents to Dart.
        deeplinkChannel = new MethodChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(), DEEPLINK);
    }

    /**
     * A task can finish while the app is closed, so ask for notification
     * permission the first time the user actually starts one — not at launch,
     * where the request has no context.
     */
    private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return;
        if (TaskNotifier.canNotify(this)) return;
        try {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 91);
        } catch (Exception ignored) {
            // Not fatal: tasks still run, the user just won't be told.
        }
    }

    /** Persist a single settings key coming from the Flutter Settings screen. */
    private void applySetting(String key, Object value) {
        switch (key) {
            case "history": {
                boolean v = Boolean.TRUE.equals(value);
                MrNobodyApp.settings().setHistoryEnabled(v);
                MrNobodyApp.history().setEnabled(v);
                break;
            }
            case "js":
                MrNobodyApp.settings().setJsEnabled(Boolean.TRUE.equals(value));
                break;
            case "suggestions":
                MrNobodyApp.settings().setSuggestionsEnabled(Boolean.TRUE.equals(value));
                break;
            case "terminal":
                MrNobodyApp.settings().setTerminalEnabled(Boolean.TRUE.equals(value));
                // Register/unregister the tool right away — the switch is the
                // gate, not a label.
                MrNobodyApp.applyTerminalSetting();
                break;
            case "profile":
                MrNobodyApp.settings().setProfile(
                        PrivacyProfile.fromName(String.valueOf(value)));
                break;
            case "searchEngine":
                MrNobodyApp.settings().setSearchEngine(String.valueOf(value));
                break;
            case "provider":
                MrNobodyApp.setActiveAiProviderId(String.valueOf(value));
                break;
            default:
                // Unknown keys are ignored on purpose: the UI never writes
                // settings the core does not own.
                break;
        }
    }

    private static Map<String, Object> taskMap(Task t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.id());
        m.put("instruction", t.instruction());
        m.put("status", t.status().name());
        m.put("step", t.currentStep() == null ? "" : t.currentStep());
        m.put("progress", t.progress());
        m.put("result", t.result() == null ? "" : t.result());
        m.put("error", t.error() == null ? "" : t.error());
        m.put("worker", t.worker() == null ? "local" : t.worker());
        return m;
    }

    /**
     * Clear the buckets the user ticked on the Clear-data screen. Everything is
     * local, so this is a straight delete — nothing is reported anywhere.
     */
    private Map<String, Object> clearData(@Nullable List<String> buckets) {
        Map<String, Object> cleared = new HashMap<>();
        if (buckets == null) return cleared;
        for (String bucket : buckets) {
            try {
                switch (bucket) {
                    case "history":
                        MrNobodyApp.history().clear();
                        cleared.put("history", true);
                        break;
                    case "cookies":
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        cleared.put("cookies", true);
                        break;
                    case "cache":
                        deleteRecursive(getCacheDir());
                        new WebView(this).clearCache(true);
                        cleared.put("cache", true);
                        break;
                    case "sitedata":
                        WebStorage.getInstance().deleteAllData();
                        cleared.put("sitedata", true);
                        break;
                    case "taskstate":
                        MrNobodyApp.tasks().clear();
                        cleared.put("taskstate", true);
                        break;
                    case "workspace":
                        deleteRecursive(new java.io.File(getFilesDir(), "workspace"));
                        cleared.put("workspace", true);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                cleared.put(bucket, false);
            }
        }
        return cleared;
    }

    private static void deleteRecursive(@Nullable java.io.File file) {
        if (file == null || !file.exists()) return;
        java.io.File[] children = file.listFiles();
        if (children != null) {
            for (java.io.File child : children) deleteRecursive(child);
        }
        // Keep the directory itself; only its contents are user data.
        if (file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Forward a deep link (mrnobody:// or a shared http(s) URL) to Dart. */    private void dispatchDeepLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null) return;
        final String uri = data.toString();
        if (deeplinkChannel != null) {
            deeplinkChannel.invokeMethod("link", uri);
        }
        // Classify for convenience (Dart re-parses; this just normalizes).
        if (DeepLinkHandler.isWebUrl(uri) || DeepLinkHandler.parse(uri).action != DeepLinkHandler.Action.NONE) {
            // handled by Dart via the channel above
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dispatchDeepLink(intent);
    }
}
