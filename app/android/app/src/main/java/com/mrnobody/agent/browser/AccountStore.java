package com.mrnobody.agent.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebView;

import com.mrnobody.agent.util.Hosts;
import com.mrnobody.browser.net.ProfileManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * User-granted site sessions, stored on-device.
 *
 * <p>Agent-Reach's Cookie-Editor rule: we never scrape the browser jar
 * unless the user taps grant. A task WebView is ephemeral; this store is
 * the only way a later run sees the same login.
 */
public final class AccountStore {

    private static final String PREFS = "mrnobody_accounts";
    private static final String KEY = "grants";

    private final SharedPreferences prefs;

    public AccountStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<AccountGrant> list() {
        List<AccountGrant> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                AccountGrant g = AccountGrant.fromStored(arr.optJSONObject(i));
                if (g != null) out.add(g);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized AccountGrant grant(AccountGrant grant) {
        if (grant == null || grant.isEmpty()) return null;
        List<AccountGrant> all = list();
        List<AccountGrant> next = new ArrayList<>();
        for (AccountGrant g : all) {
            if (!g.host.equals(grant.host)) next.add(g);
        }
        next.add(grant);
        save(next);
        return grant;
    }

    public synchronized boolean revoke(String host) {
        String h = AccountGrant.normaliseHost(host);
        if (h.isEmpty()) return false;
        List<AccountGrant> all = list();
        List<AccountGrant> next = new ArrayList<>();
        boolean removed = false;
        for (AccountGrant g : all) {
            if (g.host.equals(h)) removed = true;
            else next.add(g);
        }
        if (removed) save(next);
        return removed;
    }

    public synchronized AccountGrant forHost(String host) {
        String h = AccountGrant.normaliseHost(host);
        if (h.isEmpty()) return null;
        for (AccountGrant g : list()) {
            if (h.equals(g.host) || h.endsWith("." + g.host) || g.host.endsWith("." + h)) {
                return g;
            }
        }
        return null;
    }

    public String headerForUrl(String url) {
        String host = Hosts.firstIn(url);
        AccountGrant g = forHost(host);
        return g == null ? "" : g.header;
    }

    /** Inject a grant into a WebView's cookie jar. Call on the main thread. */
    public void applyTo(WebView webView, String url) {
        if (webView == null || url == null || url.isEmpty()) return;
        String header = headerForUrl(url);
        if (header.isEmpty()) return;
        CookieManager cm = ProfileManager.cookiesFor(webView);
        if (cm == null) cm = CookieManager.getInstance();
        for (String part : header.split(";")) {
            String cookie = part.trim();
            if (!cookie.isEmpty()) cm.setCookie(url, cookie);
        }
        cm.flush();
    }

    private void save(List<AccountGrant> grants) {
        JSONArray arr = new JSONArray();
        for (AccountGrant g : grants) arr.put(g.toJson());
        prefs.edit().putString(KEY, arr.toString()).apply();
    }
}
