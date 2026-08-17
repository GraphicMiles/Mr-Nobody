package com.mrnobody.browser;

import android.graphics.Typeface;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.mrnobody.browser.core.BookmarksStore;
import com.mrnobody.browser.core.PerSiteSettings;
import com.mrnobody.browser.core.PermissionStore;
import com.mrnobody.browser.core.PrivacyProfile;
import com.mrnobody.browser.core.Settings;

import java.util.List;
import java.util.Map;

/**
 * Settings screen. Every default favors privacy (history OFF, suggestions OFF).
 * Built programmatically to keep the dependency footprint tiny.
 */
public class SettingsActivity extends AppCompatActivity {

    private Settings settings;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = MrNobodyApp.settings();

        ScrollView scroll = new ScrollView(this);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container);
        setContentView(scroll);

        int text = color(com.mrnobody.browser.R.color.text);
        int dim = color(com.mrnobody.browser.R.color.text_dim);
        int faint = color(com.mrnobody.browser.R.color.text_faint);

        header("Settings");

        toggleRow(getString(R.string.settings_history), settings.isHistoryEnabled(), checked -> {
            settings.setHistoryEnabled(checked);
            MrNobodyApp.history().setEnabled(checked);
        });
        toggleRow(getString(R.string.settings_js), settings.isJsEnabled(), checked ->
                settings.setJsEnabled(checked));
        toggleRow(getString(R.string.settings_suggest), settings.areSuggestionsEnabled(), checked ->
                settings.setSuggestionsEnabled(checked));

        section(getString(R.string.settings_privacy_section));

        // V2: privacy profile presets
        navRow(getString(R.string.profile_title), () -> showProfileDialog());
        toggleRow(getString(R.string.settings_param_stripping),
                settings.isParamStrippingEnabled(),
                settings::setParamStrippingEnabled);
        toggleRow(getString(R.string.settings_fingerprint),
                settings.isFingerprintProtection(),
                settings::setFingerprintProtection);

        section(getString(R.string.settings_agent_section));

        valueRow(getString(R.string.settings_ai_provider),
                MrNobodyApp.providerDisplayName(MrNobodyApp.activeAiProviderId()),
                this::showAiProviderDialog);

        section("Data & controls");

        navRow(getString(R.string.settings_search_engine), () -> showSearchEngineDialog());
        navRow(getString(R.string.settings_bookmarks), this::showBookmarksDialog);
        navRow(getString(R.string.settings_privacy_report), this::showReportDialog);
        navRow(getString(R.string.settings_permission_dashboard), this::showPermissionDashboard);
        navRow(getString(R.string.settings_site_overrides), this::showSiteOverrides);
        navRow(getString(R.string.settings_storage_dashboard), this::showStorageDashboard);
        navRow(getString(R.string.settings_clear_data), () -> showClearDataDialog());
        navRow(getString(R.string.settings_downloads), () ->
                startActivity(new android.content.Intent(
                        android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)));
        navRow(getString(R.string.settings_about), () -> showAbout());

        section("Theme");
        navRow("Appearance", () -> showThemeDialog());

        // Footer
        TextView footer = new TextView(this);
        footer.setText("local filtering only · no account · no browsing data ever leaves the device");
        footer.setTextColor(faint);
        footer.setTextSize(11);
        footer.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.setMargins(dp(24), dp(24), dp(24), dp(32));
        footer.setLayoutParams(fp);
        container.addView(footer);
    }

    // ------------------------------------------------------------- row builders

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void header(String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(color(com.mrnobody.browser.R.color.text_dim));
        back.setTextSize(22);
        back.setPadding(dp(8), 0, dp(16), 0);
        back.setOnClickListener(v -> finish());
        row.addView(back);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(color(com.mrnobody.browser.R.color.text));
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t);
        container.addView(row);
    }

    private void section(String label) {
        TextView t = new TextView(this);
        t.setText(label.toUpperCase());
        t.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        t.setTextSize(10);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(16), dp(18), dp(16), dp(2));
        t.setLayoutParams(p);
        container.addView(t);
    }

    private void toggleRow(String label, boolean initial, OnBoolChanged listener) {
        LinearLayout row = baseRow();
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color(com.mrnobody.browser.R.color.text));
        t.setTextSize(14);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch s = new Switch(this);
        s.setChecked(initial);
        s.setOnCheckedChangeListener((button, checked) -> listener.onChanged(checked));
        row.addView(s);
        container.addView(row);
    }

    private interface OnBoolChanged {
        void onChanged(boolean value);
    }

    private void navRow(String label, Runnable onClick) {
        LinearLayout row = baseRow();
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color(com.mrnobody.browser.R.color.text));
        t.setTextSize(14);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        chev.setTextSize(18);
        row.addView(chev);
        row.setOnClickListener(v -> onClick.run());
        container.addView(row);
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundColor(color(com.mrnobody.browser.R.color.surface));
        return row;
    }

    /** A nav-style row that also shows a current value between the label and chevron. */
    private void valueRow(String label, String value, Runnable onClick) {
        LinearLayout row = baseRow();
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color(com.mrnobody.browser.R.color.text));
        t.setTextSize(14);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color(com.mrnobody.browser.R.color.accent));
        v.setTextSize(12);
        v.setTypeface(Typeface.MONOSPACE);
        v.setPadding(dp(8), 0, dp(4), 0);
        row.addView(v);

        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        chev.setTextSize(18);
        row.addView(chev);
        row.setOnClickListener(vi -> onClick.run());
        container.addView(row);
    }

    // ------------------------------------------------------------- dialogs

    private void showSearchEngineDialog() {
        String[] engines = {"DuckDuckGo (default)", "Startpage", "Bing"};
        String[] urls = {Settings.SEARCH_DDG, Settings.SEARCH_STARTPAGE, Settings.SEARCH_BING};
        int current = indexOf(urls, settings.getSearchEngine());
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_search_engine))
                .setSingleChoiceItems(engines, current, (d, which) -> {
                    settings.setSearchEngine(urls[which]);
                    d.dismiss();
                })
                .show();
    }

    private void showAiProviderDialog() {
        String[] ids = {"local", "gemini", "groq", "openai-compatible"};
        String[] labels = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            labels[i] = MrNobodyApp.providerDisplayName(ids[i]);
        }
        String current = MrNobodyApp.activeAiProviderId();
        int checked = 0;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(current)) checked = i;

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_ai_provider))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    String id = ids[which];
                    if ("local".equals(id)) {
                        MrNobodyApp.setActiveAiProviderId("local");
                    } else {
                        promptForKey(id);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Full provider config: API key, base URL and model (pre-filled with free-tier defaults). */
    private void promptForKey(String providerId) {
        android.widget.EditText keyInput = new android.widget.EditText(this);
        keyInput.setHint(getString(R.string.ai_key_hint));
        keyInput.setSingleLine(true);
        keyInput.setText(settings.apiKey(providerId));

        android.widget.EditText baseInput = new android.widget.EditText(this);
        baseInput.setHint("Base URL");
        baseInput.setSingleLine(true);
        baseInput.setText(settings.apiBase(providerId));

        android.widget.EditText modelInput = new android.widget.EditText(this);
        modelInput.setHint("Model");
        modelInput.setSingleLine(true);
        modelInput.setText(settings.apiModel(providerId));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(8), dp(8), 0);

        TextView kLbl = new TextView(this);
        kLbl.setText("API key");
        kLbl.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        kLbl.setTextSize(11);
        form.addView(kLbl);
        form.addView(keyInput);

        TextView bLbl = new TextView(this);
        bLbl.setText("Base URL");
        bLbl.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        bLbl.setTextSize(11);
        bLbl.setPadding(0, dp(8), 0, 0);
        form.addView(bLbl);
        form.addView(baseInput);

        TextView mLbl = new TextView(this);
        mLbl.setText("Model");
        mLbl.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        mLbl.setTextSize(11);
        mLbl.setPadding(0, dp(8), 0, 0);
        form.addView(mLbl);
        form.addView(modelInput);

        new AlertDialog.Builder(this)
                .setTitle(MrNobodyApp.providerDisplayName(providerId))
                .setMessage(getString(R.string.ai_disclosure))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    settings.setApiKey(providerId, keyInput.getText().toString().trim());
                    settings.setApiBase(providerId, baseInput.getText().toString().trim());
                    settings.setApiModel(providerId, modelInput.getText().toString().trim());
                    MrNobodyApp.setActiveAiProviderId(providerId);
                    android.widget.Toast.makeText(this, R.string.ai_key_set,
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showProfileDialog() {
        String[] labels = {PrivacyProfile.BALANCED.label(),
                PrivacyProfile.STRICT.label(), PrivacyProfile.MAXIMUM.label()};
        int current = settings.getProfile().ordinal();
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.profile_title))
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    PrivacyProfile profile = PrivacyProfile.values()[which];
                    settings.setProfile(profile);
                    profile.apply(settings);
                    MrNobodyApp.history().setEnabled(settings.isHistoryEnabled());
                    MrNobodyApp.filters().setEnabled(settings.isBlockingEnabled());
                    d.dismiss();
                })
                .show();
    }

    private void showBookmarksDialog() {
        List<BookmarksStore.Bookmark> list = MrNobodyApp.bookmarks().all();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.bookmarks_title)
                    .setMessage(R.string.bookmarks_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            labels[i] = list.get(i).title + "\n" + list.get(i).url;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.bookmarks_title)
                .setItems(labels, (d, which) ->
                        new AlertDialog.Builder(this)
                                .setTitle(list.get(which).title)
                                .setItems(new String[]{"Open", "Delete"}, (d2, w2) -> {
                                    if (w2 == 0) {
                                        finish();
                                    } else {
                                        MrNobodyApp.bookmarks().remove(list.get(which).id);
                                    }
                                })
                                .show())
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

    private void showPermissionDashboard() {
        Map<String, List<String>> grants = MrNobodyApp.permissions().allGrants();
        if (grants.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.perm_dash_title)
                    .setMessage(R.string.perm_dash_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : grants.entrySet()) {
            sb.append(e.getKey()).append(": ")
              .append(joinPerms(e.getValue())).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.perm_dash_title)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showSiteOverrides() {
        Map<String, Map<String, String>> all = MrNobodyApp.perSite().allOverrides();
        if (all.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_site_overrides)
                    .setMessage("No per-site overrides yet.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        List<String> hosts = new java.util.ArrayList<>(all.keySet());
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_site_overrides)
                .setItems(hosts.toArray(new String[0]), (d, which) -> {
                    String host = hosts.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle(host)
                            .setItems(new String[]{getString(R.string.site_reset)}, (d2, w2) -> {
                                MrNobodyApp.perSite().clear(host);
                            })
                            .show();
                })
                .show();
    }

    private void showStorageDashboard() {
        long cache = dirSize(getCacheDir());
        String msg = getString(R.string.storage_cache) + ": " + humanBytes(cache);
        new AlertDialog.Builder(this)
                .setTitle(R.string.storage_title)
                .setMessage(msg)
                .setNeutralButton(android.R.string.ok, null)
                .setPositiveButton(R.string.storage_clear_cache, (d, w) -> clearCache())
                .show();
    }

    /** Recursively sum the bytes under a directory (best-effort). */
    private static long dirSize(java.io.File dir) {
        long total = 0;
        if (dir == null || !dir.exists()) return total;
        java.io.File[] files = dir.listFiles();
        if (files == null) return total;
        for (java.io.File f : files) {
            if (f.isDirectory()) total += dirSize(f);
            else total += f.length();
        }
        return total;
    }

    private static String joinPerms(List<String> perms) {
        StringBuilder sb = new StringBuilder();
        for (String p : perms) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void showThemeDialog() {
        String[] themes = {"System default", "Dark", "Light"};
        String[] values = {Settings.THEME_SYSTEM, Settings.THEME_DARK, Settings.THEME_LIGHT};
        int current = indexOf(values, settings.getTheme());
        new AlertDialog.Builder(this)
                .setTitle("Appearance")
                .setSingleChoiceItems(themes, current, (d, which) -> {
                    settings.setTheme(values[which]);
                    d.dismiss();
                    // Re-apply immediately.
                    int mode;
                    switch (values[which]) {
                        case Settings.THEME_DARK:  mode = AppCompatDelegate.MODE_NIGHT_YES; break;
                        case Settings.THEME_LIGHT: mode = AppCompatDelegate.MODE_NIGHT_NO; break;
                        default:                    mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                    }
                    AppCompatDelegate.setDefaultNightMode(mode);
                })
                .show();
    }

    private void showClearDataDialog() {
        String[] items = {getString(R.string.clear_history), getString(R.string.clear_cookies),
                getString(R.string.clear_cache), getString(R.string.clear_site_data)};
        boolean[] checked = {true, true, true, false};

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.clear_title))
                .setMultiChoiceItems(items, checked, null)
                .setNegativeButton(R.string.clear_cancel, null)
                .setPositiveButton(R.string.clear_action, (d, which) -> {
                    boolean[] c = checked;
                    if (c[0]) MrNobodyApp.history().clear();
                    if (c[1]) CookieManager.getInstance().removeAllCookies(null);
                    if (c[2]) clearCache();
                    if (c[3]) WebStorage.getInstance().deleteAllData();
                })
                .show();
    }

    private void clearCache() {
        // clearCache(true) is a global (per-process) operation but must be called
        // on an instance. A throwaway WebView on the main thread is the accepted way.
        try {
            android.webkit.WebView wv = new android.webkit.WebView(this);
            wv.clearCache(true);
            wv.destroy();
        } catch (Exception ignored) {
            // Cache clearing is best-effort; never crash the settings screen.
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(getString(R.string.tagline) + "\n\n" +
                        "A tiny native privacy browser.\nNo ads, no trackers, no history by default.\n\n" +
                        "Filter list version: " + MrNobodyApp.filters().getFilterVersion() + "\n" +
                        "Profile: " + settings.getProfile().label() + "\n\n" +
                        "Privacy is not anonymity.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(value)) return i;
        return 0;
    }
}
