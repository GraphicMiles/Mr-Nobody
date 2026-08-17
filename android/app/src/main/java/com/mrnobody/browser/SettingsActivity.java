package com.mrnobody.browser;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.mrnobody.browser.core.BookmarksStore;
import com.mrnobody.browser.core.PerSiteSettings;
import com.mrnobody.browser.core.PermissionStore;
import com.mrnobody.browser.core.PrivacyProfile;
import com.mrnobody.browser.core.Settings;

import java.util.List;
import java.util.Map;

/**
 * Settings screen. Every default favors privacy (history OFF, suggestions OFF).
 * Monochrome (black & white), edge-to-edge, anchored popup menus for choices.
 */
public class SettingsActivity extends AppCompatActivity {

    private Settings settings;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = MrNobodyApp.settings();

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        ScrollView scroll = new ScrollView(this);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container);
        setContentView(scroll);

        // status-bar inset for the header
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            container.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        header("Settings");

        section("Browsing");
        toggleRow(getString(R.string.settings_history), settings.isHistoryEnabled(), checked -> {
            settings.setHistoryEnabled(checked);
            MrNobodyApp.history().setEnabled(checked);
        });
        toggleRow(getString(R.string.settings_js), settings.isJsEnabled(),
                settings::setJsEnabled);
        toggleRow(getString(R.string.settings_suggest), settings.areSuggestionsEnabled(),
                settings::setSuggestionsEnabled);

        section(getString(R.string.settings_privacy_section));
        valueRow(getString(R.string.profile_title), settings.getProfile().label(),
                anchor -> showProfileMenu(anchor));
        toggleRow(getString(R.string.settings_param_stripping),
                settings.isParamStrippingEnabled(), settings::setParamStrippingEnabled);
        toggleRow(getString(R.string.settings_fingerprint),
                settings.isFingerprintProtection(), settings::setFingerprintProtection);

        section(getString(R.string.settings_agent_section));
        valueRow(getString(R.string.settings_ai_provider),
                MrNobodyApp.providerDisplayName(MrNobodyApp.activeAiProviderId()),
                this::showAiProviderMenu);
        valueRow("Terminal", settings.isFingerprintProtection() ? "on" : "off",
                this::showTerminalMenu);

        section("Data & controls");
        valueRow(getString(R.string.settings_search_engine), engineName(),
                this::showSearchEngineMenu);
        navRow(getString(R.string.settings_bookmarks), this::showBookmarksDialog);
        navRow(getString(R.string.settings_privacy_report), this::showReportDialog);
        navRow(getString(R.string.settings_permission_dashboard), this::showPermissionDashboard);
        navRow(getString(R.string.settings_site_overrides), this::showSiteOverrides);
        navRow(getString(R.string.settings_storage_dashboard), this::showStorageDashboard);
        // Clear data → native screen (deep link into MainActivity)
        navRow(getString(R.string.settings_clear_data), () -> deepLink("mrnobody://clear"));
        // Downloads → native screen (deep link into MainActivity)
        navRow(getString(R.string.settings_downloads), () -> deepLink("mrnobody://downloads"));
        navRow(getString(R.string.settings_about), this::showAbout);

        section("Theme");
        valueRow("Appearance", themeName(), this::showThemeMenu);

        TextView footer = new TextView(this);
        footer.setText("local filtering only · no account · no browsing data ever leaves the device");
        footer.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
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
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(color(com.mrnobody.browser.R.color.text_dim));
        back.setPadding(dp(12), dp(8), dp(14), dp(8));
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

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
        p.setMargins(dp(16), dp(18), dp(16), dp(4));
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

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(color(com.mrnobody.browser.R.color.text_faint));
        chev.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.addView(chev, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setOnClickListener(v -> onClick.run());
        container.addView(row);
    }

    private void valueRow(String label, String value, View.OnClickListener onClick) {
        LinearLayout row = baseRow();
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color(com.mrnobody.browser.R.color.text));
        t.setTextSize(14);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        v.setTextSize(12);
        v.setTypeface(Typeface.MONOSPACE);
        v.setPadding(dp(8), 0, dp(4), 0);
        row.addView(v);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(color(com.mrnobody.browser.R.color.text_faint));
        chev.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.addView(chev, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setOnClickListener(onClick);
        container.addView(row);
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundColor(color(com.mrnobody.browser.R.color.surface));
        return row;
    }

    // ------------------------------------------------- anchored popup menus

    /** A compact, anchored choice menu (PopupWindow) aligned to the tapped row. */
    private void showChoiceMenu(View anchor, String[] labels, int selected, ChoiceListener listener) {
        final PopupWindow[] holder = new PopupWindow[1];
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(color(com.mrnobody.browser.R.color.surface));
        content.setElevation(dp(8));
        content.setPadding(dp(6), dp(6), dp(6), dp(6));
        content.setMinimumWidth(dp(220));

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextColor(color(idx == selected ? com.mrnobody.browser.R.color.accent
                    : com.mrnobody.browser.R.color.text));
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT, idx == selected ? Typeface.BOLD : Typeface.NORMAL);
            t.setPadding(dp(14), dp(13), dp(14), dp(13));
            t.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                listener.onChoose(idx);
            });
            content.addView(t);
        }

        PopupWindow popup = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        holder[0] = popup;
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    private interface ChoiceListener {
        void onChoose(int index);
    }

    private void showProfileMenu(View anchor) {
        String[] labels = {PrivacyProfile.BALANCED.label(),
                PrivacyProfile.STRICT.label(), PrivacyProfile.MAXIMUM.label()};
        showChoiceMenu(anchor, labels, settings.getProfile().ordinal(), idx -> {
            PrivacyProfile profile = PrivacyProfile.values()[idx];
            settings.setProfile(profile);
            profile.apply(settings);
            MrNobodyApp.history().setEnabled(settings.isHistoryEnabled());
            MrNobodyApp.filters().setEnabled(settings.isBlockingEnabled());
            rebuild();
        });
    }

    private void showAiProviderMenu(View anchor) {
        String[] ids = {"local", "gemini", "groq", "openai-compatible"};
        String[] labels = new String[ids.length];
        for (int i = 0; i < ids.length; i++) labels[i] = MrNobodyApp.providerDisplayName(ids[i]);
        String current = MrNobodyApp.activeAiProviderId();
        int checked = 0;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(current)) checked = i;
        showChoiceMenu(anchor, labels, checked, idx -> {
            String id = ids[idx];
            if ("local".equals(id)) {
                MrNobodyApp.setActiveAiProviderId("local");
                rebuild();
            } else {
                promptForKey(id);
            }
        });
    }

    private void showTerminalMenu(View anchor) {
        showChoiceMenu(anchor, new String[]{"Off", "On (sandboxed)"}, 0, idx -> {
            android.widget.Toast.makeText(this, idx == 0 ? "Terminal disabled" : "Terminal enabled",
                    android.widget.Toast.LENGTH_SHORT).show();
            rebuild();
        });
    }

    private void showSearchEngineMenu(View anchor) {
        String[] labels = {"DuckDuckGo (default)", "Startpage", "Bing"};
        String[] urls = {Settings.SEARCH_DDG, Settings.SEARCH_STARTPAGE, Settings.SEARCH_BING};
        showChoiceMenu(anchor, labels, indexOf(urls, settings.getSearchEngine()), idx -> {
            settings.setSearchEngine(urls[idx]);
            rebuild();
        });
    }

    private void showThemeMenu(View anchor) {
        String[] labels = {"System default", "Dark", "Light"};
        String[] values = {Settings.THEME_SYSTEM, Settings.THEME_DARK, Settings.THEME_LIGHT};
        showChoiceMenu(anchor, labels, indexOf(values, settings.getTheme()), idx -> {
            settings.setTheme(values[idx]);
            int mode;
            switch (values[idx]) {
                case Settings.THEME_DARK:  mode = AppCompatDelegate.MODE_NIGHT_YES; break;
                case Settings.THEME_LIGHT: mode = AppCompatDelegate.MODE_NIGHT_NO; break;
                default:                   mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            AppCompatDelegate.setDefaultNightMode(mode);
            rebuild();
        });
    }

    private String engineName() {
        String e = settings.getSearchEngine();
        if (e.equals(Settings.SEARCH_DDG)) return "DuckDuckGo";
        if (e.equals(Settings.SEARCH_STARTPAGE)) return "Startpage";
        return "Bing";
    }

    private String themeName() {
        String t = settings.getTheme();
        if (t.equals(Settings.THEME_DARK)) return "Dark";
        if (t.equals(Settings.THEME_LIGHT)) return "Light";
        return "System";
    }

    /** Rebuild the screen so value rows reflect the new selection. */
    private void rebuild() {
        recreate();
    }

    /** Launch MainActivity with a deep link (native screens like Downloads/Clear data). */
    private void deepLink(String uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.setPackage(getPackageName());
        startActivity(i);
    }

    // ------------------------------------------------------------- info dialogs

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
        form.addView(label("API key"));
        form.addView(keyInput);
        form.addView(label("Base URL"));
        form.addView(baseInput);
        form.addView(label("Model"));
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
                    rebuild();
                })
                .show();
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color(com.mrnobody.browser.R.color.text_faint));
        t.setTextSize(11);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private void showBookmarksDialog() {
        List<BookmarksStore.Bookmark> list = MrNobodyApp.bookmarks().all();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(R.string.bookmarks_title)
                    .setMessage(R.string.bookmarks_empty).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) labels[i] = list.get(i).title + "\n" + list.get(i).url;
        new AlertDialog.Builder(this).setTitle(R.string.bookmarks_title)
                .setItems(labels, (d, which) -> {
                    final BookmarksStore.Bookmark b = list.get(which);
                    new AlertDialog.Builder(this).setTitle(b.title)
                            .setItems(new String[]{"Open", "Delete"}, (d2, w2) -> {
                                if (w2 == 0) finish();
                                else { MrNobodyApp.bookmarks().remove(b.id); }
                            }).show();
                }).show();
    }

    private void showReportDialog() {
        long ads = MrNobodyApp.report().adsBlocked();
        long trackers = MrNobodyApp.report().trackersBlocked();
        long pages = MrNobodyApp.report().pagesLoaded();
        String msg = getString(R.string.report_ads) + ": " + ads + "\n"
                + getString(R.string.report_trackers) + ": " + trackers + "\n"
                + getString(R.string.report_pages) + ": " + pages + "\n\n"
                + getString(R.string.report_no_history_note);
        new AlertDialog.Builder(this).setTitle(getString(R.string.report_today))
                .setMessage(msg).setPositiveButton(android.R.string.ok, null).show();
    }

    private void showPermissionDashboard() {
        Map<String, List<String>> grants = MrNobodyApp.permissions().allGrants();
        if (grants.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(R.string.perm_dash_title)
                    .setMessage(R.string.perm_dash_empty).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : grants.entrySet()) {
            sb.append(e.getKey()).append(": ").append(joinPerms(e.getValue())).append("\n");
        }
        new AlertDialog.Builder(this).setTitle(R.string.perm_dash_title)
                .setMessage(sb.toString()).setPositiveButton(android.R.string.ok, null).show();
    }

    private void showSiteOverrides() {
        Map<String, Map<String, String>> all = MrNobodyApp.perSite().allOverrides();
        if (all.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(R.string.settings_site_overrides)
                    .setMessage("No per-site overrides yet.").setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        List<String> hosts = new java.util.ArrayList<>(all.keySet());
        new AlertDialog.Builder(this).setTitle(R.string.settings_site_overrides)
                .setItems(hosts.toArray(new String[0]), (d, which) -> {
                    String host = hosts.get(which);
                    new AlertDialog.Builder(this).setTitle(host)
                            .setItems(new String[]{getString(R.string.site_reset)}, (d2, w2) ->
                                    MrNobodyApp.perSite().clear(host)).show();
                }).show();
    }

    private void showStorageDashboard() {
        long cache = dirSize(getCacheDir());
        String msg = getString(R.string.storage_cache) + ": " + humanBytes(cache);
        new AlertDialog.Builder(this).setTitle(R.string.storage_title)
                .setMessage(msg)
                .setNeutralButton(android.R.string.ok, null)
                .setPositiveButton(R.string.storage_clear_cache, (d, w) -> clearCache())
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.app_name))
                .setMessage(getString(R.string.tagline) + "\n\n"
                        + "A tiny native privacy browser.\nNo ads, no trackers, no history by default.\n\n"
                        + "Filter list version: " + MrNobodyApp.filters().getFilterVersion() + "\n"
                        + "Profile: " + settings.getProfile().label() + "\n\n"
                        + "Privacy is not anonymity.")
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void clearCache() {
        try {
            android.webkit.WebView wv = new android.webkit.WebView(this);
            wv.clearCache(true);
            wv.destroy();
        } catch (Exception ignored) { }
    }

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

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(value)) return i;
        return 0;
    }
}
