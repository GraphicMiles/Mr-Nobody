package com.mrnobody.browser;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.browser.core.BookmarksStore;
import com.mrnobody.browser.core.PrivacyProfile;
import com.mrnobody.browser.deeplink.DeepLinkHandler;
import com.mrnobody.browser.download.DownloadDestination;
import com.mrnobody.debug.ErrorLog;
import com.mrnobody.browser.webview.MrNobodyWebViewFactory;

import android.app.DownloadManager;
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
                        case "removeDownload": {
                            // Cancels it if running, and deletes the file it
                            // wrote — the only way to stop a system download
                            // from inside the app.
                            Number rmId = call.argument("id");
                            if (rmId == null) {
                                result.error("bad_arg", "id required", null);
                                return;
                            }
                            try {
                                DownloadManager dm =
                                        (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                                result.success(dm != null && dm.remove(rmId.longValue()) > 0);
                            } catch (Exception e) {
                                result.success(false);
                            }
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

    /** Result of the system folder picker; see "pickDownloadFolder". */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
     */
    private List<Map<String, Object>> listDownloads() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return out;
            Cursor c = dm.query(new DownloadManager.Query());
            if (c == null) return out;
            int iId = c.getColumnIndex(DownloadManager.COLUMN_ID);
            int iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
            int iSize = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            int iSoFar = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
            int iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int iReason = c.getColumnIndex(DownloadManager.COLUMN_REASON);
            int iUri = c.getColumnIndex(DownloadManager.COLUMN_URI);
            int iLocal = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            int iMime = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
            int iWhen = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);
            while (c.moveToNext()) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", iId >= 0 ? c.getLong(iId) : 0L);
                m.put("name", iTitle >= 0 ? c.getString(iTitle) : "download");
                m.put("size", iSize >= 0 ? c.getLong(iSize) : 0L);
                m.put("downloaded", iSoFar >= 0 ? c.getLong(iSoFar) : 0L);
                m.put("status", iStatus >= 0 ? c.getInt(iStatus) : 0);
                m.put("reason", iReason >= 0 ? c.getInt(iReason) : 0);
                m.put("url", iUri >= 0 ? c.getString(iUri) : null);
                m.put("localUri", iLocal >= 0 ? c.getString(iLocal) : null);
                m.put("mime", iMime >= 0 ? c.getString(iMime) : null);
                m.put("updatedAt", iWhen >= 0 ? c.getLong(iWhen) : 0L);
                out.add(m);
            }
            c.close();
        } catch (Exception e) {
            ErrorLog.record("downloads query failed: " + e);
        }
        return out;
    }

    /** Hand a finished download to whatever app knows how to open it. */
    private boolean openDownload(long id) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return false;
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) return false;
            String mime = dm.getMimeTypeForDownloadedFile(id);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, mime == null ? "*/*" : mime);
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
