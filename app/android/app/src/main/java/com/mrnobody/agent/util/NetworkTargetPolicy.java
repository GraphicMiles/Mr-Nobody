package com.mrnobody.agent.util;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Rejects agent-controlled web targets that can address the device or its LAN.
 *
 * <p>Visible browsing remains free to open a router or local development page;
 * this policy is for autonomous tools. Hostname resolution is optional because
 * resolving locally while a SOCKS/privacy proxy is active would itself leak DNS.
 */
public final class NetworkTargetPolicy {

    /** DNS seam for deterministic tests. */
    interface Resolver {
        InetAddress[] resolve(String host) throws Exception;
    }

    private NetworkTargetPolicy() { }

    /** A human-readable refusal, or null when the URL is a public web target. */
    public static String publicReason(String raw, boolean resolveDns) {
        return publicReason(raw, resolveDns, InetAddress::getAllByName);
    }

    static String publicReason(String raw, boolean resolveDns, Resolver resolver) {
        final URI uri;
        try {
            uri = new URI(raw == null ? "" : raw.trim());
        } catch (Exception e) {
            return "URL is invalid";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            return "URL must use HTTPS";
        }
        return hostAndTargetReason(uri, resolveDns, resolver);
    }

    /**
     * The same host/target checks as {@link #publicReason} but without the
     * "must use HTTPS" requirement. Used by the user-facing download engine,
     * which may fetch a public http:// site the user chose, while still
     * refusing local-network, private, loopback, and obscured-numeric targets.
     * Agent tools keep calling {@link #requirePublic} and therefore stay HTTPS.
     */
    public static String publicHostReason(String raw, boolean resolveDns) {
        return publicHostReason(raw, resolveDns, InetAddress::getAllByName);
    }

    static String publicHostReason(String raw, boolean resolveDns, Resolver resolver) {
        final URI uri;
        try {
            uri = new URI(raw == null ? "" : raw.trim());
        } catch (Exception e) {
            return "URL is invalid";
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http")
                && !scheme.equalsIgnoreCase("https"))) {
            return "URL must use HTTP(S)";
        }
        return hostAndTargetReason(uri, resolveDns, resolver);
    }

    /** Host-level checks shared by both entry points, minus the scheme rule. */
    private static String hostAndTargetReason(URI uri, boolean resolveDns,
                                              Resolver resolver) {
        if (uri.getRawUserInfo() != null) return "URL credentials are not allowed";
        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) return "URL needs a valid host";
        try {
            host = host.indexOf(':') >= 0
                    ? host.toLowerCase(Locale.ROOT)
                    : IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
                            .toLowerCase(Locale.ROOT);
            // A final DNS root dot does not change the name. Remove it before
            // checking reserved suffixes so localhost. cannot bypass policy.
            while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.isEmpty()) return "URL host is invalid";
        } catch (Exception e) {
            return "URL host is invalid";
        }
        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")
                || host.equals("home.arpa") || host.endsWith(".home.arpa")
                || host.endsWith(".lan") || host.endsWith(".home")
                || host.endsWith(".corp")) {
            return "local-network URLs are not available to the agent";
        }

        // Numeric-looking non-canonical hosts (decimal/octal/hex IPv4 forms)
        // have historically been interpreted differently by URL and DNS stacks.
        if (looksLikeObscuredNumber(host)) {
            return "non-canonical numeric hosts are not allowed";
        }

        InetAddress literal = parseLiteral(host);
        if (literal == null && (host.indexOf(':') >= 0
                || host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) {
            return "numeric host is invalid";
        }
        if (literal == null && host.indexOf('.') < 0) {
            return "single-label local names are not available to the agent";
        }
        if (literal != null && !isPublic(literal)) {
            return "private, loopback, link-local, and reserved addresses are blocked";
        }
        if (literal != null || !resolveDns) return null;

        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses == null || addresses.length == 0) return "host did not resolve";
            for (InetAddress address : addresses) {
                if (address == null || !isPublic(address)) {
                    return "host resolves to a private, loopback, link-local, or reserved address";
                }
            }
            return null;
        } catch (Exception e) {
            return "host could not be resolved safely";
        }
    }

    /** Throw before an autonomous tool opens the target. */
    public static void requirePublic(String url, boolean resolveDns) throws java.io.IOException {
        String reason = publicReason(url, resolveDns);
        if (reason != null) throw new java.io.IOException("Refused URL: " + reason);
    }

    private static InetAddress parseLiteral(String host) {
        try {
            // Avoid turning ordinary names into DNS lookups. A colon denotes
            // IPv6; canonical dotted decimal denotes IPv4.
            if (host.indexOf(':') >= 0 || host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                return InetAddress.getByName(host);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean looksLikeObscuredNumber(String host) {
        if (host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        return host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)"
                + "(?:\\.(?:0x[0-9a-f]+|[0-9]+)){0,3}");
    }

    /** True only for globally routable unicast addresses. */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) return publicIpv4(b);
        if (b.length == 16) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if ((first & 0xfe) == 0xfc) return false;       // fc00::/7 unique local
            if (first == 0xfe && (second & 0xc0) == 0x80) return false; // fe80::/10
            if (first == 0xff) return false;               // multicast
            // Documentation prefix 2001:db8::/32.
            if (first == 0x20 && second == 0x01
                    && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) return false;
            return true;
        }
        return false;
    }

    private static boolean publicIpv4(byte[] bytes) {
        int a = bytes[0] & 0xff;
        int b = bytes[1] & 0xff;
        int c = bytes[2] & 0xff;
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && (b & 0xc0) == 64) return false;     // shared 100.64/10
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 192 && b == 0 && c == 0) return false;
        if (a == 192 && b == 0 && c == 2) return false;     // documentation
        if (a == 198 && (b == 18 || b == 19)) return false; // benchmark
        if (a == 198 && b == 51 && c == 100) return false;  // documentation
        if (a == 203 && b == 0 && c == 113) return false;   // documentation
        return true;
    }
}
