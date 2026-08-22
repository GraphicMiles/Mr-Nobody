package com.mrnobody.browser.blocking;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Blocks known advertising/betting destinations when another page sends the user there. */
public final class RedirectGuard {

    private static final Set<String> UNWANTED_DESTINATIONS = new HashSet<>(Arrays.asList(
            // Gambling destinations — the classic forced-redirect target.
            "bet9ja.com", "bet9ja.net", "betnaija.com",
            "stake.com", "stake.bet",
            "1xbet.com", "1xbet.ng", "22bet.com", "melbet.com",
            "sportybet.com", "betway.com", "parimatch.com",
            "betking.com", "betbonanza.com", "bangbet.com",
            // Pop-up / pop-under ad networks often used by streaming sites when
            // a button or link is tapped. Blocking the redirect is safer than
            // letting the page open an unknown advertising surface.
            "clickadu.com", "juicyads.com", "hilltopads.net", "trafficjunky.com",
            "popcash.net", "ad-maven.com", "yoads.net", "onclickpredirect.com",
            "adtrustmedia.com", "plugrush.com",
            // Ad-heavy link shorteners / redirect portals. Not legitimate
            // content destinations; they exist to insert an advertisement in
            // front of whatever the user actually asked for.
            "adf.ly", "ouo.io", "shorte.st", "bc.vc", "tlnk.io", "adshort.co"
    ));

    private RedirectGuard() {
    }

    /**
     * True only for a cross-site top-level navigation into a known unwanted
     * destination. Navigation within a site is left alone, and subresources are
     * handled by the normal filter engine.
     */
    public static boolean shouldBlock(String currentUrl, String targetUrl, boolean mainFrame) {
        if (!mainFrame) return false;
        String current = hostOf(currentUrl);
        String targetRoot = matchedDestination(hostOf(targetUrl));
        if (current.isEmpty() || targetRoot.isEmpty()) return false;

        // Root and sibling subdomains of one known destination are the same
        // site. A transition from any other site is the redirect pattern this
        // guard owns.
        return !targetRoot.equals(matchedDestination(current));
    }

    static boolean matchesKnown(String host) {
        return !matchedDestination(host).isEmpty();
    }

    private static String matchedDestination(String host) {
        for (String known : UNWANTED_DESTINATIONS) {
            if (host.equals(known) || host.endsWith("." + known)) return known;
        }
        return "";
    }

    static String hostOf(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        try {
            String host = new URI(url.trim()).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            while (host.endsWith(".") && host.length() > 1) {
                host = host.substring(0, host.length() - 1);
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }
}
