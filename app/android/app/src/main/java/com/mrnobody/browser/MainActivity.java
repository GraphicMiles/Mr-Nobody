package com.mrnobody.browser;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.browser.AccountGrant;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.browser.core.BookmarksStore;
import com.mrnobody.browser.core.PrivacyProfile;
import com.mrnobody.browser.download.DownloadDestination;
import com.mrnobody.browser.download.DownloadEngine;
import com.mrnobody.browser.download.DownloadRecord;
import com.mrnobody.agent.policy.ApprovalMode;
import com.mrnobody.agent.policy.RestrictedTools;
import com.mrnobody.agent.tasks.TaskEventStore;
import com.mrnobody.agent.tasks.TaskStreamHub;
import com.mrnobody.browser.net.EngineInfo;
import com.mrnobody.browser.net.PrivacyController;
import com.mrnobody.browser.net.PrivacyMode;
import com.mrnobody.debug.Diagnostics;
import com.mrnobody.debug.ErrorLog;
import com.mrnobody.browser.webview.MrNobodyWebViewFactory;

import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.DocumentsContract;
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
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;

/**
 * Flutter host activity. The entire UI is Flutter; this class owns the bridge
 * to the Java core (agent engine, task store, filter engine) and routes deep
 * links / shared URLs to Dart.
 */
public class MainActivity extends FlutterActivity {

    private static final String CORE = "mrnobody/core";
    private static final String DEEPLINK = "mrnobody/deeplink";

    private static final int REQUEST_PICK_FOLDER = 4301;

    /** Held while the system folder picker is open. */
    @Nullable
    private MethodChannel.Result pendingFolderResult;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    private MethodChannel deeplinkChannel;

