package com.mrnobody.browser;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.planner.IntentRouter;
import com.mrnobody.agent.planner.IntentType;
import com.mrnobody.browser.blocking.FilterEngine;
import com.mrnobody.browser.blocking.TrackingParams;
import com.mrnobody.browser.core.BookmarksStore;
import com.mrnobody.browser.core.PerSiteSettings;
import com.mrnobody.browser.core.PermissionStore;
import com.mrnobody.browser.core.Settings;
import com.mrnobody.browser.deeplink.DeepLinkHandler;
import com.mrnobody.browser.ui.Tab;
import com.mrnobody.browser.ui.TabManager;
import com.mrnobody.browser.ui.TabStateStore;
import com.mrnobody.debug.DebugOverlay;
import com.mrnobody.debug.ErrorLog;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
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
    private TabStateStore tabState;
    private FrameLayout contentContainer;
    private View bottomNav;
    private DebugOverlay debugOverlay;
    private boolean toolbarHidden = false;

    private EditText addressInput;
    private ImageView secureIcon;
    private View browserLayout, privacyPanel, firstLaunch;

    // Agent Home (S2) overlay — the "new tab" landing: logo, search, tasks, shortcuts.
    private View homePanel;
    private EditText homeInput;
    private LinearLayout homeTasks, homeShortcuts;

    // Native Sessions (S3) and Tasks (S5) overlay screens.
    private View sessionsPanel, tasksPanel;
    private LinearLayout sessionsList, tasksList;

    // Additional screens: Downloads (S8), Clear Data (S7), Task detail (S2 state).
    private View downloadsPanel, clearPanel, detailPanel;
    private LinearLayout downloadsList, clearList, detailList;

    // Privacy dashboard (S4) — rebuilt programmatically (card + metric rows).
    private LinearLayout privacyList;

    // Clear-data checkbox state (history, cookies, cache, site data, tasks, workspace).
    private final boolean[] clearChecks = {true, true, true, false, false, false};

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

        // Draw edge-to-edge and apply real system-bar insets (status bar / notch
        // top, gesture bar bottom) so nothing is cut off at the screen edge.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        applySystemBarInsets();

        tabs = new TabManager();
        tabState = new TabStateStore(this);
        contentContainer = findViewById(R.id.content_container);
        bottomNav = findViewById(R.id.bottom_nav);
        addressInput = findViewById(R.id.address_input);
        secureIcon = findViewById(R.id.secure_icon);
        browserLayout = findViewById(R.id.browser_layout);
        firstLaunch = findViewById(R.id.first_launch);

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
            restoreOrStart();
        } else {
            showFirstLaunch();
        }

        // Build the Sessions and Tasks screens (native overlays).
        buildPanels();
        buildHomePanel();

        // Debug overlay: floating circle with an error-count badge, expandable
        // on tap. Testers use it to surface failures instantly. Added last so it
        // stays on top of the screens.
        debugOverlay = new DebugOverlay((FrameLayout) findViewById(android.R.id.content));

        // Cold-start deep link / shared URL: reconstruct state, then route.
        handleIntent(getIntent());
    }

    /**
     * State memory: restore the previous tab session, or start fresh. Restoring
     * "you had N tabs open" is ephemeral UI state — it never writes history
     * (the history store stays OFF by default and is entirely separate).
     */
    private void restoreOrStart() {
        if (tabState.hasSavedState() && tabState.wasLaunched()) {
            restoreSession();
            return;
        }
        openInitialTab();
    }

    private void restoreSession() {
        List<TabStateStore.Snapshot> snaps = tabState.loadTabs();
        tabs.setNextId(tabState.loadNextId());
        if (snaps.isEmpty()) {
            openInitialTab();
            return;
        }
        for (TabStateStore.Snapshot s : snaps) {
            tabs.restoreTab(s.id, s.isPrivate, s.url, s.title);
        }
        int activeId = tabState.loadActiveId();
        if (activeId >= 0 && tabs.findById(activeId) != null) {
            tabs.setActiveById(activeId);
        }
        Tab active = tabs.getActive();
        if (active == null) {
            tabs.setActive(0);
            active = tabs.getActive();
        }
        // Attach the active tab; other tabs' WebViews are created lazily on switch.
        if (active != null) {
            if (active.getUrl().isEmpty()) {
                attachTab(active);
                addressInput.setText("");
            } else {
                attachTab(active);
                loadUrl(active.getUrl());
            }
            applySecureFlag();
        }
    }

    /**
     * Persist tab state after any mutation (navigation, new/close tab, switch).
     * Called on the UI thread; cheap enough for a handful of tabs.
     */
    private void persistTabs() {
        tabState.save(tabs.all(), tabs.getActive(), tabs.nextId());
    }

    // ------------------------------------------------------------ deep links

    /** Route an incoming intent: a shared http(s) URL, or an mrnobody:// link. */
    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null) return;
        String uri = data.toString();

        if (DeepLinkHandler.isWebUrl(uri)) {
            ensureLaunched();
            loadUrl(uri);               // a URL shared from another app
            return;
        }
        handleDeepLink(DeepLinkHandler.parse(uri));
    }

    /** If we're still on the first-launch screen, dismiss it so routing is visible. */
    private void ensureLaunched() {
        if (firstLaunch != null && firstLaunch.getVisibility() == View.VISIBLE) {
            MrNobodyApp.settings().setFirstLaunchDone();
            hideFirstLaunch();
        }
    }

    private void handleDeepLink(DeepLinkHandler link) {
        if (link == null || link.action == DeepLinkHandler.Action.NONE) {
            return;                     // unknown link — ignore silently
        }
        ensureLaunched();
        switch (link.action) {
            case OPEN:
                if (link.arg != null && !link.arg.isEmpty()) loadUrl(link.arg);
                break;
            case SEARCH:
                if (link.arg != null && !link.arg.isEmpty()) loadUrl(toUrl(link.arg));
                break;
            case TASK:
                if (link.arg != null && !link.arg.isEmpty()) runTask(link.arg);
                break;
            case TABS:      showSessions(); break;
            case TASKS:     showTasks(); break;
            case SETTINGS:  startActivity(new Intent(this, SettingsActivity.class)); break;
            case PRIVACY:   showPrivacyPanel(); break;
            case DOWNLOADS: showDownloads(); break;
            case CLEAR:     showClearData(); break;
            default: break;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);              // keep getIntent() in sync
        handleIntent(intent);           // warm-start route, no state reset
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

    /**
     * Pad the address bar by the status-bar/notch inset and the bottom nav by the
     * navigation-bar/gesture inset. This replaces the old fixed 48dp margins, so
     * content clears every device's cutout/gesture area correctly.
     */
    private void applySystemBarInsets() {
        final View addressBar = findViewById(R.id.address_bar);
        final View bottomNav = findViewById(R.id.bottom_nav);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (addressBar != null) {
                addressBar.setPadding(addressBar.getPaddingLeft(),
                        bars.top + dp(8),
                        addressBar.getPaddingRight(),
                        addressBar.getPaddingBottom());
            }
            if (bottomNav != null) {
                bottomNav.setPadding(bottomNav.getPaddingLeft(),
                        bottomNav.getPaddingTop(),
                        bottomNav.getPaddingRight(),
                        bars.bottom);
            }
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void wireToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            Tab t = tabs.getActive();
            if (t != null && t.canGoBack()) t.goBack();
        });

        buildBottomNav();

        secureIcon.setOnClickListener(v -> showPrivacyPanel());

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
        // The "new tab" landing is the Agent Home, not a hardcoded URL.
        showHome();
    }

    private void newTab(boolean isPrivate) {
        Tab tab = tabs.newTab(isPrivate);
        attachTab(tab);
        addressInput.setText("");
        addressInput.requestFocus();
        applySecureFlag();
        persistTabs();
    }

    private void attachTab(Tab tab) {
        contentContainer.removeAllViews();
        WebView wv = tab.getWebView(this, webViewClient, webChromeClient, downloadListener);
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        contentContainer.addView(wv);
        // Hide the bottom toolbar (and drift the debug FAB) when scrolling down,
        // reveal on scroll up — smooth, never jumps. API 23+ (minSdk is 31).
        wv.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            int delta = sy - osy;
            if (sy > dp(48) && delta > dp(3)) {
                setToolbarCollapsed(true);
            } else if (delta < -dp(3)) {
                setToolbarCollapsed(false);
            }
        });
        updateAddressBar();
    }

    /** Slide the bottom toolbar out/in and drift the debug FAB in sync. */
    private void setToolbarCollapsed(boolean collapsed) {
        if (toolbarHidden == collapsed) return;
        toolbarHidden = collapsed;
        if (bottomNav != null) {
            float targetY = collapsed ? (bottomNav.getHeight() + dp(48)) : 0f;
            bottomNav.animate()
                    .translationY(targetY)
                    .alpha(collapsed ? 0f : 1f)
                    .setDuration(250)
                    .start();
        }
        if (debugOverlay != null) {
            debugOverlay.setCollapsed(collapsed);
        }
    }

    /**
     * Build the monochrome bottom navigation: Home · Tabs · (+) · Tasks · Settings,
     * with a raised circular "+" floating above the center slot.
     */
    private void buildBottomNav() {
        FrameLayout container = findViewById(R.id.bottom_nav);
        if (container == null) return;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(color(com.mrnobody.browser.R.color.surface));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(58));
        barLp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(barLp);
        container.addView(bar);

        addNavItem(bar, R.drawable.ic_home, "Home", v -> showHome());
        addNavItem(bar, R.drawable.ic_layers, "Tabs", v -> showSessions());

        // center spacer reserves room for the raised "+"
        Space spacer = new Space(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        addNavItem(bar, R.drawable.ic_checklist, "Tasks", v -> showTasks());
        addNavItem(bar, R.drawable.ic_settings, "Settings", v -> startActivity(new Intent(this, SettingsActivity.class)));

        // raised circular "+" — opens the Agent Home (a fresh "new tab")
        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextSize(24);
        plus.setGravity(Gravity.CENTER);
        plus.setTextColor(color(com.mrnobody.browser.R.color.accent_ink));
        plus.setTypeface(Typeface.DEFAULT_BOLD);
        plus.setBackground(circleDrawable(color(com.mrnobody.browser.R.color.accent)));
        FrameLayout.LayoutParams plusLp = new FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        plusLp.topMargin = -dp(24);
        plus.setLayoutParams(plusLp);
        plus.setOnClickListener(v -> showHome());
        container.addView(plus);
    }

    private void addNavItem(LinearLayout bar, int iconRes, String label, View.OnClickListener onClick) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setPadding(0, dp(7), 0, dp(7));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(color(com.mrnobody.browser.R.color.text_dim));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        item.addView(icon, iconLp);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(9);
        lbl.setTypeface(Typeface.MONOSPACE);
        lbl.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        lbl.setGravity(Gravity.CENTER);
        item.addView(lbl);

        item.setOnClickListener(onClick);
        bar.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    }

    private android.graphics.drawable.GradientDrawable circleDrawable(int fill) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    private void switchToTab(int index) {
        Tab tab = tabs.get(index);
        if (tab == null) return;
        tabs.setActive(index);
        attachTab(tab);
        applySecureFlag();
        persistTabs();
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

    /** Create a persistent task and enqueue it for background (resumable) execution. */
    private void runTask(String instruction) {
        long id = MrNobodyApp.tasks().insert(instruction);
        if (id < 0) {
            ErrorLog.record("failed to create task");
            return;
        }
        Toast.makeText(this, "Task started: " + instruction, Toast.LENGTH_SHORT).show();

        // Enqueue via WorkManager so the task survives process death and runs
        // to completion even if the user leaves the app (spec §13).
        MrNobodyApp.scheduler().schedule(getApplicationContext(), id);
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
            secureIcon.setColorFilter(Color.parseColor("#8a8a8f"));
            return;
        }
        addressInput.setText(url);
        boolean secure = url.startsWith("https://");
        secureIcon.setColorFilter(Color.parseColor(secure ? "#fafafa" : "#c4c4c7"));
    }

    // ---------------------------------------------------------------- privacy

    private void showPrivacyPanel() {
        renderPrivacy();
        hideAllPanels();
        privacyPanel.setVisibility(View.VISIBLE);
    }

    private void hidePrivacyPanel() {
        privacyPanel.setVisibility(View.GONE);
        browserLayout.setVisibility(View.VISIBLE);
        setToolbarCollapsed(false);
    }

    /** Render the Privacy dashboard: This page / Today / Cookies / History metric cards. */
    private void renderPrivacy() {
        privacyList.removeAllViews();
        FilterEngine f = MrNobodyApp.filters();

        privacyList.addView(sectionLabel(getString(R.string.privacy_this_page)));
        LinearLayout page = card();
        page.addView(metricRow(getString(R.string.privacy_score), f.privacyScore() + " / 100", false));
        page.addView(metricRow(getString(R.string.privacy_ads_blocked), String.valueOf(f.getPageAdsBlocked()), false));
        page.addView(metricRow(getString(R.string.privacy_trackers_blocked), String.valueOf(f.getPageTrackersBlocked()), false));
        privacyList.addView(page);

        privacyList.addView(sectionLabel(getString(R.string.privacy_today)));
        LinearLayout today = card();
        today.addView(metricRow(getString(R.string.privacy_ads_blocked), String.valueOf(MrNobodyApp.report().adsBlocked()), false));
        today.addView(metricRow(getString(R.string.privacy_trackers_blocked), String.valueOf(MrNobodyApp.report().trackersBlocked()), false));
        privacyList.addView(today);

        privacyList.addView(sectionLabel(getString(R.string.privacy_cookies)));
        LinearLayout cookies = card();
        cookies.addView(metricRow(getString(R.string.privacy_third_party), getString(R.string.privacy_cookies_blocked), true));
        privacyList.addView(cookies);

        privacyList.addView(sectionLabel(getString(R.string.privacy_history)));
        LinearLayout history = card();
        history.addView(metricRow(getString(R.string.privacy_history_saved),
                MrNobodyApp.settings().isHistoryEnabled() ? getString(R.string.on_state) : getString(R.string.off_state), true));
        privacyList.addView(history);

        privacyList.addView(emptyRow(getString(R.string.privacy_stays_local)));
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
        items.add(getString(R.string.menu_tasks));
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
                    } else if (label.equals(getString(R.string.menu_tasks))) {
                        showTasks();
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
        // Open the native Downloads screen (spec S8), not the system manager.
        showDownloads();
    }

    private void closeAllTabs() {
        tabs.closeAll();
        newTab(false);
    }

    // ----------------------------------------------- Sessions (S3) & Tasks (S5)

    /** Build the two native overlay screens and attach them to the content root. */
    private void buildPanels() {
        FrameLayout root = (FrameLayout) findViewById(android.R.id.content);

        sessionsPanel = buildPanel(getString(R.string.sessions_title),
                v -> hideSessions(), container -> sessionsList = container);
        root.addView(sessionsPanel);

        tasksPanel = buildPanel(getString(R.string.tasks_title),
                v -> hideTasks(), container -> tasksList = container);
        root.addView(tasksPanel);

        downloadsPanel = buildPanel(getString(R.string.downloads_title),
                v -> hideDownloads(), container -> downloadsList = container);
        root.addView(downloadsPanel);

        clearPanel = buildPanel(getString(R.string.clear_title),
                v -> hideClearData(), container -> clearList = container);
        root.addView(clearPanel);

        detailPanel = buildPanel("Task",
                v -> hideTaskDetail(), container -> detailList = container);
        root.addView(detailPanel);

        privacyPanel = buildPanel(getString(R.string.privacy_title),
                v -> hidePrivacyPanel(), container -> privacyList = container);
        root.addView(privacyPanel);
    }

    private interface ListRef {
        void set(LinearLayout list);
    }

    /** Create a full-screen panel: header (‹ back + title) + scrollable list. */
    private View buildPanel(String title, View.OnClickListener back, ListRef listRef) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(color(com.mrnobody.browser.R.color.bg));
        panel.setVisibility(View.GONE);

        panel.addView(panelHeader(title, back));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        listRef.set(list);
        return panel;
    }

    private LinearLayout panelHeader(String title, View.OnClickListener back) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(12), dp(8));
        row.setBackgroundColor(color(com.mrnobody.browser.R.color.surface));

        ImageView backBtn = new ImageView(this);
        backBtn.setImageResource(R.drawable.ic_chevron_left);
        backBtn.setColorFilter(color(com.mrnobody.browser.R.color.text_dim));
        backBtn.setPadding(dp(12), dp(8), dp(14), dp(8));
        backBtn.setOnClickListener(back);
        row.addView(backBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(color(com.mrnobody.browser.R.color.text));
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t);
        return row;
    }

    // ------------------------------------------------------------- Agent Home

    /** Build the Agent Home overlay: big logo, unified search, active tasks, shortcuts. */
    private void buildHomePanel() {
        FrameLayout root = (FrameLayout) findViewById(android.R.id.content);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(color(com.mrnobody.browser.R.color.bg));
        panel.setVisibility(View.GONE);
        homePanel = panel;

        // safe-area top spacing
        panel.setPadding(0, dp(48), 0, 0);

        // big centered logo mark
        TextView logo = new TextView(this);
        logo.setText("MR NOBODY");
        logo.setTextSize(26);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextColor(color(com.mrnobody.browser.R.color.text));
        logo.setGravity(Gravity.CENTER);
        logo.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        logoLp.topMargin = dp(28);
        logoLp.bottomMargin = dp(20);
        panel.addView(logo, logoLp);

        // unified search pill
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(pillBackground());
        pill.setPadding(dp(16), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        pillLp.leftMargin = dp(16);
        pillLp.rightMargin = dp(16);
        pill.setLayoutParams(pillLp);

        homeInput = new EditText(this);
        homeInput.setHint("Ask Mr Nobody or enter URL…");
        homeInput.setTextColor(color(com.mrnobody.browser.R.color.text));
        homeInput.setHintTextColor(color(com.mrnobody.browser.R.color.text_faint));
        homeInput.setTextSize(14);
        homeInput.setSingleLine(true);
        homeInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        homeInput.setPadding(0, 0, dp(8), 0);
        homeInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        homeInput.setOnEditorActionListener((v, actionId, event) -> {
            navigateFromHome(homeInput.getText().toString());
            return true;
        });
        pill.addView(homeInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        ImageView go = new ImageView(this);
        go.setImageResource(R.drawable.ic_arrow_forward);
        go.setColorFilter(color(com.mrnobody.browser.R.color.accent_ink));
        go.setPadding(dp(9), dp(9), dp(9), dp(9));
        go.setBackground(circleDrawable(color(com.mrnobody.browser.R.color.accent)));
        go.setOnClickListener(v -> navigateFromHome(homeInput.getText().toString()));
        pill.addView(go, new LinearLayout.LayoutParams(dp(36), dp(36)));
        panel.addView(pill);

        // scrollable body: active tasks + shortcuts
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(24));
        scroll.addView(body);

        body.addView(sectionLabel("Active tasks"));
        homeTasks = new LinearLayout(this);
        homeTasks.setOrientation(LinearLayout.VERTICAL);
        homeTasks.setBackground(cardBackground());
        LinearLayout.LayoutParams tasksLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tasksLp.leftMargin = dp(16);
        tasksLp.rightMargin = dp(16);
        homeTasks.setLayoutParams(tasksLp);
        body.addView(homeTasks);

        body.addView(sectionLabel("Shortcuts"));
        homeShortcuts = new LinearLayout(this);
        homeShortcuts.setOrientation(LinearLayout.VERTICAL);
        homeShortcuts.setBackground(cardBackground());
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.leftMargin = dp(16);
        scLp.rightMargin = dp(16);
        homeShortcuts.setLayoutParams(scLp);
        body.addView(homeShortcuts);

        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(panel);
    }

    private void showHome() {
        renderHome();
        hideAllPanels();
        homePanel.setVisibility(View.VISIBLE);
        browserLayout.setVisibility(View.GONE);
        homeInput.setText("");
        homeInput.requestFocus();
    }

    private void hideHome() {
        homePanel.setVisibility(View.GONE);
        browserLayout.setVisibility(View.VISIBLE);
        setToolbarCollapsed(false);
    }

    /** Home's unified input creates a fresh tab, then routes like the address bar. */
    private void navigateFromHome(String input) {
        if (input == null || input.trim().isEmpty()) return;
        newTab(false);
        hideHome();
        navigate(input.trim());
    }

    private void renderHome() {
        // active (running) tasks
        homeTasks.removeAllViews();
        List<Task> tasks = MrNobodyApp.tasks().recent(20);
        List<Task> live = new ArrayList<>();
        for (Task t : tasks) {
            if (t.status() == Task.Status.RUNNING || t.status() == Task.Status.QUEUED
                    || t.status() == Task.Status.WAITING) {
                live.add(t);
            }
        }
        if (live.isEmpty()) {
            homeTasks.addView(emptyRow("No active tasks"));
        } else {
            for (Task t : live) {
                homeTasks.addView(homeTaskRow(t));
            }
        }

        // shortcuts
        homeShortcuts.removeAllViews();
        homeShortcuts.addView(homeShortcutRow(R.drawable.ic_layers, "Tabs", v -> showSessions()));
        homeShortcuts.addView(homeShortcutRow(R.drawable.ic_checklist, "Tasks", v -> showTasks()));
        homeShortcuts.addView(homeShortcutRow(R.drawable.ic_download, "Downloads", v -> openSystemDownloads()));
        homeShortcuts.addView(homeShortcutRow(R.drawable.ic_settings, "Settings",
                v -> startActivity(new Intent(this, SettingsActivity.class))));
    }

    private View homeTaskRow(Task task) {
        LinearLayout row = baseRow();
        row.setOnClickListener(v -> showTaskDetail(task));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(task.instruction());
        name.setTextColor(color(com.mrnobody.browser.R.color.text));
        name.setTextSize(13);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        TextView meta = new TextView(this);
        meta.setText(task.currentStep() == null ? "" : task.currentStep());
        meta.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        meta.setTextSize(10);
        meta.setTypeface(Typeface.MONOSPACE);
        col.addView(meta);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = new TextView(this);
        chip.setText("RUNNING");
        chip.setTextSize(9);
        chip.setTypeface(Typeface.MONOSPACE);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        chip.setTextColor(color(com.mrnobody.browser.R.color.accent_ink));
        chip.setBackground(pillBackground());
        row.addView(chip);
        return row;
    }

    private View homeShortcutRow(int iconRes, String label, View.OnClickListener onClick) {
        LinearLayout row = baseRow();
        row.setOnClickListener(onClick);
        ImageView g = new ImageView(this);
        g.setImageResource(iconRes);
        g.setColorFilter(color(com.mrnobody.browser.R.color.text_dim));
        g.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.addView(g, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color(com.mrnobody.browser.R.color.text));
        t.setTextSize(13);
        t.setPadding(dp(12), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private android.graphics.drawable.GradientDrawable cardBackground() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color(com.mrnobody.browser.R.color.surface));
        d.setCornerRadius(dp(16));
        d.setStroke(dp(1), color(com.mrnobody.browser.R.color.border_soft));
        return d;
    }

    private android.graphics.drawable.GradientDrawable pillBackground() {
        return pillBackground(color(com.mrnobody.browser.R.color.surface));
    }

    private android.graphics.drawable.GradientDrawable pillBackground(int fill) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(24));
        d.setStroke(dp(1), color(com.mrnobody.browser.R.color.border_soft));
        return d;
    }

    // -------------------------------------------------------------- Sessions

    private void showSessions() {
        renderSessions();
        hideAllPanels();
        sessionsPanel.setVisibility(View.VISIBLE);
    }

    private void hideSessions() {
        sessionsPanel.setVisibility(View.GONE);
        browserLayout.setVisibility(View.VISIBLE);
        setToolbarCollapsed(false);
    }

    private void renderSessions() {
        sessionsList.removeAllViews();
        List<Tab> all = tabs.all();
        Tab active = tabs.getActive();

        // ---- open tabs as a 2-column card grid (with thumbnail previews) ----
        sessionsList.addView(sectionLabel(getString(R.string.sessions_tabs_section)));

        if (all.isEmpty()) {
            sessionsList.addView(emptyRow(getString(R.string.sessions_empty)));
        } else {
            android.widget.GridLayout grid = new android.widget.GridLayout(this);
            grid.setColumnCount(2);
            grid.setPadding(dp(16), 0, dp(16), dp(8));
            for (int i = 0; i < all.size(); i++) {
                final int index = i;
                Tab tab = all.get(i);
                boolean isActive = (tab == active);
                grid.addView(tabCard(tab, isActive, () -> {
                    switchToTab(index);
                    hideSessions();
                }));
            }
            // "+ new tab" dashed card
            grid.addView(newTabCard(() -> {
                newTab(false);
                hideSessions();
            }));
            sessionsList.addView(grid);
        }

        // ---- live task sessions ----
        sessionsList.addView(sectionLabel(getString(R.string.sessions_tasks_section)));
        List<Task> tasks = MrNobodyApp.tasks().recent(20);
        List<Task> live = new ArrayList<>();
        for (Task t : tasks) {
            if (t.status() == Task.Status.RUNNING || t.status() == Task.Status.QUEUED
                    || t.status() == Task.Status.WAITING) {
                live.add(t);
            }
        }
        if (live.isEmpty()) {
            sessionsList.addView(emptyRow(getString(R.string.tasks_empty)));
        } else {
            for (Task t : live) {
                sessionsList.addView(taskRow(t, () -> showTaskDetail(t)));
            }
        }

        // ---- actions ----
        sessionsList.addView(actionRow(getString(R.string.sessions_new_private), () -> {
            newTab(true);
            hideSessions();
        }));
        if (!all.isEmpty()) {
            sessionsList.addView(actionRow(getString(R.string.sessions_close_all), () -> {
                closeAllTabs();
                hideSessions();
            }));
        }
    }

    /** A tab card with a thumbnail area, title, close affordance and PRIVATE badge. */
    private View tabCard(Tab tab, boolean isActive, Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        card.setClickable(true);
        card.setFocusable(true);
        if (isActive) {
            android.graphics.drawable.GradientDrawable d = cardBackground();
            d.setStroke(dp(2), color(com.mrnobody.browser.R.color.accent));
            card.setBackground(d);
        }
        card.setOnClickListener(v -> onClick.run());

        android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
        lp.width = 0;
        lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
        lp.setMargins(0, 0, dp(6), dp(6));
        card.setLayoutParams(lp);

        // header: title + close
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), dp(9), dp(10), dp(7));
        TextView title = new TextView(this);
        title.setText(tab.label());
        title.setTextColor(color(com.mrnobody.browser.R.color.text));
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(color(com.mrnobody.browser.R.color.text_faint));
        close.setPadding(dp(4), dp(4), 0, dp(4));
        close.setOnClickListener(v -> {
            tabs.close(tabs.indexOf(tab));
            persistTabs();
            renderSessions();
        });
        head.addView(close, new LinearLayout.LayoutParams(dp(20), dp(20)));
        card.addView(head);

        // thumbnail
        FrameLayout thumb = new FrameLayout(this);
        thumb.setBackgroundColor(color(com.mrnobody.browser.R.color.surface_2));
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(84));
        thumbLp.leftMargin = dp(8);
        thumbLp.rightMargin = dp(8);
        thumbLp.bottomMargin = dp(8);
        thumb.setLayoutParams(thumbLp);
        // mock content lines
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(dp(8), dp(10), dp(8), 0);
        View l1 = new View(this);
        l1.setBackgroundColor(color(com.mrnobody.browser.R.color.border));
        lines.addView(l1, new LinearLayout.LayoutParams((int) (dp(120) * 0.7), dp(4)));
        View l2 = new View(this);
        l2.setBackgroundColor(color(com.mrnobody.browser.R.color.border));
        LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams((int) (dp(120) * 0.5), dp(4));
        l2p.topMargin = dp(8);
        lines.addView(l2, l2p);
        thumb.addView(lines);
        card.addView(thumb);

        // PRIVATE badge overlay
        if (tab.isPrivate()) {
            TextView badge = new TextView(this);
            badge.setText(getString(R.string.tab_private_badge));
            badge.setTextColor(color(com.mrnobody.browser.R.color.accent_ink));
            badge.setTextSize(8);
            badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            badge.setPadding(dp(6), dp(3), dp(6), dp(3));
            badge.setBackground(pillBackground());
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            badgeLp.topMargin = dp(6);
            badgeLp.rightMargin = dp(6);
            badge.setLayoutParams(badgeLp);
            thumb.addView(badge);
        }
        return card;
    }

    private View newTabCard(Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(cardBackground());
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> onClick.run());
        android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
        lp.width = 0;
        lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
        lp.setMargins(0, 0, dp(6), dp(6));
        card.setLayoutParams(lp);
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setColorFilter(color(com.mrnobody.browser.R.color.text_faint));
        plus.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(40)));
        return card;
    }

    // -------------------------------------------------------------- Downloads

    private void showDownloads() {
        renderDownloads();
        hideAllPanels();
        downloadsPanel.setVisibility(View.VISIBLE);
    }

    private void hideDownloads() {
        downloadsPanel.setVisibility(View.GONE);
        browserLayout.setVisibility(View.VISIBLE);
        setToolbarCollapsed(false);
    }

    /** One download entry from the system DownloadManager. */
    private static final class DownloadEntry {
        final String title;
        final long total;
        final long soFar;
        final int status;
        DownloadEntry(String t, long total, long soFar, int status) {
            this.title = t; this.total = total; this.soFar = soFar; this.status = status;
        }
    }

    private List<DownloadEntry> queryDownloads() {
        List<DownloadEntry> out = new ArrayList<>();
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return out;
            DownloadManager.Query q = new DownloadManager.Query();
            android.database.Cursor c = dm.query(q);
            if (c == null) return out;
            int iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
            int iTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            int iSoFar = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
            int iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
            while (c.moveToNext()) {
                out.add(new DownloadEntry(
                        c.getString(iTitle),
                        c.getLong(iTotal),
                        c.getLong(iSoFar),
                        c.getInt(iStatus)));
            }
            c.close();
        } catch (Exception e) {
            ErrorLog.record("download query failed: " + e.getMessage());
        }
        return out;
    }

    private void renderDownloads() {
        downloadsList.removeAllViews();
        List<DownloadEntry> entries = queryDownloads();

        // storage summary
        downloadsList.addView(sectionLabel(getString(R.string.dl_storage_section)));
        long totalBytes = 0;
        int done = 0;
        for (DownloadEntry e : entries) {
            totalBytes += Math.max(e.total, e.soFar);
            if (e.status == DownloadManager.STATUS_SUCCESSFUL) done++;
        }
        LinearLayout summary = card();
        summary.addView(metricRow(getString(R.string.dl_files_downloaded), String.valueOf(done), false));
        summary.addView(metricRow(getString(R.string.dl_storage_used), humanBytes(totalBytes), false));
        downloadsList.addView(summary);

        // recent list
        downloadsList.addView(sectionLabel(getString(R.string.dl_recent)));
        if (entries.isEmpty()) {
            downloadsList.addView(emptyRow(getString(R.string.dl_empty)));
        } else {
            LinearLayout list = card();
            for (DownloadEntry e : entries) {
                list.addView(downloadRow(e));
            }
            downloadsList.addView(list);
        }
    }

    private View downloadRow(DownloadEntry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_download);
        icon.setColorFilter(color(com.mrnobody.browser.R.color.text_dim));
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(e.title != null && !e.title.isEmpty() ? e.title : "download");
        name.setTextColor(color(com.mrnobody.browser.R.color.text));
        name.setTextSize(12.5f);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        TextView sub = new TextView(this);
        sub.setText(humanBytes(Math.max(e.total, e.soFar)));
        sub.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        sub.setTextSize(10);
        sub.setTypeface(Typeface.MONOSPACE);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // status icon (check / refresh / download-in-progress)
        ImageView state = new ImageView(this);
        int res;
        switch (e.status) {
            case DownloadManager.STATUS_SUCCESSFUL: res = R.drawable.ic_check; break;
            case DownloadManager.STATUS_FAILED:     res = R.drawable.ic_refresh; break;
            default:                               res = R.drawable.ic_download; break;
        }
        state.setImageResource(res);
        state.setColorFilter(color(com.mrnobody.browser.R.color.text_dim));
        state.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.addView(state, new LinearLayout.LayoutParams(dp(26), dp(26)));
        return row;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // -------------------------------------------------------------- Clear Data

    private void showClearData() {
        renderClearData();
        hideAllPanels();
        clearPanel.setVisibility(View.VISIBLE);
    }

    private void hideClearData() {
        clearPanel.setVisibility(View.GONE);
        browserLayout.setVisibility(View.VISIBLE);
        setToolbarCollapsed(false);
    }

    private void renderClearData() {
        clearList.removeAllViews();
        String[] labels = {
                getString(R.string.clear_history),
                getString(R.string.clear_cookies),
                getString(R.string.clear_cache),
                getString(R.string.clear_site_data),
                "Task state",
                "Download workspace"
        };
        clearList.addView(sectionLabel(getString(R.string.clear_title)));
        LinearLayout card = card();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            card.addView(checkRow(labels[i], clearChecks[i], v -> {
                clearChecks[idx] = !clearChecks[idx];
                ((ImageView) v).setColorFilter(color(clearChecks[idx]
                        ? com.mrnobody.browser.R.color.accent
                        : com.mrnobody.browser.R.color.text_faint));
                ImageView iv = (ImageView) v;
                iv.setImageResource(clearChecks[idx] ? R.drawable.ic_check : R.drawable.ic_blank);
            }));
        }
        clearList.addView(card);

        // actions
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(16), dp(18), dp(16), dp(8));
        actions.addView(clearButton(getString(R.string.clear_cancel), false, v -> hideClearData()),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        actions.addView(clearButton(getString(R.string.clear_action), true, v -> performClearData()),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        clearList.addView(actions);
    }

    /** A rounded button (ghost or solid) for the clear-data actions. */
    private TextView clearButton(String label, boolean solid, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        if (solid) {
            b.setTextColor(color(com.mrnobody.browser.R.color.accent_ink));
            b.setBackground(pillBackground(color(com.mrnobody.browser.R.color.accent)));
        } else {
            b.setTextColor(color(com.mrnobody.browser.R.color.text_dim));
            b.setBackground(pillBackground(color(com.mrnobody.browser.R.color.surface)));
        }
        b.setOnClickListener(onClick);
        return b;
    }

    /** A checkbox row whose trailing icon toggles on tap. */
    private View checkRow(String label, boolean checked, View.OnClickListener onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color(com.mrnobody.browser.R.color.text));
        t.setTextSize(13);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageView box = new ImageView(this);
        box.setImageResource(checked ? R.drawable.ic_check : R.drawable.ic_blank);
        box.setColorFilter(color(checked ? com.mrnobody.browser.R.color.accent
                : com.mrnobody.browser.R.color.text_faint));
        box.setPadding(dp(5), dp(5), dp(5), dp(5));
        box.setOnClickListener(onToggle);
        row.addView(box, new LinearLayout.LayoutParams(dp(28), dp(28)));
        return row;
    }

    private void performClearData() {
        if (clearChecks[0]) MrNobodyApp.history().clear();
        if (clearChecks[1]) CookieManager.getInstance().removeAllCookies(null);
        if (clearChecks[2]) clearCache();
        if (clearChecks[3]) WebStorage.getInstance().deleteAllData();
        if (clearChecks[4]) MrNobodyApp.tasks().clear();
        Toast.makeText(this, getString(R.string.clear_action), Toast.LENGTH_SHORT).show();
        hideClearData();
    }

    private void clearCache() {
        try {
            WebView wv = new WebView(this);
            wv.clearCache(true);
            wv.destroy();
        } catch (Exception ignored) { }
    }

    // ----------------------------------------------------------------- Tasks

    private void showTasks() {
        renderTasks();
        hideAllPanels();
        tasksPanel.setVisibility(View.VISIBLE);
    }

    private void hideTasks() {
        tasksPanel.setVisibility(View.GONE);
        browserLayout.setVisibility(View.VISIBLE);
        setToolbarCollapsed(false);
    }

    private void renderTasks() {
        tasksList.removeAllViews();
        List<Task> tasks = MrNobodyApp.tasks().recent(100);
        if (tasks.isEmpty()) {
            tasksList.addView(emptyRow(getString(R.string.tasks_empty)));
            return;
        }
        for (Task t : tasks) {
            tasksList.addView(taskRow(t, () -> showTaskDetail(t)));
        }
    }

    private View taskRow(Task task, Runnable onClick) {
        LinearLayout row = baseRow();
        row.setOnClickListener(v -> onClick.run());

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(task.instruction());
        name.setTextColor(color(com.mrnobody.browser.R.color.text));
        name.setTextSize(14);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(name);

        TextView meta = new TextView(this);
        String worker = MrNobodyApp.dispatcher().isLocal(task)
                ? getString(R.string.task_worker_local) : getString(R.string.task_worker_remote);
        meta.setText(worker + (task.currentStep() != null && !task.currentStep().isEmpty()
                ? " · " + task.currentStep() : ""));
        meta.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        meta.setTextSize(10);
        meta.setTypeface(Typeface.MONOSPACE);
        textCol.addView(meta);
        row.addView(textCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = new TextView(this);
        chip.setText(task.status().name());
        chip.setTextSize(9);
        chip.setTypeface(Typeface.MONOSPACE);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        chip.setTextColor(statusColor(task.status()));
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(dp(10));
        d.setColor(android.graphics.Color.TRANSPARENT);
        d.setStroke(dp(1), statusColor(task.status()));
        chip.setBackground(d);
        row.addView(chip);
        return row;
    }

    private void showTaskDetail(Task task) {
        renderTaskDetail(task);
        hideAllPanels();
        detailPanel.setVisibility(View.VISIBLE);
    }

    private void hideTaskDetail() {
        detailPanel.setVisibility(View.GONE);
        browserLayout.setVisibility(View.VISIBLE);
        setToolbarCollapsed(false);
    }

    /** Render the Task detail screen: status, progress, result/error, actions. */
    private void renderTaskDetail(Task task) {
        detailList.removeAllViews();

        // instruction as a centered title
        TextView title = new TextView(this);
        title.setText(task.instruction());
        title.setTextColor(color(com.mrnobody.browser.R.color.text));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(20), dp(14), dp(20), dp(10));
        detailList.addView(title);

        // status + worker card
        detailList.addView(sectionLabel("Status"));
        LinearLayout statusCard = card();
        statusCard.addView(metricRow("State", task.status().name(), false));
        if (task.currentStep() != null && !task.currentStep().isEmpty()) {
            statusCard.addView(metricRow("Step", task.currentStep(), true));
        }
        statusCard.addView(metricRow("Worker",
                MrNobodyApp.dispatcher().isLocal(task)
                        ? getString(R.string.task_worker_local)
                        : getString(R.string.task_worker_remote), true));
        detailList.addView(statusCard);

        // progress card
        detailList.addView(sectionLabel(getString(R.string.td_progress)));
        LinearLayout progCard = card();
        int pct = task.status() == Task.Status.COMPLETED ? 100 : task.progress();
        LinearLayout progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);
        progRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(color(com.mrnobody.browser.R.color.surface_2));
        LinearLayout fill = new LinearLayout(this);
        fill.setBackgroundColor(color(com.mrnobody.browser.R.color.accent));
        LinearLayout.LayoutParams fillLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(0, Math.min(100, pct)) / 100f);
        fill.setLayoutParams(fillLp);
        bar.addView(fill);
        progRow.addView(bar, new LinearLayout.LayoutParams(0, dp(4), 1f));
        TextView pctTv = new TextView(this);
        pctTv.setText(pct + "%");
        pctTv.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        pctTv.setTextSize(11);
        pctTv.setTypeface(Typeface.MONOSPACE);
        pctTv.setPadding(dp(10), 0, 0, 0);
        progRow.addView(pctTv);
        progCard.addView(progRow);
        detailList.addView(progCard);

        // result / error card
        String resultText = task.status() == Task.Status.COMPLETED
                ? (task.result() != null ? task.result() : "")
                : (task.error() != null ? task.error() : "");
        if (!resultText.isEmpty()) {
            detailList.addView(sectionLabel(task.status() == Task.Status.COMPLETED ? "Result" : "Error"));
            LinearLayout resultCard = card();
            TextView r = new TextView(this);
            r.setText(truncate(resultText, 800));
            r.setTextColor(color(com.mrnobody.browser.R.color.text_dim));
            r.setTextSize(12);
            r.setPadding(dp(16), dp(14), dp(16), dp(14));
            resultCard.addView(r);
            detailList.addView(resultCard);
        }

        // actions
        detailList.addView(actionRow(getString(R.string.copy), () -> copyToClipboard(resultText)));
        if (task.status() == Task.Status.FAILED) {
            detailList.addView(actionRow(getString(R.string.tasks_run_again),
                    () -> { hideTaskDetail(); runTask(task.instruction()); }));
        }
    }

    /** Copy text to the system clipboard and confirm. */
    private void copyToClipboard(String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Mr Nobody", text));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private int statusColor(Task.Status status) {
        switch (status) {
            case COMPLETED: return color(com.mrnobody.browser.R.color.blocked);
            case FAILED:
            case CANCELLED: return color(com.mrnobody.browser.R.color.deny);
            case WAITING:   return color(com.mrnobody.browser.R.color.accent);
            default:        return color(com.mrnobody.browser.R.color.accent); // RUNNING/QUEUED
        }
    }

    // ------------------------------------------------------------- UI helpers

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundColor(color(com.mrnobody.browser.R.color.surface));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    /** A key/value metric row (key left, mono value right) — Privacy / Storage. */
    private View metricRow(String key, String value, boolean dimValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setBackgroundColor(color(com.mrnobody.browser.R.color.surface));

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextColor(color(com.mrnobody.browser.R.color.text_dim));
        k.setTextSize(13);
        row.addView(k, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(dimValue ? color(com.mrnobody.browser.R.color.text_faint)
                : color(com.mrnobody.browser.R.color.text));
        v.setTextSize(dimValue ? 11 : 15);
        v.setTypeface(Typeface.MONOSPACE, dimValue ? Typeface.NORMAL : Typeface.BOLD);
        row.addView(v);
        return row;
    }

    /** A wrapped card with rounded monochrome background. */
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase());
        t.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        t.setTextSize(10);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.1f);
        t.setPadding(dp(16), dp(16), dp(16), dp(4));
        return t;
    }

    private View actionRow(String label, Runnable onClick) {
        LinearLayout row = baseRow();
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color(com.mrnobody.browser.R.color.accent));
        t.setTextSize(14);
        row.addView(t);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private TextView emptyRow(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        t.setTextSize(13);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        return t;
    }

    private void hideAllPanels() {
        privacyPanel.setVisibility(View.GONE);
        firstLaunch.setVisibility(View.GONE);
        sessionsPanel.setVisibility(View.GONE);
        tasksPanel.setVisibility(View.GONE);
        downloadsPanel.setVisibility(View.GONE);
        clearPanel.setVisibility(View.GONE);
        detailPanel.setVisibility(View.GONE);
        if (homePanel != null) homePanel.setVisibility(View.GONE);
        browserLayout.setVisibility(View.VISIBLE);
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
                persistTabs();
            }
            updateAddressBar();
        }
    };

    private final WebChromeClient webChromeClient = new WebChromeClient() {
        @Override
        public void onReceivedTitle(WebView view, String title) {
            Tab t = tabs.getActive();
            if (t != null) {
                t.setTitle(title);
                persistTabs();
            }
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
        persistTabs();
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
        if (sessionsPanel != null && sessionsPanel.getVisibility() == View.VISIBLE) {
            hideSessions();
        } else if (tasksPanel != null && tasksPanel.getVisibility() == View.VISIBLE) {
            hideTasks();
        } else if (privacyPanel != null && privacyPanel.getVisibility() == View.VISIBLE) {
            hidePrivacyPanel();
        } else if (downloadsPanel != null && downloadsPanel.getVisibility() == View.VISIBLE) {
            hideDownloads();
        } else if (clearPanel != null && clearPanel.getVisibility() == View.VISIBLE) {
            hideClearData();
        } else if (detailPanel != null && detailPanel.getVisibility() == View.VISIBLE) {
            hideTaskDetail();
        } else if (homePanel != null && homePanel.getVisibility() == View.VISIBLE) {
            // Back from Agent Home → return to the active tab, or exit.
            if (tabs.getActive() != null) hideHome();
            else super.onBackPressed();
        } else if (firstLaunch.getVisibility() == View.VISIBLE) {
            super.onBackPressed();
        } else if (t != null && t.canGoBack()) {
            t.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
