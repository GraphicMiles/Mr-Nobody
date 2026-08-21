package com.mrnobody.agent.browser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A user-granted set of cookies with their normal request boundaries intact. */
public final class AccountGrant {

    public enum Source { PASTED, TAB }

    /** One cookie; package-visible so AccountStore can inject matching entries. */
    static final class CookieEntry {
        final String name;
        final String value;
        final String domain;
        final boolean hostOnly;
        final String path;
        final boolean secure;
        final boolean httpOnly;
        final String sameSite;
        final long expiresAtMs;

        CookieEntry(String name, String value, String domain, boolean hostOnly,
                    String path, boolean secure, boolean httpOnly,
                    String sameSite, long expiresAtMs) {
            this.name = name;
            this.value = value;
            this.domain = normaliseHost(domain);
            this.hostOnly = hostOnly;
            this.path = normalisePath(path);
            this.secure = secure;
            this.httpOnly = httpOnly;
            this.sameSite = normaliseSameSite(sameSite);
            this.expiresAtMs = expiresAtMs;
        }

        boolean matches(URI uri, long now) {
            if (uri == null || uri.getHost() == null) return false;
            String requestHost = normaliseHost(uri.getHost());
            if (requestHost.isEmpty() || domain.isEmpty()) return false;
            boolean domainMatch = hostOnly
                    ? requestHost.equals(domain)
                    : requestHost.equals(domain) || requestHost.endsWith("." + domain);
            if (!domainMatch) return false;
            if (secure && !"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (expiresAtMs > 0 && now >= expiresAtMs) return false;
            String requestPath = uri.getPath();
            if (requestPath == null || requestPath.isEmpty()) requestPath = "/";
            return pathMatches(requestPath, path);
        }

        String headerPair() { return name + "=" + value; }

        String setCookieLine() {
            StringBuilder line = new StringBuilder(headerPair()).append("; Path=").append(path);
            if (!hostOnly) line.append("; Domain=").append(domain);
            if (secure) line.append("; Secure");
            if (httpOnly) line.append("; HttpOnly");
            if (!sameSite.isEmpty()) line.append("; SameSite=").append(sameSite);
            if (expiresAtMs > 0) line.append("; Expires=").append(httpDate(expiresAtMs));
            return line.toString();
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("value", value);
                o.put("domain", domain);
                o.put("hostOnly", hostOnly);
                o.put("path", path);
                o.put("secure", secure);
                o.put("httpOnly", httpOnly);
                o.put("sameSite", sameSite);
                o.put("expiresAt", expiresAtMs);
            } catch (Exception ignored) { }
            return o;
        }
    }

    public final String host;
    /** Legacy/public projection; request code uses {@link #headerForUrl(String)}. */
    public final String header;
    public final List<String> names;
    public final Source source;
    public final long grantedAt;
    private final List<CookieEntry> cookies;