    /** Task-stream listeners, keyed by task id, so {@code onCancel} can drop them. */
    private final Map<Long, TaskStreamHub.Listener> streamListeners = new HashMap<>();

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
                        case "rerunTask": {
                            // A follow-up like "check again" re-runs the SAME
                            // task rather than spawning a new one — so a
                            // recurring monitor keeps its one schedule, and the
                            // conversation continues in place instead of
                            // forking. Reset and re-enqueue the existing id.
                            Number rerunId = call.argument("id");
                            if (rerunId == null) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            Task t = MrNobodyApp.tasks().get(rerunId.longValue());
                            if (t == null) {
                                result.success(false);
                                return;
                            }
                            t.setStatus(Task.Status.QUEUED);
                            t.setCurrentStep("");
                            t.setResult("");
                            t.setError("");
                            t.resetRetry(); // a "check again" is a fresh run
                            MrNobodyApp.tasks().update(t);
                            // Cancel the recurring schedule first, so the manual
                            // re-check cannot race the periodic worker on the
                            // same task row. The re-run itself re-registers the
                            // schedule (applyRecurrence), giving a clean
                            // "run now, then resume hourly".
                            MrNobodyApp.scheduler().cancel(getApplicationContext(), t.id());
                            MrNobodyApp.scheduler().schedule(getApplicationContext(), t.id());
                            result.success(true);
                            return;
                        }
                        case "followUpTask": {
                            Number followId = call.argument("id");
                            String text = call.argument("text");
                            if (followId == null || text == null || text.trim().isEmpty()) {
                                result.error("bad_arg", "id and text required", null);
                                return;
                            }
                            Task t = MrNobodyApp.tasks().get(followId.longValue());
                            if (t == null) {
                                result.success(false);
                                return;
                            }
                            String prior = t.result();
                            if (prior != null && !prior.isEmpty()) {
                                boolean already = false;
                                for (TaskEventStore.Event e : MrNobodyApp.taskEvents().eventsFor(t.id())) {
                                    if (TaskEventStore.AGENT_ANSWER.equals(e.type)) {
                                        already = true;
                                        break;
                                    }
                                }
                                if (!already) {
                                    MrNobodyApp.taskEvents().append(t.id(),
                                            TaskEventStore.AGENT_ANSWER, prior);
                                }
                            }
                            MrNobodyApp.taskEvents().append(t.id(),
                                    TaskEventStore.USER_FOLLOWUP, text.trim());
                            t.setFollowUp(text.trim());
                            t.setStatus(Task.Status.QUEUED);
                            t.setCurrentStep("");
                            t.setResult("");
                            t.setError("");
                            t.resetRetry();
                            MrNobodyApp.tasks().update(t);
                            MrNobodyApp.scheduler().cancel(getApplicationContext(), t.id());
                            MrNobodyApp.scheduler().schedule(getApplicationContext(), t.id());
                            result.success(true);
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
                                m.put("schedule", MrNobodyApp.tasks().scheduleOf(t.id()).name());
                                out.add(m);
                            }
                            result.success(out);
                            return;
                        }
                        case "listMonitors": {
                            List<Map<String, Object>> out = new ArrayList<>();
                            for (Task t : MrNobodyApp.tasks().monitors()) {
                                Map<String, Object> m = new HashMap<>();
                                m.put("id", t.id());
                                m.put("instruction", t.instruction());
                                m.put("status", t.status().name());
                                m.put("schedule", MrNobodyApp.tasks().scheduleOf(t.id()).name());
                                out.add(m);
                            }
                            result.success(out);
                            return;
                        }
                        case "listAccounts": {
                            List<Map<String, Object>> out = new ArrayList<>();
                            if (MrNobodyApp.accounts() != null) {
                                for (AccountGrant g : MrNobodyApp.accounts().list()) {
                                    Map<String, Object> m = new HashMap<>();
                                    m.put("host", g.host);
                                    m.put("names", g.names);
                                    m.put("source", g.source.name());
                                    m.put("at", g.grantedAt);
                                    out.add(m);
                                }
                            }
                            result.success(out);
                            return;
                        }
                        case "importAccount": {
                            String host = call.argument("host");
                            String raw = call.argument("cookies");
                            AccountGrant g = AccountGrant.parse(raw, host, AccountGrant.Source.PASTED);
                            if (g == null) {
                                result.error("bad_arg", "could not read cookies for that site", null);
                                return;
                            }
                            MrNobodyApp.accounts().grant(g);
                            Map<String, Object> m = new HashMap<>();
                            m.put("host", g.host);
                            m.put("names", g.names);
                            result.success(m);
                            return;
                        }
                        case "captureAccount": {
                            String url = call.argument("url");
                            if (url == null || url.trim().isEmpty()) {
                                result.error("bad_arg", "url required", null);
                                return;
                            }
                            String header = CookieManager.getInstance().getCookie(url);
                            AccountGrant g = AccountGrant.parse(header, url, AccountGrant.Source.TAB);
                            if (g == null) {
                                Map<String, Object> miss = new HashMap<>();
                                miss.put("ok", false);
                                miss.put("reason", "no cookies on this tab");
                                result.success(miss);
                                return;
                            }
                            MrNobodyApp.accounts().grant(g);
                            Map<String, Object> m = new HashMap<>();
                            m.put("ok", true);
                            m.put("host", g.host);
                            m.put("names", g.names);
                            result.success(m);
                            return;
                        }
                        case "revokeAccount": {
                            String host = call.argument("host");
                            result.success(MrNobodyApp.accounts() != null
                                    && MrNobodyApp.accounts().revoke(host));
                            return;
                        }
                        case "listRestrictedTools": {
                            result.success(RestrictedTools.list());
                            return;
                        }
                        case "runRestrictedTool": {
                            String toolId = call.argument("id");
                            result.success(RestrictedTools.execute(toolId));
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
                        case "releaseTab": {
                            // The tab is closed for good, so its retained page
                            // can be destroyed. Anything short of this (leaving
                            // the browser, switching tabs) keeps the page alive
                            // deliberately.
                            Number tabId = call.argument("id");
                            if (tabId == null) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            com.mrnobody.browser.webview.TabWebViews.release(tabId.intValue());
                            result.success(true);
                            return;
                        }
                        case "downloads": {
                            result.success(listDownloads());
                            return;
                        }
                        case "openDownload": {
                            Number dlId = call.argument("id");
                            if (dlId == null) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            result.success(openDownload(dlId.longValue()));
                            return;
                        }
                        case "pauseDownload": {
                            Number pId = call.argument("id");
                            if (pId == null) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            result.success(DownloadEngine.get(this).pause(pId.longValue()));
                            return;
                        }
                        case "resumeDownload": {
                            Number rId = call.argument("id");
                            if (rId == null) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            result.success(DownloadEngine.get(this).resume(rId.longValue()));
                            return;
                        }
                        case "cancelDownload": {
                            // Stops the transfer and deletes the partial file,
                            // but keeps the row so the user can see what
                            // happened to it.
                            Number cId = call.argument("id");
                            if (cId == null) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            result.success(DownloadEngine.get(this).cancel(cId.longValue()));
                            return;
                        }
                        case "removeDownload": {
                            Number rmId = call.argument("id");
                            if (rmId == null) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            Boolean deleteFile = call.argument("deleteFile");
                            result.success(DownloadEngine.get(this)
                                    .remove(rmId.longValue(), !Boolean.FALSE.equals(deleteFile)));
                            return;
                        }
                        case "downloadFolder": {
                            DownloadDestination dest = new DownloadDestination(this);
                            Map<String, Object> m = new HashMap<>();
                            m.put("label", dest.label());
                            m.put("custom", dest.isCustom());
                            result.success(m);
                            return;
                        }
                        case "pickDownloadFolder": {
                            // The system picker is the only way to get write
                            // access to a folder outside our own storage, and
                            // the grant has to be taken persistably or it dies
                            // with the process.
                            if (pendingFolderResult != null) {
                                pendingFolderResult.error("busy", "a folder picker is already open", null);
                                return;
                            }
                            pendingFolderResult = result;
                            try {
                                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                                pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                                startActivityForResult(pick, REQUEST_PICK_FOLDER);
                            } catch (Exception e) {
                                pendingFolderResult = null;
                                result.error("no_picker", "No folder picker on this device", null);
                            }
                            return;
                        }
                        case "clearDownloadFolder": {
                            DownloadDestination dest = new DownloadDestination(this);
                            Uri previous = dest.treeUri();
                            if (previous != null) {
                                try {
                                    getContentResolver().releasePersistableUriPermission(previous,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                                } catch (Exception ignored) {
                                    // The grant may already be gone; clearing is still correct.
                                }
                            }
                            dest.clearTree();
                            Map<String, Object> m = new HashMap<>();
                            m.put("label", dest.label());
                            m.put("custom", false);
                            result.success(m);
                            return;
                        }
                        case "completionStats": {
                            result.success(com.mrnobody.agent.tasks.CompletionStats.snapshot());
                            return;
                        }
                        case "diagnostics": {
                            // The Phase 1 device benchmark: every subsystem
                            // reports pass/fail so a real-device run is a list
                            // the user reads off, not a guess. Failures land in
                            // the report; the panel also records them to the
                            // debug log so the ⓘ badge carries them.
                            //
                            // Runs off the UI thread: the headless-browser check
                            // posts its load to main and awaits the result, so
                            // running it on main would deadlock.
                            executor.execute(() -> {
                                List<Map<String, Object>> maps =
                                        Diagnostics.runAsMaps(MainActivity.this);
                                runOnUiThread(() -> result.success(maps));
                            });
                            return;
                        }
                        case "debugLog": {
                            // The overlay's badge counts what the CORE has seen
                            // as well as what Dart has: an AI 404 or a failed
                            // tool happens entirely on this side.
                            Map<String, Object> m = new HashMap<>();
                            m.put("entries", ErrorLog.tail(50));
                            m.put("count", ErrorLog.count());
                            result.success(m);
                            return;
                        }
                        case "clearDebugLog": {
                            ErrorLog.clear();
                            result.success(null);
                            return;
                        }
                        case "networkStatus": {
                            result.success(networkStatus());
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
                            m.put("fingerprint", MrNobodyApp.settings().isFingerprintProtection());
                            m.put("privacyMode", PrivacyController.current().name());
                            m.put("privacyModeLabel", PrivacyController.current().label());
                            m.put("privacyModeNote", PrivacyController.current().description());
                            m.put("route", MrNobodyApp.settings().routeId());
                            m.put("proxyKind", MrNobodyApp.settings().proxyKind());
                            m.put("proxyHost", MrNobodyApp.settings().proxyHost());
                            m.put("proxyPort", MrNobodyApp.settings().proxyPort());
                            m.put("approvalMode", MrNobodyApp.settings().approvalMode());
                            m.put("resourcePolicy", MrNobodyApp.settings().resourcePolicy().name());
                            m.put("blocking", MrNobodyApp.settings().isBlockingEnabled());
                            m.put("paramStripping", MrNobodyApp.settings().isParamStrippingEnabled());
                            result.success(m);
                            return;
                        }
                        case "taskEvents": {
                            Integer id = call.argument("id");
                            List<Map<String, Object>> out = new ArrayList<>();
                            if (id != null) {
                                for (TaskEventStore.Event e
                                        : MrNobodyApp.taskEvents().eventsFor(id)) {
                                    Map<String, Object> row = new HashMap<>();
                                    row.put("seq", e.seq);
                                    row.put("type", e.type);
                                    row.put("detail", e.detail);
                                    row.put("at", e.at);
                                    out.add(row);
                                }
                            }
                            result.success(out);
                            return;
                        }
                        case "privacyMode": {
                            String name = call.argument("mode");
                            PrivacyController.Result r =
                                    MrNobodyApp.applyPrivacyMode(PrivacyMode.fromName(name));
                            Map<String, Object> m = new HashMap<>();
                            // Report what was achieved, not what was asked:
                            // a refused mode must not look like it applied.
                            m.put("requested", r.requested.name());
                            m.put("effective", r.effective.name());
                            m.put("applied", r.isFullyApplied());
                            m.put("label", r.effective.label());
                            m.put("note", r.effective.description());
                            m.put("problem", r.problem);
                            result.success(m);
                            return;
                        }
                        case "setProxy": {
                            String kind = call.argument("kind");
                            String host = call.argument("host");
                            Integer port = call.argument("port");
                            MrNobodyApp.settings().setProxy(kind, host, port == null ? 0 : port);
                            String routeId = call.argument("route");
                            if (routeId != null) MrNobodyApp.settings().setRouteId(routeId);
                            // Re-apply so a live NOBODY session picks the change up.
                            PrivacyController.Result r =
                                    MrNobodyApp.applyPrivacyMode(PrivacyController.current());
                            Map<String, Object> m = new HashMap<>();
                            m.put("applied", r.isFullyApplied());
                            m.put("problem", r.problem);
                            result.success(m);
                            return;
                        }
                        case "engineInfo": {
                            result.success(EngineInfo.describe(MainActivity.this));
                            return;
                        }
                        case "revalidateRoute": {
                            String problem = PrivacyController.revalidate(MrNobodyApp.settings());
                            if (problem != null) {
                                // refuse() dropped the live mode; persist so
                                // the next launch does not claim Nobody.
                                MrNobodyApp.settings().setPrivacyMode(
                                        PrivacyController.current().name());
                            }
                            Map<String, Object> m = new HashMap<>();
                            m.put("ok", problem == null);
                            m.put("problem", problem);
                            m.put("mode", PrivacyController.current().name());
                            result.success(m);
                            return;
                        }
                        case "resolveApproval": {
                            Number waitId = call.argument("id");
                            Boolean allow = call.argument("allow");
                            if (waitId == null || allow == null) {
                                result.error("bad_arg", "id and allow required", null);
                                return;
                            }
                            result.success(resolveApproval(waitId.longValue(), allow));
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
                        case "listModels": {
                            // Ask the provider, with whatever the user has
                            // typed but not yet saved, so the list can be
                            // fetched before committing anything.
                            String id = call.argument("id");
                            if (id == null || id.trim().isEmpty()) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            String base = call.argument("base");
                            String key = call.argument("key");
                            if (key == null || key.isEmpty()) {
                                key = MrNobodyApp.settings().apiKey(id);
                            }
                            if (base == null || base.trim().isEmpty()) {
                                base = MrNobodyApp.settings().apiBase(id);
                            }
                            AiProvider probe = MrNobodyApp.buildProvider(id, base, "", key);
                            probe.listModels(new AiProvider.ModelsCallback() {
                                @Override
                                public void onModels(java.util.List<String> modelIds) {
                                    Map<String, Object> m = new HashMap<>();
                                    m.put("models", modelIds);
                                    runOnUiThread(() -> result.success(m));
                                }

                                @Override
                                public void onError(String error) {
                                    Map<String, Object> m = new HashMap<>();
                                    m.put("models", new ArrayList<String>());
                                    m.put("error", error);
                                    runOnUiThread(() -> result.success(m));
                                }
                            });
                            return;
                        }
                        case "removeProviderKey": {
                            String id = call.argument("id");
                            if (id == null || id.trim().isEmpty()) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            MrNobodyApp.settings().removeApiKey(id);
                            if (id.equals(MrNobodyApp.activeAiProviderId())) {
                                MrNobodyApp.setActiveAiProviderId("local");
                            }
                            result.success(null);
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
                        case "memoryInfo": {
                            // What the agent remembers: the on-device task
                            // history, newest first. Never the network, never a
                            // profile of the person — just past work.
                            java.util.List<Task> tasks = MrNobodyApp.tasks().recent(100);
                            java.util.List<Map<String, Object>> rows = new ArrayList<>();
                            for (Task t : tasks) {
                                Map<String, Object> m = new HashMap<>();
                                m.put("id", t.id());
                                m.put("instruction", t.instruction());
                                m.put("status", t.status().name());
                                m.put("result", t.result() == null ? "" : t.result());
                                rows.add(m);
                            }
                            Map<String, Object> out = new HashMap<>();
                            out.put("count", rows.size());
                            out.put("tasks", rows);
                            result.success(out);
                            return;
                        }
                        case "forgetMemory": {
                            // Erase everything the agent remembers. Local and
                            // immediate; the only memory that exists is here.
                            MrNobodyApp.tasks().clear();
                            MrNobodyApp.taskEvents().clearAll();
                            result.success(null);
                            return;
                        }
                        default:
                            result.notImplemented();
                    }
                });

        // Task answer stream: the worker emits tokens through TaskStreamHub as
        // a remote provider generates them; this forwards them to the task chat
        // so the reveal is real arrival rather than a timed replay of a
        // finished string. Fire-and-forget — a token with no listener is
        // dropped, and the finished answer still lands in the task row.
        new EventChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), "mrnobody/task-stream")
                .setStreamHandler(new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object arguments, EventChannel.EventSink events) {
                        final long taskId = arguments instanceof Number
                                ? ((Number) arguments).longValue() : -1L;
                        if (taskId < 0) {
                            events.error("bad_arg", "task id required", null);
                            return;
                        }
                        // The hub emits from the provider's worker thread, but
                        // EventSink.success() dispatches a platform message that
                        // MUST run on the main thread — posting off-main is the
                        // "Methods marked with @UiThread must be executed on the
                        // main thread" crash that killed the app mid-task.
                        TaskStreamHub.Listener listener = new TaskStreamHub.Listener() {
                            @Override public void onToken(long id, String token) {
                                runOnUiThread(() -> events.success(streamEvent(id, "token", token)));
                            }
                            @Override public void onDone(long id, String fullText) {
                                runOnUiThread(() -> events.success(streamEvent(id, "done", fullText)));
                            }
                            @Override public void onError(long id, String error) {
                                runOnUiThread(() -> events.success(streamEvent(id, "error", error)));
                            }
                        };
                        streamListeners.put(taskId, listener);
                        TaskStreamHub.instance().subscribe(taskId, listener);
                    }

                    @Override
                    public void onCancel(Object arguments) {
                        final long taskId = arguments instanceof Number
                                ? ((Number) arguments).longValue() : -1L;
                        TaskStreamHub.Listener listener = streamListeners.remove(taskId);
                        if (listener != null) {
                            TaskStreamHub.instance().unsubscribe(taskId, listener);
                        }
                    }
                });

        // Deep-link channel: forward incoming intents to Dart.
        deeplinkChannel = new MethodChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(), DEEPLINK);
    }

    /** One task-stream event, as the task chat expects it. */
    private static Map<String, Object> streamEvent(long taskId, String type, String text) {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", taskId);
        m.put("type", type);
        m.put("text", text);
        return m;
    }

    /** Result of the system folder picker; see "pickDownloadFolder". */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (FileChooserHost.deliver(requestCode, resultCode, data)) return;
        if (requestCode != REQUEST_PICK_FOLDER) return;
        MethodChannel.Result pending = pendingFolderResult;
        pendingFolderResult = null;
        if (pending == null) return;

        Uri tree = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (tree == null) {
            Map<String, Object> m = new HashMap<>();
            m.put("cancelled", true);
            pending.success(m);
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            ErrorLog.record("Could not keep access to that folder: " + e);
            Map<String, Object> m = new HashMap<>();
            m.put("error", "Android would not grant lasting access to that folder.");
            pending.success(m);
            return;
        }
        String label = folderLabel(tree);
        new DownloadDestination(this).setTree(tree, label);
        Map<String, Object> m = new HashMap<>();
        m.put("label", label);
        m.put("custom", true);
        pending.success(m);
    }

    /** The folder's own display name, so Settings shows something recognisable. */
    private String folderLabel(Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String name = c.getString(0);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the id, which is still better than "unknown".
        }
        String id = DocumentsContract.getTreeDocumentId(tree);
        return id == null ? tree.getLastPathSegment() : id;
    }

    /**
     * Everything the Downloads screen needs to describe a file: where it came
     * from, what it is, how far along it is, and where it landed.
     *
     * <p>Read from the app's own store rather than {@code DownloadManager}:
     * the transfers are ours now, and so is the truth about them.
     */
    private List<Map<String, Object>> listDownloads() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (DownloadRecord record : DownloadEngine.get(this).store().all()) {
                out.add(record.toMap());
            }
        } catch (Exception e) {
            ErrorLog.record("downloads query failed: " + e);
        }
        return out;
    }

    /** Hand a finished download to whatever app knows how to open it. */
    private boolean openDownload(long id) {
        try {
            DownloadRecord record = DownloadEngine.get(this).store().find(id);
            if (record == null || record.destUri == null) return false;
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(Uri.parse(record.destUri),
                    record.mime == null || record.mime.isEmpty() ? "*/*" : record.mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
            return true;
        } catch (Exception e) {
            ErrorLog.record("could not open download " + id + ": " + e);
            return false;
        }
    }

    /**
     * What the connection can do right now. Deliberately from
     * NetworkCapabilities only: signal bars would need READ_PHONE_STATE, a
     * permission this product refuses to ask for.
     */
    private Map<String, Object> networkStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("transport", "none");
        m.put("metered", false);
        m.put("downKbps", 0);
        m.put("upKbps", 0);
        m.put("online", false);
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) return m;
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            if (caps == null) return m;
            String transport = "other";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "wifi";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "cellular";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "ethernet";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transport = "vpn";
            m.put("transport", transport);
            m.put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            m.put("downKbps", caps.getLinkDownstreamBandwidthKbps());
            m.put("upKbps", caps.getLinkUpstreamBandwidthKbps());
            m.put("online", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        } catch (Exception e) {
            ErrorLog.record("network status failed: " + e);
        }
        return m;
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
                MrNobodyApp.applyPrivacyProfile(
                        PrivacyProfile.fromName(String.valueOf(value)));
                break;
            case "approvalMode":
                // Takes effect on the next call, not the next launch.
                MrNobodyApp.setApprovalMode(
                        ApprovalMode.fromName(String.valueOf(value)));
                break;
            case "fingerprint":
                // Was a dead toggle: read by the UI, enforced nowhere. The
                // patches install before first page load, so this takes effect
                // on newly opened tabs rather than retroactively.
                MrNobodyApp.settings().setFingerprintProtection(
                        Boolean.TRUE.equals(value));
                break;
            case "searchEngine":
                MrNobodyApp.settings().setSearchEngine(String.valueOf(value));
                break;
            case "provider":
                MrNobodyApp.setActiveAiProviderId(String.valueOf(value));
                break;
            case "resourcePolicy":
                // Data Saver grade. Applies to newly created WebViews; a page
                // already open keeps its current settings until reload.
                MrNobodyApp.settings().setResourcePolicy(
                        com.mrnobody.browser.net.ResourcePolicy.fromName(String.valueOf(value)));
                break;
            case "blocking": {
                // Ad/tracker blocking. The FilterEngine holds its own enable
                // flag, so the toggle must reach it, or this would be a dead
                // switch that reads "on" while the engine still blocks nothing
                // (or blocks when the user asked it not to).
                boolean v = Boolean.TRUE.equals(value);
                MrNobodyApp.settings().setBlockingEnabled(v);
                MrNobodyApp.filters().setEnabled(v);
                break;
            }
            case "paramStripping":
                // Tracking-parameter stripping. Already enforced on the request
                // path; this toggle just exposes it.
                MrNobodyApp.settings().setParamStrippingEnabled(Boolean.TRUE.equals(value));
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
        m.put("createdAt", t.createdAt());
        m.put("updatedAt", t.updatedAt());
        m.put("artifacts", t.artifacts() == null ? "" : t.artifacts());
        try {
            m.put("pendingTool", MrNobodyApp.tasks().pendingTool(t.id()));
        } catch (Throwable ignored) {
            m.put("pendingTool", "");
        }
        return m;
    }

    /**
     * Clear the buckets the user ticked on the Clear-data screen. Everything is
     * local, so this is a straight delete — nothing is reported anywhere.
     */
    private Map<String, Object> clearData(@Nullable List<String> buckets) {
        Map<String, Object> cleared = new HashMap<>();
        if (buckets == null) return cleared;

        boolean clearsBrowserState = buckets.contains("cookies")
                || buckets.contains("cache") || buckets.contains("sitedata");
        if (clearsBrowserState) {
            // A live WebView can retain cookies/storage in memory and an
            // isolated profile cannot be deleted while a WebView owns it.
            // Tear down private and agent browsers before clearing stores,
            // then retry deletion of the private profile after WebView releases it.
            // Normal tabs may stay open and reload against the cleared default
            // stores. Private tabs must close so their isolated profile can be deleted.
            com.mrnobody.browser.webview.TabWebViews.releasePrivate();
            com.mrnobody.agent.browser.HeadlessSessions.releaseAll();
            com.mrnobody.browser.net.ProfileManager.destroyPrivateWhenIdle();
        }

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
                        if (MrNobodyApp.accounts() != null) {
                            for (AccountGrant g : new ArrayList<>(MrNobodyApp.accounts().list())) {
                                MrNobodyApp.accounts().revoke(g.host);
                            }
                        }
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

    /**
     * A WAITING task is answered. Allow re-runs with a process-session tool
     * override so the same call can resume; deny fails it.
     */
    private boolean resolveApproval(long id, boolean allow) {
        Task t = MrNobodyApp.tasks().get(id);
        if (t == null || t.status() != Task.Status.WAITING) return false;
        String tool = MrNobodyApp.tasks().pendingTool(id);
        MrNobodyApp.tasks().setPendingTool(id, null);
        if (!allow) {
            t.setStatus(Task.Status.FAILED);
            t.setError("Declined" + (tool.isEmpty() ? "." : ": " + tool));
            t.setCurrentStep("");
            MrNobodyApp.tasks().update(t);
            return true;
        }
        // The user finished a file upload in a visible tab. Re-running would
        // try the headless input again and park forever.
        if ("upload".equals(tool)) {
            String note = "You finished the file upload in a visible tab.";
            t.setStatus(Task.Status.COMPLETED);
            t.setError("");
            t.setCurrentStep("");
            t.setResult(note);
            MrNobodyApp.tasks().update(t);
            try {
                MrNobodyApp.taskEvents().append(t.id(), TaskEventStore.AGENT_ANSWER, note);
            } catch (Throwable ignored) {
            }
            return true;
        }
        // network / grant / review / upload parks are not "always allow this tool".
        boolean sticky = !tool.isEmpty()
                && !"network".equals(tool)
                && !"grant".equals(tool)
                && !"review".equals(tool)
                && !"upload".equals(tool);
        if (sticky && MrNobodyApp.approvalOverrides() != null) {
            MrNobodyApp.approvalOverrides().set(tool,
                    com.mrnobody.agent.policy.ApprovalPolicy.Rule.ALWAYS_ALLOW);
        }
        t.setStatus(Task.Status.QUEUED);
        t.setError("");
        t.setResult("");
        t.setCurrentStep("");
        t.resetRetry();
        MrNobodyApp.tasks().update(t);
        MrNobodyApp.scheduler().cancel(getApplicationContext(), id);
        MrNobodyApp.scheduler().schedule(getApplicationContext(), id);
        return true;
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
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dispatchDeepLink(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ApprovalPrompt.setHost(this);
        FileChooserHost.setHost(this);
    }

    @Override
    protected void onPause() {
        ApprovalPrompt.clearHost(this);
        FileChooserHost.clearHost(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ApprovalPrompt.clearHost(this);
        FileChooserHost.clearHost(this);
        super.onDestroy();
    }
}
