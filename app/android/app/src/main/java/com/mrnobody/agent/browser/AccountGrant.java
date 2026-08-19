package com.mrnobody.agent.browser;

import com.mrnobody.agent.util.Hosts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A cookie grant the user made for one host.
 *
 * <p>This is how account automation works without Twikit passwords or a
 * stealth Cloudflare solver. The user logs in with the real WebView (or
 * pastes a Cookie-Editor export). We store what they handed over, inject
 * it on later fetches, and never invent a session.
 *
 * <p>Values never appear in {@link #toString()} or the UI. A leaked log
 * line must not be a session.
 */
public final class AccountGrant {

    public enum Source { PASTED, TAB }

    public final String host;
    public final String header;
    public final List<String> names;
    public final Source source;
    public final long grantedAt;

    AccountGrant(String host, String header, List<String> names,
                 Source source, long grantedAt) {
        this.host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        this.header = header == null ? "" : header;
        this.names = names == null ? Collections.emptyList() : names;
        this.source = source == null ? Source.PASTED : source;
        this.grantedAt = grantedAt;
    }

    public boolean isEmpty() {
        return host.isEmpty() || header.isEmpty();
    }

    /**
     * Parse a Cookie-Editor JSON array or a {@code name=value; name2=value2}
     * header. {@code hostHint} is used when the paste has no domain.
     */
    public static AccountGrant parse(String raw, String hostHint, Source source) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String text = raw.trim();
        if (text.startsWith("[")) {
            return fromJson(text, hostHint, source);
        }
        return fromHeader(text, hostHint, source);
    }

    private static AccountGrant fromHeader(String header, String hostHint, Source source) {
        List<String> names = new ArrayList<>();
        StringBuilder cleaned = new StringBuilder();
        for (String part : header.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String name = p.substring(0, eq).trim();
            String value = p.substring(eq + 1).trim();
            if (name.isEmpty() || value.isEmpty()) continue;
            if (isReserved(name)) continue;
            names.add(name);
            if (cleaned.length() > 0) cleaned.append("; ");
            cleaned.append(name).append('=').append(value);
        }
        String host = normaliseHost(hostHint);
        if (host.isEmpty() || names.isEmpty()) return null;
        return new AccountGrant(host, cleaned.toString(), names, source,
                System.currentTimeMillis());
    }

    private static AccountGrant fromJson(String json, String hostHint, Source source) {
        try {
            JSONArray arr = new JSONArray(json);
            List<String> names = new ArrayList<>();
            StringBuilder header = new StringBuilder();
            String host = normaliseHost(hostHint);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                String value = o.optString("value", "").trim();
                if (name.isEmpty() || value.isEmpty() || isReserved(name)) continue;
                String domain = o.optString("domain", "");
                if (host.isEmpty()) host = normaliseHost(domain);
                names.add(name);
                if (header.length() > 0) header.append("; ");
                header.append(name).append('=').append(value);
            }
            if (host.isEmpty() || names.isEmpty()) return null;
            return new AccountGrant(host, header.toString(), names, source,
                    System.currentTimeMillis());
        } catch (Exception e) {
            return fromHeader(json, hostHint, source);
        }
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("host", host);
            o.put("header", header);
            JSONArray n = new JSONArray();
            for (String name : names) n.put(name);
            o.put("names", n);
            o.put("source", source.name());
            o.put("at", grantedAt);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static AccountGrant fromStored(JSONObject o) {
        if (o == null) return null;
        List<String> names = new ArrayList<>();
        JSONArray n = o.optJSONArray("names");
        if (n != null) {
            for (int i = 0; i < n.length(); i++) names.add(n.optString(i));
        }
        Source src;
        try {
            src = Source.valueOf(o.optString("source", "PASTED"));
        } catch (Exception e) {
            src = Source.PASTED;
        }
        AccountGrant g = new AccountGrant(
                o.optString("host", ""),
                o.optString("header", ""),
                names, src, o.optLong("at", 0L));
        return g.isEmpty() ? null : g;
    }

    /** Public description: host and cookie names, never values. */
    public String describe() {
        return host + " (" + String.join(", ", names) + ")";
    }

    @Override
    public String toString() {
        return "AccountGrant{" + describe() + "}";
    }

    static String normaliseHost(String host) {
        if (host == null || host.isEmpty()) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("http://") || h.startsWith("https://")) {
            String found = Hosts.firstIn(h);
            return found == null ? "" : found;
        }
        if (h.startsWith(".")) h = h.substring(1);
        if (h.startsWith("www.")) h = h.substring(4);
        return h;
    }

    private static boolean isReserved(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("__host-")
                || n.equals("httponly")
                || n.equals("secure")
                || n.equals("path")
                || n.equals("domain")
                || n.equals("expires")
                || n.equals("max-age")
                || n.equals("samesite");
    }
}
