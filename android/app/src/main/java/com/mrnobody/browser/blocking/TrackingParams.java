package com.mrnobody.browser.blocking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative URL tracking-parameter removal (V2).
 *
 * Removes a fixed set of well-known tracking parameters (utm_*, gclid, fbclid,
 * etc.) from the query string. Only removes parameters known to be
 * tracking-related; everything else is preserved, so legitimate links are not
 * broken. This is pure Java and fully unit-testable.
 *
 * The feature is off by default? No — it is ON in the Balanced profile but can
 * be disabled globally in Settings (see Settings.isParamStrippingEnabled()).
 */
public final class TrackingParams {

    // Known tracking parameters. Conservative: this list is intentionally
    // short and widely-agreed-upon.
    private static final Set<String> EXACT = new HashSet<>(Arrays.asList(
            "gclid",        // Google Ads
            "fbclid",       // Facebook
            "mc_cid",       // Mailchimp
            "mc_eid",       // Mailchimp
            "igshid",       // Instagram share
            "yclid",        // Yandex
            "dclid",        // DoubleClick
            "msclkid",      // Microsoft Ads
            "twclid",       // Twitter Ads
            "wickedid",     // Wicked Reports
            "vero_id",      // Vero
            "oly_anon_id",  // Omeda
            "oly_enc_id",   // Omeda
            "s_cid",        // Adobe
            "ref_src",      // generic referrer
            "ref_url"       // generic referrer
    ));

    private static final String[] PREFIXES = {
            "utm_",   // Google Analytics UTM family
            "pk_",    // Matomo
            "mtm_"    // Matomo
    };

    private TrackingParams() {
    }

    /**
     * Strip known tracking parameters from a URL, preserving the rest.
     * Returns the original string if there is no query or nothing to strip.
     * Never throws.
     */
    public static String strip(String url) {
        if (url == null || url.isEmpty()) return url;
        int hash = url.indexOf('#');
        String base = hash >= 0 ? url.substring(0, hash) : url;
        String fragment = hash >= 0 ? url.substring(hash) : "";

        int q = base.indexOf('?');
        if (q < 0 || q == base.length() - 1) return url; // no query to touch

        String path = base.substring(0, q);
        String query = base.substring(q + 1);

        List<String> kept = new ArrayList<>();
        boolean changed = false;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                kept.add(pair);
                continue;
            }
            int eq = pair.indexOf('=');
            String name = (eq >= 0 ? pair.substring(0, eq) : pair).trim();
            if (isTracking(name)) {
                changed = true;
                continue; // drop this parameter
            }
            kept.add(pair);
        }

        if (!changed) return url;
        if (kept.isEmpty()) return path + fragment;
        return path + "?" + join(kept) + fragment;
    }

    private static boolean isTracking(String name) {
        String n = name.toLowerCase();
        if (EXACT.contains(n)) return true;
        for (String prefix : PREFIXES) {
            if (n.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String join(List<String> pairs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pairs) {
            if (sb.length() > 0) sb.append('&');
            sb.append(p);
        }
        return sb.toString();
    }
}
