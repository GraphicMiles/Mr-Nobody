package com.mrnobody.browser;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.planner.IntentRouter;
import com.mrnobody.agent.planner.IntentType;
import com.mrnobody.browser.blocking.FilterEngine;
import com.mrnobody.browser.blocking.TrackingParams;
import com.mrnobody.browser.core.BookmarksStore;
import com.mrnobody.browser.core.PerSiteSettings;
import com.mrnobody.browser.core.PermissionStore;
import com.mrnobody.browser.core.Settings;
import com.mrnobody.browser.ui.Tab;
import com.mrnobody.browser.ui.TabManager;
import com.mrnobody.debug.DebugOverlay;
import com.mrnobody.debug.ErrorLog;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The browser. Native chrome (address bar, toolbar, privacy panel, tab
 * management) around a System WebView rendering surface. All privacy decisions
 * are made here; WebView is used only to render pages.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 100;

    private TabManager tabs;
    private FrameLayout contentContainer;

    private EditText addressInput;
    private ImageView secureIcon;
    private View browserLayout, privacyPanel, firstLaunch;
    private TextView dashAds, dashTrackers, dashHistory;
    private TextView dashScore, dashTodayAds, dashTodayTrackers;

    // Pending web permission requests (camera/mic/location).
    private PermissionRequest pendingPermissionRequest;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    private ValueCallback<Uri[]> filePathCallback;

    private final ExecutorService agentExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePathCallback != null) {
                    Uri[] uris = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        uris = WebChromeClient.FileChooserParams
                                .parseResult(result.getResultCode(), result.getData());
                    }
                    filePathCallback.onReceiveValue(uris);
                    filePathCallback = null;
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tabs = new TabManager();
        contentContainer = findViewById(R.id.content_container);
        addressInput = findViewById(R.id.address_input);
        secureIcon = findViewById(R.id.secure_icon);
        browserLayout = findViewById(R.id.browser_layout);
        privacyPanel = findViewById(R.id.privacy_panel);
        firstLaunch = findViewById(R.id.first_launch);
        dashAds = findViewById(R.id.dash_ads);
        dashTrackers = findViewById(R.id.dash_trackers);
        dashHistory = findViewById(R.id.dash_history);
        dashScore = findViewById(R.id.dash_score);
        dashTodayAds = findViewById(R.id.dash_today_ads);
        dashTodayTrackers = findViewById(R.id.dash_today_trackers);

        // V2: feed the daily privacy report from the local filter engine.
        MrNobodyApp.filters().setBlockListener(category -> {
            if (category == FilterEngine.Category.AD) {
                MrNobodyApp.report().increment("ads");
            } else if (category == FilterEngine.Category.TRACKER) {
                MrNobodyApp.report().increment("trackers");
            }
        });

        wireToolbar();

        addressInput.setOnEditorActionListener((v, actionId, event) -> {
            navigate(addressInput.getText().toString());
            return true;
        });

        findViewById(R.id.addr_menu).setOnClickListener(v -> showMenu());

        if (MrNobodyApp.settings().isFirstLaunchDone()) {
            openInitialTab();
        } else {
            showFirstLaunch();
        }

        // Handle a VIEW intent (e.g. opening a link from another app).
        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            openInitialTab();
            loadUrl(intent.getData().toString());
        }

        // Debug overlay: floating circle with an error-count badge, expandable
        // on tap. Testers use it to surface failures instantly.
        new DebugOverlay((FrameLayout) findViewById(android.R.id.content));
    }

    private void applyTheme() {
        String theme = MrNobodyApp.settings().getTheme();
        int mode;
        switch (theme) {
            case Settings.THEME_DARK:  mode = AppCompatDelegate.MODE_NIGHT_YES; break;
            case Settings.THEME_LIGHT: mode = AppCompatDelegate.MODE_NIGHT_NO; break;
            default:                   mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void wireToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            Tab t = tabs.getActive();
            if (t != null && t.canGoBack()) t.goBack();
        });
        findViewById(R.id.btn_new_tab).setOnClickListener(v -> newTab(false));
        findViewById(R.id.btn_tabs).setOnClickListener(v -> showTabSwitcher());
        findViewById(R.id.btn_menu).setOnClickListener(v -> showMenu());

        secureIcon.setOnClickListener(v -> showPrivacyPanel());
        findViewById(R.id.privacy_back).setOnClickListener(v -> hidePrivacyPanel());

        findViewById(R.id.fl_start).setOnClickListener(v -> {
            MrNobodyApp.settings().setFirstLaunchDone();
            hideFirstLaunch();
            openInitialTab();
        });
        findViewById(R.id.fl_privacy).setOnClickListener(v -> {
            MrNobodyApp.settings().setFirstLaunchDone();
            hideFirstLaunch();
            startActivity(new Intent(this, SettingsActivity.class));
        });
    }

    // ------------------------------------------------------------------ tabs

    private void openInitialTab() {
        newTab(false);
        loadUrl("https://example.com");
    }

    private void newTab(boolean isPrivate) {
        Tab tab = tabs.newTab(isPrivate);
        attachTab(tab);
        addressInput.setText("");
        addressInput.requestFocus();
        applySecureFlag();
    }

    private void attachTab(Tab tab) {
        contentContainer.removeAllViews();
        WebView wv = tab.getWebView(this, webViewClient, webChromeClient, downloadListener);
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        contentContainer.addView(wv);
        updateAddressBar();
    }

    private void switchToTab(int index) {
        Tab tab = tabs.get(index);
        if (tab == null) return;
        tabs.setActive(index);
        attachTab(tab);
        applySecureFlag();
    }

    private void applySecureFlag() {
        Tab t = tabs.getActive();
        if (t != null && t.isPrivate()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    // -------------------------------------------------------------- navigation

    private void navigate(String input) {
        if (input == null) return;
        String query = input.trim();
        if (query.isEmpty()) return;

        // Intent routing — the key distinction:
        //   URL    → render in the visible browser (WebView)
        //   SEARCH → render the search engine's results page in the browser
        //            (a real rendered page — NOT raw HTML, NOT a task)
        //   TASK   → agentic pipeline (headless browser + tools), text result
        IntentType type = IntentRouter.route(query);
        switch (type) {
            case URL:
                loadUrl(toUrl(query));
                break;
            case SEARCH:
                // Bare query → search engine results, rendered like a browser.
                loadUrl(toUrl(query));
                break;
            case TASK:
            default:
                runTask(query);
                break;
        }
    }

    /** Create a persistent task and dispatch it (local worker, background). */
    private void runTask(String instruction) {
        long id = MrNobodyApp.tasks().insert(instruction);
        Task task = MrNobodyApp.tasks().get(id);
        if (task == null) {
            ErrorLog.record("failed to create task");
            return;
        }
        Toast.makeText(this, "Task started: " + instruction, Toast.LENGTH_SHORT).show();
        agentExecutor.execute(() -> {
            MrNobodyApp.dispatcher().dispatch(getApplicationContext(), task);
            MrNobodyApp.tasks().update(task);
            if (task.status() == Task.Status.FAILED) {
                ErrorLog.record("task failed: " + task.error());
            }
            runOnUiThread(() -> showTaskResult(task));
        });
    }

    private void showTaskResult(Task task) {
        String title = task.status() == Task.Status.COMPLETED ? "Task done" : "Task failed";
        String message = task.status() == Task.Status.COMPLETED ? task.result() : task.error();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(truncate(message, 1200))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private void loadUrl(String url) {
        Tab t = tabs.getActive();
        if (t == null) {
            newTab(false);
            t = tabs.getActive();
        }
        // V2: strip known tracking parameters before navigation (conservative,
        // can be disabled in Settings).
        if (MrNobodyApp.settings().isParamStrippingEnabled()) {
            url = TrackingParams.strip(url);
        }
        MrNobodyApp.filters().resetPageCounters();
        MrNobodyApp.report().increment("pages");
        applyPerSiteSettings(t, url);
        t.loadUrl(url);
        addressInput.setText(url);
        addressInput.clearFocus();
    }

    /**
     * Apply per-site overrides (V2) for the host being navigated to. Falls back
     * to global settings when no override is set. JavaScript and blocking are
     * applied per-load; cookies are enforced on the WebView.
     */
    private void applyPerSiteSettings(Tab tab, String url) {
        String host = hostOf(url);
        if (host == null || tab == null) return;
        WebView wv = tab.peekWebView();
        if (wv == null) return;

        Settings settings = MrNobodyApp.settings();
        PerSiteSettings perSite = MrNobodyApp.perSite();

        Boolean jsOverride = perSite.js(host);
        wv.getSettings().setJavaScriptEnabled(jsOverride != null ? jsOverride : settings.isJsEnabled());

        Boolean cookieOverride = perSite.cookies(host);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false);
        if (cookieOverride != null) {
            CookieManager.getInstance().setAcceptCookie(cookieOverride);
        } else {
            CookieManager.getInstance().setAcceptCookie(true);
        }
    }

    /** Extract the host from a URL, or null if it has none. */
    static String hostOf(String url) {
        if (url == null) return null;
        try {
            String host = new URI(url).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Turn raw address-bar input into a URL (or a search). */
    static String toUrl(String input) {
        String s = input.trim();
        if (s.isEmpty()) return s;
        if (URLUtil.isValidUrl(s)) return s; // has a scheme (http, https, ...)
        if (s.startsWith("localhost") || s.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$")) {
            return "http://" + s;
        }
        boolean looksLikeDomain = s.contains(".") && !s.contains(" ");
        if (looksLikeDomain) return "https://" + s;
        return MrNobodyApp.settings().getSearchEngine() + Uri.encode(s);
    }

    private void updateAddressBar() {
        Tab t = tabs.getActive();
        if (t == null) return;
        String url = t.getUrl();
        if (url == null || url.isEmpty()) {
            addressInput.setText("");
            secureIcon.setColorFilter(Color.parseColor("#5c5c62"));
            return;
        }
        addressInput.setText(url);
        boolean secure = url.startsWith("https://");
        secureIcon.setColorFilter(Color.parseColor(secure ? "#7f9c78" : "#c9a237"));
    }

    // ---------------------------------------------------------------- privacy

    private void showPrivacyPanel() {
        dashScore.setText(MrNobodyApp.filters().privacyScore() + " / 100");
        dashAds.setText(String.valueOf(MrNobodyApp.filters().getPageAdsBlocked()));
        dashTrackers.setText(String.valueOf(MrNobodyApp.filters().getPageTrackersBlocked()));
        dashTodayAds.setText(String.valueOf(MrNobodyApp.report().adsBlocked()));
        dashTodayTrackers.setText(String.valueOf(MrNobodyApp.report().trackersBlocked()));
        dashHistory.setText(MrNobodyApp.settings().isHistoryEnabled()
                ? getString(R.string.on_state) : getString(R.string.off_state));
        browserLayout.setVisibility(View.GONE);
        privacyPanel.setVisibility(View.VISIBLE);
    }

    private void hidePrivacyPanel() {
        privacyPanel.setVisibility(View.GONE);
        browserLayout.setVisibility(View.VISIBLE);
    }

    private void showFirstLaunch() {
        browserLayout.setVisibility(View.GONE);
        firstLaunch.setVisibility(View.VISIBLE);
    }

    private void hideFirstLaunch() {
        firstLaunch.setVisibility(View.GONE);
        browserLayout.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        Tab t = tabs.getActive();
        String host = t != null ? hostOf(t.getUrl()) : null;
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.menu_new_private));
        items.add(getString(R.string.menu_bookmark_page));
        items.add(getString(R.string.menu_bookmarks));
        if (host != null) items.add(getString(R.string.menu_site_settings, host));
        items.add(getString(R.string.menu_reports));
        items.add(getString(R.string.settings_title));
        items.add(getString(R.string.downloads_title));
        items.add(getString(R.string.menu_close_all));

        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String label = items.get(which);
                    if (label.equals(getString(R.string.menu_new_private))) {
                        newTab(true);
                    } else if (label.equals(getString(R.string.menu_bookmark_page))) {
                        bookmarkCurrentPage();
                    } else if (label.equals(getString(R.string.menu_bookmarks))) {
                        showBookmarks();
                    } else if (label.equals(getString(R.string.menu_reports))) {
                        showReportDialog();
                    } else if (label.equals(getString(R.string.settings_title))) {
                        startActivity(new Intent(this, SettingsActivity.class));
                    } else if (label.equals(getString(R.string.downloads_title))) {
                        openSystemDownloads();
                    } else if (label.equals(getString(R.string.menu_close_all))) {
                        closeAllTabs();
                    } else {
                        // Site privacy (for host)
                        showSiteSettingsDialog(host);
                    }
                })
                .show();
    }

    private void bookmarkCurrentPage() {
        Tab t = tabs.getActive();
        if (t == null || t.getUrl().isEmpty()) {
            Toast.makeText(this, "Nothing to bookmark", Toast.LENGTH_SHORT).show();
            return;
        }
        long id = MrNobodyApp.bookmarks().add(t.getTitle(), t.getUrl(), "");
        Toast.makeText(this, id >= 0 ? "Bookmarked" : "Already bookmarked?",
                Toast.LENGTH_SHORT).show();
    }

    private void showBookmarks() {
        List<BookmarksStore.Bookmark> list = MrNobodyApp.bookmarks().all();
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.bookmarks_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            labels[i] = list.get(i).title;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.bookmarks_title)
                .setItems(labels, (d, which) -> loadUrl(list.get(which).url))
                .show();
    }

    private void showReportDialog() {
        long ads = MrNobodyApp.report().adsBlocked();
        long trackers = MrNobodyApp.report().trackersBlocked();
        long pages = MrNobodyApp.report().pagesLoaded();
        String msg = getString(R.string.report_ads) + ": " + ads + "\n"
                + getString(R.string.report_trackers) + ": " + trackers + "\n"
                + getString(R.string.report_pages) + ": " + pages + "\n\n"
                + getString(R.string.report_no_history_note);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.report_today))
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showSiteSettingsDialog(String host) {
        PerSiteSettings perSite = MrNobodyApp.perSite();
        Settings global = MrNobodyApp.settings();

        Boolean jsOverride = perSite.js(host);
        boolean jsCurrent = jsOverride != null ? jsOverride : global.isJsEnabled();
        Boolean blockOverride = perSite.blocking(host);
        boolean blockCurrent = blockOverride != null ? blockOverride : global.isBlockingEnabled();
        Boolean locOverride = perSite.location(host);
        boolean locCurrent = locOverride != null ? locOverride : false;

        String[] items = {
                getString(R.string.site_blocking) + ": " + onOff(blockCurrent),
                getString(R.string.site_js) + ": " + onOff(jsCurrent),
                getString(R.string.site_location) + ": " + onOff(locCurrent),
                getString(R.string.site_reset)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu_site_settings, host))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: perSite.setBlocking(host, !blockCurrent);
                                MrNobodyApp.filters().setEnabled(!blockCurrent); break;
                        case 1: perSite.setJs(host, !jsCurrent);
                                applyJsToActiveTab(!jsCurrent); break;
                        case 2: perSite.setLocation(host, !locCurrent); break;
                        case 3: perSite.clear(host);
                                MrNobodyApp.filters().setEnabled(global.isBlockingEnabled());
                                applyJsToActiveTab(global.isJsEnabled());
                                break;
                    }
                    Toast.makeText(this, "Site setting updated", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void applyJsToActiveTab(boolean enabled) {
        Tab t = tabs.getActive();
        if (t != null && t.peekWebView() != null) {
            t.peekWebView().getSettings().setJavaScriptEnabled(enabled);
        }
    }

    private static String onOff(boolean b) {
        return b ? "ON" : "OFF";
    }

    private void openSystemDownloads() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.downloads_title, Toast.LENGTH_SHORT).show();
        }
    }

    private void closeAllTabs() {
        tabs.closeAll();
        newTab(false);
    }

    private void showTabSwitcher() {
        List<Tab> all = tabs.all();
        List<String> labels = new ArrayList<>();
        for (Tab t : all) {
            labels.add((t.isPrivate() ? "🕶 " : "") + t.label());
        }
        labels.add("+ " + getString(R.string.toolbar_new_tab));
        labels.add(getString(R.string.menu_new_private));

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.toolbar_tabs))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < all.size()) {
                        switchToTab(which);
                    } else if (which == all.size()) {
                        newTab(false);
                    } else {
                        newTab(true);
                    }
                })
                .show();
    }

    // -------------------------------------------------------- webview clients

    private final WebViewClient webViewClient = new WebViewClient() {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return false; // load in this tab
            }
            // External schemes: mailto, tel, geo, market, intent, ...
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
            } catch (ActivityNotFoundException ignored) {
                // No app handles it — do nothing rather than crash.
            }
            return true;
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request.isForMainFrame()) {
                // New page: reset the per-page counters shown on the dashboard.
                MrNobodyApp.filters().resetPageCounters();
                return null;
            }
            FilterEngine.Category cat =
                    MrNobodyApp.filters().shouldBlock(request.getUrl().toString());
            if (cat != FilterEngine.Category.NONE) {
                return new WebResourceResponse("text/plain", "utf-8",
                        new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Tab t = tabs.getActive();
            if (t != null) {
                t.setUrl(url);
                if (!t.isPrivate()) {
                    MrNobodyApp.history().add(url, view.getTitle());
                }
            }
            updateAddressBar();
        }
    };

    private final WebChromeClient webChromeClient = new WebChromeClient() {
        @Override
        public void onReceivedTitle(WebView view, String title) {
            Tab t = tabs.getActive();
            if (t != null) t.setTitle(title);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> showPermissionDialog(request));
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            runOnUiThread(() -> showGeolocationDialog(origin, callback));
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            try {
                fileChooserLauncher.launch(params.createIntent());
            } catch (Exception e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }
    };

    private final android.webkit.DownloadListener downloadListener =
            (url, userAgent, contentDisposition, mimetype, contentLength) -> {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) return;
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimetype);
                req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
                req.setTitle(name);
                req.addRequestHeader("User-Agent", userAgent);
                try {
                    dm.enqueue(req);
                    Toast.makeText(this, getString(R.string.downloads_title) + ": " + name,
                            Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
                }
            };

    // ------------------------------------------------------------ permissions

    private void showPermissionDialog(PermissionRequest request) {
        pendingPermissionRequest = request;
        String origin = originOf(request.getOrigin());
        String what = describeResources(request.getResources());
        String[] requested = request.getResources();
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.perm_wants, origin, what))
                .setNegativeButton(R.string.perm_block, (d, w) -> {
                    request.deny();
                    pendingPermissionRequest = null;
                })
                .setPositiveButton(R.string.perm_allow, (d, w) -> {
                    // V2: record the grant in the local permission dashboard.
                    recordPermissionGrants(origin, requested);
                    String[] perms = resourcesToRuntimePermissions(requested);
                    if (perms.length == 0) {
                        request.grant(requested);
                        pendingPermissionRequest = null;
                    } else {
                        ActivityCompat.requestPermissions(this, perms, REQ_PERMISSIONS);
                    }
                })
                .show();
    }

    private void recordPermissionGrants(String host, String[] resources) {
        if (host == null || host.isEmpty() || host.equals("website")) return;
        PermissionStore store = MrNobodyApp.permissions();
        for (String r : resources) {
            if (r.contains("VIDEO_CAPTURE")) store.grant(host, PermissionStore.CAMERA);
            else if (r.contains("AUDIO_CAPTURE")) store.grant(host, PermissionStore.MICROPHONE);
        }
    }

    private void showGeolocationDialog(String origin, GeolocationPermissions.Callback callback) {
        pendingGeoCallback = callback;
        pendingGeoOrigin = origin;
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.perm_wants, origin, "location"))
                .setNegativeButton(R.string.perm_block, (d, w) -> {
                    callback.invoke(origin, false, false);
                    pendingGeoCallback = null;
                })
                .setPositiveButton(R.string.perm_allow, (d, w) ->
                        ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                REQ_PERMISSIONS))
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (pendingPermissionRequest != null) {
            if (granted) pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            else pendingPermissionRequest.deny();
            pendingPermissionRequest = null;
        }
        if (pendingGeoCallback != null) {
            pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            if (granted && pendingGeoOrigin != null && !pendingGeoOrigin.isEmpty()) {
                MrNobodyApp.permissions().grant(pendingGeoOrigin, PermissionStore.LOCATION);
            }
            pendingGeoCallback = null;
        }
    }

    private static String originOf(Uri origin) {
        if (origin == null) return "website";
        String host = origin.getHost();
        return host == null ? origin.toString() : host;
    }

    private static String describeResources(String[] resources) {
        boolean cam = false, mic = false, loc = false;
        for (String r : resources) {
            if (r.contains("VIDEO_CAPTURE")) cam = true;
            else if (r.contains("AUDIO_CAPTURE")) mic = true;
            else if (r.contains("LOCATION") || r.contains("GEOLOCATION")) loc = true;
        }
        List<String> parts = new ArrayList<>();
        if (cam) parts.add("camera");
        if (mic) parts.add("microphone");
        if (loc) parts.add("location");
        if (parts.isEmpty()) parts.add("device");
        return String.join(" and ", parts);
    }

    private static String[] resourcesToRuntimePermissions(String[] resources) {
        List<String> perms = new ArrayList<>();
        for (String r : resources) {
            if (r.contains("VIDEO_CAPTURE")) perms.add(Manifest.permission.CAMERA);
            else if (r.contains("AUDIO_CAPTURE")) perms.add(Manifest.permission.RECORD_AUDIO);
        }
        return perms.toArray(new String[0]);
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        Tab t = tabs.getActive();
        if (t != null) t.onResume();
    }

    @Override
    protected void onPause() {
        Tab t = tabs.getActive();
        if (t != null) t.onPause();
        MrNobodyApp.filters().persist(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        tabs.closeAll();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        Tab t = tabs.getActive();
        if (privacyPanel.getVisibility() == View.VISIBLE) {
            hidePrivacyPanel();
        } else if (firstLaunch.getVisibility() == View.VISIBLE) {
            super.onBackPressed();
        } else if (t != null && t.canGoBack()) {
            t.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
