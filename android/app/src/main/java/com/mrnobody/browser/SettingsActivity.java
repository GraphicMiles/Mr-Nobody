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

import com.mrnobody.browser.core.Settings;

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

        navRow(getString(R.string.settings_search_engine), () -> showSearchEngineDialog());
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
                        "Privacy is not anonymity.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(value)) return i;
        return 0;
    }
}
