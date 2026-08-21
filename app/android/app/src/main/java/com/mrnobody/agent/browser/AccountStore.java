package com.mrnobody.agent.browser;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebView;

import com.mrnobody.browser.net.ProfileManager;
import com.mrnobody.security.EncryptedPreferences;

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

    private final EncryptedPreferences secrets;

    public AccountStore(Context context) {
        // Cookie headers are credentials. The old plaintext JSON under KEY is
        // migrated in place to a Keystore-backed AES-GCM envelope on first read.
        this.secrets = new EncryptedPreferences(context, PREFS,
                "mrnobody_account_credentials_v1");
    }

    public synchronized List<AccountGrant> list() {
        List<AccountGrant> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(secrets.getString(KEY, "[]"));
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
            if (g.matchesHost(h)) return g;
        }
        return null;
    }

    /** Build a request header from every cookie whose domain/path/scheme still matches. */
    public synchronized String headerForUrl(String url) {
        StringBuilder out = new StringBuilder();
        for (AccountGrant grant : list()) {
            String part = grant.headerForUrl(url);
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append("; ");
            out.append(part);
        }
        return out.toString();
    }

    /** Inject only URL-matching grants into a WebView's cookie jar. Call on the main thread. */
    public synchronized void applyTo(WebView webView, String url) {
        if (webView == null || url == null || url.isEmpty()) return;
        CookieManager cm = ProfileManager.cookiesFor(webView);
        if (cm == null) cm = CookieManager.getInstance();
        boolean wrote = false;
        for (AccountGrant grant : list()) {
            for (String cookie : grant.setCookieLinesForUrl(url)) {
                cm.setCookie(url, cookie);
                wrote = true;
            }
        }
        if (wrote) cm.flush();
    }

    private void save(List<AccountGrant> grants) {
        JSONArray arr = new JSONArray();
        for (AccountGrant g : grants) arr.put(g.toJson());
        secrets.putString(KEY, arr.toString());
    }
}
