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

import com.mrnobody.browser.blocking.FilterEngine;
import com.mrnobody.browser.core.Settings;
import com.mrnobody.browser.ui.Tab;
import com.mrnobody.browser.ui.TabManager;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    // Pending web permission requests (camera/mic/location).
    private PermissionRequest pendingPermissionRequest;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    private ValueCallback<Uri[]> filePathCallback;

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
        String url = toUrl(query);
        loadUrl(url);
    }

    private void loadUrl(String url) {
        Tab t = tabs.getActive();
        if (t == null) {
            newTab(false);
            t = tabs.getActive();
        }
        MrNobodyApp.filters().resetPageCounters();
        t.loadUrl(url);
        addressInput.setText(url);
        addressInput.clearFocus();
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
        dashAds.setText(String.valueOf(MrNobodyApp.filters().getPageAdsBlocked()));
        dashTrackers.setText(String.valueOf(MrNobodyApp.filters().getPageTrackersBlocked()));
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
        String[] items = {
                getString(R.string.menu_new_private),
                getString(R.string.settings_title),
                getString(R.string.downloads_title),
                getString(R.string.menu_close_all)
        };
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: newTab(true); break;
                        case 1: startActivity(new Intent(this, SettingsActivity.class)); break;
                        case 2: openSystemDownloads(); break;
                        case 3: closeAllTabs(); break;
                    }
                })
                .show();
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
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.perm_wants, origin, what))
                .setNegativeButton(R.string.perm_block, (d, w) -> {
                    request.deny();
                    pendingPermissionRequest = null;
                })
                .setPositiveButton(R.string.perm_allow, (d, w) -> {
                    String[] perms = resourcesToRuntimePermissions(request.getResources());
                    if (perms.length == 0) {
                        request.grant(request.getResources());
                        pendingPermissionRequest = null;
                    } else {
                        ActivityCompat.requestPermissions(this, perms, REQ_PERMISSIONS);
                    }
                })
                .show();
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
