package com.mrnobody.browser.blocking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory compiled blocklist.
 *
 * Supported rule syntax (a conservative subset of Adblock Plus network rules):
 *   ||domain^        block this domain and all subdomains (any path)
 *   ||domain         same as above
 *   ||domain/path    block URLs on this domain whose path starts with /path
 *   ||domain/path*   path prefix (wildcard after)
 *   *substr*         substring match anywhere in the URL
 *   !comment         ignored
 *   @@rule           exception (allowlist) — parsed but treated as non-blocking in V1
 *
 * Element-hiding rules (##, #@#) are not network rules and are ignored here.
 *
 * A malformed rule is skipped; a malformed list must never crash the browser
 * (see README.md — privacy and security invariants).
 */
public final class Blocklist {

    /** A single category (ads or trackers). */
    static final class Category {
        final Set<String> domains = new HashSet<>();            // exact domain + subdomains
        final Map<String, List<String>> pathRules = new HashMap<>(); // domain -> path prefixes
        final List<String[]> wildcards = new ArrayList<>();     // segments split on '*'

        boolean matchesHost(String host) {
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (domains.contains(host)) return true;
            int dot = host.indexOf('.');
            while (dot >= 0) {
                String suffix = host.substring(dot + 1);
                if (domains.contains(suffix)) return true;
                dot = host.indexOf('.', dot + 1);
            }
            return false;
        }

        boolean matchesPath(String host, String path) {
            if (host == null) return false;
            // Exact host first — the common case, and O(1).
            if (hasPrefix(pathRules.get(host), path)) return true;
            // Then every registered ancestor domain: sub.example.com must match
            // a rule keyed on example.com. The previous loop broke after the
            // first ancestor, so a second registered ancestor was never checked.
            for (Map.Entry<String, List<String>> e : pathRules.entrySet()) {
                String rule = e.getKey();
                if (!host.equals(rule) && host.endsWith("." + rule)
                        && hasPrefix(e.getValue(), path)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasPrefix(List<String> prefixes, String path) {
            if (prefixes == null) return false;
            for (String prefix : prefixes) {
                if (path.startsWith(prefix)) return true;
            }
            return false;
        }

        boolean matchesWildcard(String target) {
            for (String[] segs : wildcards) {
                if (matchSegments(segs, target)) return true;
            }
            return false;
        }
    }

    static boolean matchSegments(String[] segs, String target) {
        int pos = 0;
        for (String seg : segs) {
            int idx = target.indexOf(seg, pos);
            if (idx < 0) return false;
            pos = idx + seg.length();
        }
        return true;
    }

    final Category ads = new Category();
    final Category trackers = new Category();

    /** Parse one line into the given category. */
    public void addLine(String raw, Category cat) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("@@")) return;
        if (line.contains("##") || line.contains("#@#")) return;

        String body = line;
        boolean anchored = body.startsWith("||");
        if (anchored) body = body.substring(2);

        // Strip Adblock-Plus options ($third-party, $domain=..., $script, ...).
        // Conservative: we keep the rule and ignore the options (still block).
        int dollar = body.indexOf('$');
        if (dollar >= 0) body = body.substring(0, dollar);

        boolean endSep = body.endsWith("^");
        if (endSep) body = body.substring(0, body.length() - 1);

        // strip any remaining trailing separators/pipes
        body = body.replaceAll("[|^]+$", "");

        if (body.isEmpty()) return;

        if (anchored) {
            // host (and optional path) rule
            int slash = body.indexOf('/');
            if (slash < 0) {
                // pure domain
                cat.domains.add(body.toLowerCase(Locale.ROOT));
            } else {
                String host = body.substring(0, slash).toLowerCase(Locale.ROOT);
                String path = body.substring(slash);
                // keep path prefix without trailing wildcard for prefix matching
                if (path.endsWith("*")) path = path.substring(0, path.length() - 1);
                cat.pathRules.computeIfAbsent(host, k -> new ArrayList<>()).add(path);
            }
            return;
        }

        // un-anchored rule: treat '*' segments as a substring sequence
        if (body.contains("*")) {
            String[] segs = body.split("\\*+");
            if (segs.length > 0) {
                cat.wildcards.add(segs);
            }
            return;
        }

        // plain hostname fallback
        cat.domains.add(body.toLowerCase(Locale.ROOT));
    }
}