    private AccountGrant(String host, List<CookieEntry> cookies,
                         Source source, long grantedAt) {
        this.host = normaliseHost(host);
        this.cookies = cookies == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(cookies));
        List<String> n = new ArrayList<>();
        StringBuilder h = new StringBuilder();
        for (CookieEntry cookie : this.cookies) {
            if (cookie == null || cookie.name.isEmpty() || cookie.value.isEmpty()) continue;
            n.add(cookie.name);
            if (h.length() > 0) h.append("; ");
            h.append(cookie.headerPair());
        }
        this.names = Collections.unmodifiableList(n);
        this.header = h.toString();
        this.source = source == null ? Source.PASTED : source;
        this.grantedAt = grantedAt;
    }

    public boolean isEmpty() { return host.isEmpty() || cookies.isEmpty(); }

    /** Parse a Cookie-Editor array or a raw Cookie request header. */
    public static AccountGrant parse(String raw, String hostHint, Source source) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String text = raw.trim();
        return text.startsWith("[")
                ? fromJson(text, hostHint, source)
                : fromHeader(text, hostHint, source);
    }

    private static AccountGrant fromHeader(String header, String hostHint, Source source) {
        String host = normaliseHost(hostHint);
        if (!isGrantHost(host)) return null;
        boolean secure = !hasHttpScheme(hostHint);
        List<CookieEntry> entries = new ArrayList<>();
        for (String part : header.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String name = p.substring(0, eq).trim();
            String value = p.substring(eq + 1).trim();
            if (name.isEmpty() || value.isEmpty() || isAttribute(name)) continue;
            // A raw request header carries no attribute metadata. Bind it to
            // the exact host and choose the safer HttpOnly default rather than
            // injecting a previously protected session into page JavaScript.
            entries.add(new CookieEntry(name, value, host, true, "/", secure,
                    true, "", 0));
        }
        return entries.isEmpty() ? null
                : new AccountGrant(host, entries, source, System.currentTimeMillis());
    }

    private static AccountGrant fromJson(String json, String hostHint, Source source) {
        try {
            JSONArray arr = new JSONArray(json);
            // The user grants a site, not an arbitrary collection of domains
            // named by an untrusted export. Keep that explicit site as an
            // outer boundary in addition to each cookie's RFC scope.
            String hint = normaliseHost(hostHint);
            if (!isGrantHost(hint)) return null;
            String displayHost = hint;
            List<CookieEntry> entries = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                String value = o.optString("value", "").trim();
                if (name.isEmpty() || value.isEmpty() || isAttribute(name)) continue;
                String rawDomain = o.optString("domain", "").trim();
                String domain = normaliseHost(rawDomain.isEmpty() ? hint : rawDomain);
                if (domain.isEmpty()) continue;
                if (!hint.isEmpty() && !domainMatches(hint, domain)) continue;
                if (displayHost.isEmpty()) displayHost = domain;
                boolean hostOnly = o.has("hostOnly")
                        ? o.optBoolean("hostOnly", false)
                        : !rawDomain.startsWith(".");
                String path = o.optString("path", "/");
                boolean secure = o.has("secure") ? o.optBoolean("secure", true) : true;
                boolean httpOnly = o.has("httpOnly")
                        ? o.optBoolean("httpOnly", true) : true;
                String sameSite = o.optString("sameSite", "");
                long expiresAt = expirationMs(o);
                entries.add(new CookieEntry(name, value, domain, hostOnly,
                        path, secure, httpOnly, sameSite, expiresAt));
            }
            return entries.isEmpty() || displayHost.isEmpty() ? null
                    : new AccountGrant(displayHost, entries, source, System.currentTimeMillis());
        } catch (Exception e) {
            return fromHeader(json, hostHint, source);
        }
    }

    /** Cookie request header containing only entries valid for this exact URL. */
    public String headerForUrl(String url) {
        URI uri = parseUri(url);
        // Imported account data is a credential grant. Even cookies that were
        // exported without Secure are never attached to cleartext requests.
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || !withinGrantBoundary(uri)) return "";
        long now = System.currentTimeMillis();
        StringBuilder out = new StringBuilder();
        for (CookieEntry cookie : cookies) {
            if (!cookie.matches(uri, now)) continue;
            if (out.length() > 0) out.append("; ");
            out.append(cookie.headerPair());
        }
        return out.toString();
    }

    List<String> setCookieLinesForUrl(String url) {
        URI uri = parseUri(url);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || !withinGrantBoundary(uri)) return Collections.emptyList();
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        for (CookieEntry cookie : cookies) {
            if (cookie.matches(uri, now)) out.add(cookie.setCookieLine());
        }
        return out;
    }

    boolean matchesHost(String requestHost) {
        String h = normaliseHost(requestHost);
        if (h.isEmpty() || !(h.equals(host) || h.endsWith("." + host))) return false;
        for (CookieEntry cookie : cookies) {
            if (cookie.hostOnly ? h.equals(cookie.domain)
                    : h.equals(cookie.domain) || h.endsWith("." + cookie.domain)) return true;
        }
        return false;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("host", host);
            o.put("header", header); // retained for downgrade/backward readability
            JSONArray n = new JSONArray();
            for (String name : names) n.put(name);
            o.put("names", n);
            JSONArray c = new JSONArray();
            for (CookieEntry cookie : cookies) c.put(cookie.toJson());
            o.put("cookies", c);
            o.put("source", source.name());
            o.put("at", grantedAt);
        } catch (Exception ignored) { }
        return o;
    }

    public static AccountGrant fromStored(JSONObject o) {
        if (o == null) return null;
        Source src;
        try {
            src = Source.valueOf(o.optString("source", "PASTED"));
        } catch (Exception e) {
            src = Source.PASTED;
        }
        String host = normaliseHost(o.optString("host", ""));
        List<CookieEntry> entries = new ArrayList<>();
        JSONArray c = o.optJSONArray("cookies");
        if (c != null) {
            for (int i = 0; i < c.length(); i++) {
                JSONObject item = c.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                String value = item.optString("value", "").trim();
                String domain = normaliseHost(item.optString("domain", host));
                if (name.isEmpty() || value.isEmpty() || domain.isEmpty()) continue;
                entries.add(new CookieEntry(name, value, domain,
                        item.optBoolean("hostOnly", true),
                        item.optString("path", "/"),
                        item.optBoolean("secure", true),
                        item.optBoolean("httpOnly", true),
                        item.optString("sameSite", ""),
                        item.optLong("expiresAt", 0)));
            }
        }
        if (entries.isEmpty()) {
            // Safe migration of the old flattened format: bind it to the exact
            // saved host and HTTPS rather than repeating the old over-broad match.
            AccountGrant migrated = fromHeader(o.optString("header", ""),
                    "https://" + host, src);
            if (migrated == null) return null;
            return new AccountGrant(host, migrated.cookies, src, o.optLong("at", 0));
        }
        AccountGrant grant = new AccountGrant(host, entries, src, o.optLong("at", 0));
        return grant.isEmpty() ? null : grant;
    }

    public String describe() { return host + " (" + String.join(", ", names) + ")"; }

    @Override public String toString() { return "AccountGrant{" + describe() + "}"; }

    static String normaliseHost(String host) {
        if (host == null || host.isEmpty()) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("http://") || h.startsWith("https://")) {
            try {
                String found = new URI(h).getHost();
                return found == null ? "" : found.toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                return "";
            }
        }
        if (h.startsWith(".")) h = h.substring(1);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static boolean isAttribute(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("httponly") || n.equals("secure") || n.equals("path")
                || n.equals("domain") || n.equals("expires") || n.equals("max-age")
                || n.equals("samesite");
    }

    private static boolean hasHttpScheme(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).startsWith("http://");
    }

    private static URI parseUri(String url) {
        try {
            URI uri = new URI(url == null ? "" : url);
            if (uri.getHost() == null) return null;
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                    ? uri : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean withinGrantBoundary(URI uri) {
        String requestHost = uri == null ? "" : normaliseHost(uri.getHost());
        return !requestHost.isEmpty()
                && (requestHost.equals(host) || requestHost.endsWith("." + host));
    }

    private static boolean isGrantHost(String host) {
        return host != null && host.indexOf('.') > 0 && !host.endsWith(".");
    }

    private static boolean domainMatches(String requestHost, String cookieDomain) {
        return requestHost.equals(cookieDomain) || requestHost.endsWith("." + cookieDomain);
    }

    private static boolean pathMatches(String requestPath, String cookiePath) {
        if (requestPath.equals(cookiePath)) return true;
        if (!requestPath.startsWith(cookiePath)) return false;
        return cookiePath.endsWith("/")
                || (requestPath.length() > cookiePath.length()
                    && requestPath.charAt(cookiePath.length()) == '/');
    }

    private static String normalisePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') return "/";
        return path;
    }

    private static String normaliseSameSite(String sameSite) {
        if (sameSite == null) return "";
        String value = sameSite.trim();
        if (value.equalsIgnoreCase("strict")) return "Strict";
        if (value.equalsIgnoreCase("lax")) return "Lax";
        if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("no_restriction")) {
            return "None";
        }
        return "";
    }

    private static String httpDate(long timeMs) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return format.format(new java.util.Date(timeMs));
    }

    private static long expirationMs(JSONObject o) {
        double seconds = 0;
        if (o.has("expirationDate")) seconds = o.optDouble("expirationDate", 0);
        else if (o.has("expiration")) seconds = o.optDouble("expiration", 0);
        else if (o.has("expires")) seconds = o.optDouble("expires", 0);
        if (seconds <= 0) return 0;
        // Cookie exports use Unix seconds; tolerate an already-millisecond value.
        return seconds > 10_000_000_000L ? (long) seconds : (long) (seconds * 1000L);
    }
}
