package com.mrnobody.browser;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.tools.SearchTool;
import com.mrnobody.browser.deeplink.DeepLinkHandler;

import android.app.DownloadManager;
import android.database.Cursor;

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
                                SearchTool tool = new SearchTool();
                                ToolResult r = tool.execute(getApplicationContext(),
                                        ToolRequest.of("search", "q", query));
                                Map<String, Object> m = new HashMap<>();
                                m.put("success", r.isSuccess());
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
                                        int iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                                        while (c.moveToNext()) {
                                            Map<String, Object> m = new HashMap<>();
                                            m.put("name", c.getString(iTitle));
                                            m.put("size", c.getLong(iSize));
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
                        default:
                            result.notImplemented();
                    }
                });

        // Deep-link channel: forward incoming intents to Dart.
        deeplinkChannel = new MethodChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(), DEEPLINK);
    }

    /** Forward a deep link (mrnobody:// or a shared http(s) URL) to Dart. */
    private void dispatchDeepLink(Intent intent) {
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
