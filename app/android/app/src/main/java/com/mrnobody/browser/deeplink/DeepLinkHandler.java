package com.mrnobody.browser.deeplink;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses and routes deep links into Mr Nobody.
 *
 * Supported:
 *   mrnobody://open?url=<https-url>
 *   mrnobody://search?q=<query>
 *   mrnobody://task?instruction=<instruction>
 *   mrnobody://tabs | tasks | settings | privacy | downloads
 *
 * A plain http(s) URL (a VIEW intent shared from another app) is handled by the
 * caller via {@link #isWebUrl}.
 *
 * Pure Java (java.net.URI only) so it is unit-testable without Android stubs.
 */
public final class DeepLinkHandler {

    public enum Action { OPEN, SEARCH, TASK, TABS, TASKS, SETTINGS, PRIVACY, DOWNLOADS, CLEAR, NONE }

    public final Action action;
    public final String arg;   // url for OPEN, query for SEARCH, instruction for TASK

    private DeepLinkHandler(Action action, String arg) {
        this.action = action;
        this.arg = arg;
    }

    public static boolean isWebUrl(String uri) {
        if (uri == null) return false;
        String s = uri.toLowerCase();
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** Parse a deep link; returns Action.NONE with a null arg if unrecognized. */
    public static DeepLinkHandler parse(String uri) {
        if (uri == null || uri.isEmpty()) return new DeepLinkHandler(Action.NONE, null);
        try {
            URI u = new URI(uri);
            if (!"mrnobody".equalsIgnoreCase(u.getScheme())) {
                return new DeepLinkHandler(Action.NONE, null);
            }
            String path = u.getAuthority() != null ? u.getAuthority()
                    : (u.getPath() != null ? u.getPath().replace("/", "") : "");
            Map<String, String> q = parseQuery(u.getRawQuery());
            switch (path == null ? "" : path.toLowerCase()) {
                case "open":      return new DeepLinkHandler(Action.OPEN, q.get("url"));
                case "search":    return new DeepLinkHandler(Action.SEARCH, q.get("q"));
                case "task":      return new DeepLinkHandler(Action.TASK, q.get("instruction"));
                case "tabs":      return new DeepLinkHandler(Action.TABS, null);
                case "tasks":     return new DeepLinkHandler(Action.TASKS, null);
                case "settings":  return new DeepLinkHandler(Action.SETTINGS, null);
                case "privacy":   return new DeepLinkHandler(Action.PRIVACY, null);
                case "downloads": return new DeepLinkHandler(Action.DOWNLOADS, null);
                case "clear":     return new DeepLinkHandler(Action.CLEAR, null);
                default:          return new DeepLinkHandler(Action.NONE, null);
            }
        } catch (URISyntaxException e) {
            return new DeepLinkHandler(Action.NONE, null);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                if (!pair.isEmpty()) out.put(decode(pair), "");
            } else {
                out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }
}
